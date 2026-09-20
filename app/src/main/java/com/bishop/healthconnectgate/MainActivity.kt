package com.bishop.healthconnectgate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

private val BUILD_MARKER = "Version ${BuildConfig.VERSION_NAME}"

class MainActivity : ComponentActivity() {
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val prefs by lazy { getSharedPreferences("bridge_sync", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var debug: TextView
    private lateinit var domain: EditText
    private val permissionsLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        DiagnosticLogger(applicationContext) { cleanDomain() }.record("permissions_result", "Health Connect granted ${granted.size} requested permissions")
        lifecycleScope.launch { showPermissions() }
    }
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusPoller = object : Runnable {
        override fun run() {
            prefs.getString("status", null)?.let { status.text = it }
            debug.text = "$BUILD_MARKER\n" + DiagnosticLogger(applicationContext) { prefs.getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN) ?: BuildConfig.DEFAULT_GATEWAY_DOMAIN }.recentText()
            statusHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        installUncaughtExceptionCapture(applicationContext) { prefs.getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN) ?: BuildConfig.DEFAULT_GATEWAY_DOMAIN }
        lifecycleScope.launch(Dispatchers.IO) { DiagnosticLogger(applicationContext) { prefs.getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN) ?: BuildConfig.DEFAULT_GATEWAY_DOMAIN }.uploadPending() }
        status = TextView(this).apply { textSize = 17f; text = getString(R.string.app_name) + "\n" + BUILD_MARKER }
        debug = TextView(this).apply { textSize = 11f }
        domain = EditText(this).apply { hint = "Gateway domain, e.g. gateway.example.com"; setText(prefs.getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN)) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        layout.addView(status); layout.addView(debug); layout.addView(domain)
        layout.addView(Button(this).apply { text = "Sign in to Hermes"; setOnClickListener { login() } })
        layout.addView(Button(this).apply { text = "Grant Health Connect access"; setOnClickListener { requestHealthPermissions() } })
        layout.addView(Button(this).apply { text = "Sync all data"; setOnClickListener { startSync() } })
        setContentView(layout)
        showPermissions()
        SyncWorker.schedule(this)
        statusHandler.post(statusPoller)
    }

    override fun onDestroy() { statusHandler.removeCallbacks(statusPoller); super.onDestroy() }

    private fun cleanDomain(): String = domain.text.toString().trim().removePrefix("https://").removePrefix("http://").trimEnd('/').also { prefs.edit().putString("gateway_domain", it).apply() }
    private fun showPermissions() = lifecycleScope.launch { status.text = "Health Connect\nGranted: ${client.permissionController.getGrantedPermissions().size}" }
    private fun requestHealthPermissions() {
        val requested = RecordCatalog.types.map { HealthPermission.getReadPermission(it) }.toSet() +
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY, HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        DiagnosticLogger(applicationContext) { cleanDomain() }.record("permissions_request", "Requesting ${requested.size} Health Connect read permissions")
        permissionsLauncher.launch(requested)
    }
    private fun startSync() {
        val selectedDomain = cleanDomain()
        if (selectedDomain.isBlank()) { status.text = "Enter the gateway domain first"; return }
        lifecycleScope.launch {
            // A health-type foreground service cannot start (and crashes the app) before a Health Connect grant exists.
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (granted.isEmpty()) { val msg = "Grant Health Connect access first"; status.text = msg; prefs.edit().putString("status", msg).apply(); return@launch }
            beginSync(selectedDomain)
        }
    }

    private fun beginSync(selectedDomain: String) {
        status.text = "Starting sync…"
        prefs.edit().putString("status", "Starting sync…").apply()
        val diagnostics = DiagnosticLogger(applicationContext) { selectedDomain }
        diagnostics.record("ui_sync_button", "Sync button pressed")
        lifecycleScope.launch(Dispatchers.IO) { diagnostics.uploadPending() }
        try {
            val intent = Intent(this, HealthSyncService::class.java).putExtra("domain", selectedDomain)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            diagnostics.record("ui_start_service", "Could not start sync service", error)
            status.text = "Sync start failed: ${error.javaClass.simpleName}"
        }
    }

    private fun login() {
        val selectedDomain = cleanDomain()
        if (selectedDomain.isBlank()) { status.text = "Enter the gateway domain first"; return }
        var openServer: ServerSocket? = null
        lifecycleScope.launch(Dispatchers.IO) {
        try {
            val base = "https://$selectedDomain"
            val verifier = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val expected = UUID.randomUUID().toString()
            val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).also { it.soTimeout = 300_000; openServer = it }
            val redirect = "http://127.0.0.1:${server.localPort}/callback"
            val auth = Uri.parse("$base/auth/native/authorize").buildUpon().appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("redirect_uri", redirect).appendQueryParameter("state", expected).build()
            withContext(Dispatchers.Main) { status.text = "Waiting for Hermes sign-in…"; startActivity(Intent(Intent.ACTION_VIEW, auth)) }
            var socket: java.net.Socket
            var request: String
            do { socket = server.accept(); request = socket.getInputStream().bufferedReader().readLine() ?: ""; if (!request.contains("/callback?")) socket.close() } while (!request.contains("/callback?"))
            socket.getOutputStream().use { it.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n<html><script>window.close()</script><body>Sign-in complete. You may close this tab.</body></html>".toByteArray()) }
            socket.close(); server.close()
            val query = request.substringAfter("?").substringBefore(" ").split("&").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to URLDecoder.decode(p[1], "UTF-8") } }.toMap()
            require(query["state"] == expected && !query["code"].isNullOrBlank())
            val response = post("$base/auth/native/token", JSONObject().put("code", query["code"]).put("code_verifier", verifier).toString())
            saveTokens(response)
            withContext(Dispatchers.Main) { status.text = "Hermes: sign-in complete" }
        } catch (e: Exception) {
            runCatching { openServer?.close() }
            DiagnosticLogger(applicationContext) { selectedDomain }.record("login", "Sign-in failed", e)
            withContext(Dispatchers.Main) { status.text = "Sign-in error" }
        }
        }
    }
    private fun post(url: String, body: String): JSONObject { val c = URL(url).openConnection() as HttpURLConnection; c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) }; val text = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect(); return JSONObject(text) }
    private fun saveTokens(json: JSONObject) { prefs.edit().putString("access_token", json.getString("access_token")).putString("refresh_token", json.optString("refresh_token")).apply() }
}
