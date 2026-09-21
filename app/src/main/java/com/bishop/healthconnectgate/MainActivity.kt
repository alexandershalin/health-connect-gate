package com.bishop.healthconnectgate

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
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

class MainActivity : ComponentActivity() {
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val settings by lazy { Settings(this) }
    private val statusStore by lazy { SyncStatusStore(applicationContext) }

    private lateinit var summary: TextView
    private lateinit var banner: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var domain: EditText
    private lateinit var signInOut: Button
    private lateinit var syncNow: Button

    private var bannerText: String? = null
    private var wantedTypes = 0
    private var grantedTypes = 0
    private var anyPermission = false
    private var nextRunMs: Long? = null

    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { render() } }
    private val permissionsLauncher = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        DiagnosticLogger(applicationContext) { settings.domain }.record("permissions_result", "Health Connect granted ${granted.size} requested permissions")
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        installUncaughtExceptionCapture(applicationContext) { settings.domain }
        lifecycleScope.launch(Dispatchers.IO) { DiagnosticLogger(applicationContext) { settings.domain }.uploadPending() }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 22f })
        column.addView(TextView(this).apply { text = getString(R.string.version_line, BuildConfig.VERSION_NAME); textSize = 12f; setPadding(0, 0, 0, dp(12)) })
        banner = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        summary = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(12)) }
        domain = EditText(this).apply { hint = getString(R.string.hint_domain); setText(settings.domain) }
        signInOut = Button(this).apply { setOnClickListener { if (settings.isSignedIn) confirmSignOut() else login() } }
        syncNow = Button(this).apply { text = getString(R.string.sync_now); setOnClickListener { startSync() } }
        diagnosticsView = TextView(this).apply { textSize = 11f }
        column.addView(banner); column.addView(summary); column.addView(domain); column.addView(signInOut)
        column.addView(Button(this).apply { text = getString(R.string.choose_data); setOnClickListener { startActivity(Intent(this@MainActivity, DataTypesActivity::class.java)) } })
        column.addView(Button(this).apply { text = getString(R.string.grant_access); setOnClickListener { requestHealthPermissions() } })
        column.addView(syncNow)
        column.addView(TextView(this).apply { text = getString(R.string.diagnostics_heading); textSize = 12f; setPadding(0, dp(16), 0, dp(4)) })
        column.addView(diagnosticsView)
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)
        applyInsets(scroll, dp(16))

        if (settings.isSignedIn) SyncWorker.schedule(this) else SyncWorker.cancel(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkManager.getInstance(this@MainActivity).getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_NAME).collect { infos ->
                    nextRunMs = infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.nextScheduleTimeMillis
                        ?.takeIf { it > 0 && it != Long.MAX_VALUE }
                    render()
                }
            }
        }
        render()
    }

    override fun onStart() { super.onStart(); statusStore.register(statusListener) }
    override fun onStop() { statusStore.unregister(statusListener); super.onStop() }
    override fun onResume() { super.onResume(); refreshPermissions() }

    private fun cleanDomain(): String =
        domain.text.toString().trim().removePrefix("https://").removePrefix("http://").trimEnd('/').also { settings.domain = it }

    private fun showBanner(text: String?) { bannerText = text; render() }

    private fun render() {
        val st = statusStore.read()
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        val server = settings.domain
        lines += if (server.isBlank()) getString(R.string.status_server_unset)
                 else getString(R.string.status_server, server, getString(if (settings.isSignedIn) R.string.signed_in else R.string.signed_out))
        val model = StatusModelBuilder.build(st)
        lines += when (model.line) {
            SyncLine.RUNNING -> if (model.hasProgress) getString(R.string.status_sync_running, st.detail.ifBlank { "…" }, st.monthsDone, st.monthsTotal, st.recordsThisRun)
                                else getString(R.string.status_sync_running_short)
            SyncLine.INTERRUPTED -> if (model.hasProgress) getString(R.string.status_sync_interrupted, st.monthsDone, st.monthsTotal, st.recordsThisRun)
                                    else getString(R.string.status_sync_interrupted_short)
            SyncLine.FAILED -> getString(R.string.status_sync_failed)
            SyncLine.IDLE -> getString(R.string.status_sync_idle)
        }
        lines += if (st.lastSuccessMs > 0) getString(R.string.status_last_success, DateUtils.getRelativeTimeSpanString(st.lastSuccessMs, now, DateUtils.MINUTE_IN_MILLIS), st.lastSuccessRecords)
                 else getString(R.string.status_never)
        // The problem is shown only when the last run really ended badly, and with its age, so it cannot pass for the current state.
        model.errorText?.let { lines += getString(R.string.status_last_error_at, DateUtils.getRelativeTimeSpanString(model.errorAtMs, now, DateUtils.MINUTE_IN_MILLIS), it) }
        lines += nextRunMs?.let { getString(R.string.status_next_run, DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS)) }
            ?: getString(R.string.status_next_run_none)
        lines += getString(R.string.status_permissions, grantedTypes, wantedTypes)
        summary.text = lines.joinToString("\n")
        banner.text = bannerText.orEmpty()
        banner.visibility = if (bannerText.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        signInOut.text = getString(if (settings.isSignedIn) R.string.sign_out else R.string.sign_in)
        syncNow.isEnabled = model.syncEnabled
        diagnosticsView.text = DiagnosticLogger(applicationContext) { settings.domain }.recentText()
    }

    private fun refreshPermissions() {
        lifecycleScope.launch {
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            val wanted = DataSelection.typesFor(settings.selectedCategoryIds).map { HealthPermission.getReadPermission(it) }
            wantedTypes = wanted.size
            grantedTypes = wanted.count { it in granted }
            anyPermission = granted.isNotEmpty()
            render()
        }
    }

    private fun requestHealthPermissions() {
        val requested = DataSelection.permissionsFor(settings.selectedCategoryIds)
        DiagnosticLogger(applicationContext) { settings.domain }.record("permissions_request", "Requesting ${requested.size} Health Connect read permissions")
        permissionsLauncher.launch(requested)
    }

    private fun startSync() {
        val selectedDomain = cleanDomain()
        if (selectedDomain.isBlank()) return showBanner(getString(R.string.msg_enter_domain))
        if (!settings.isSignedIn) return showBanner(getString(R.string.msg_sign_in_first))
        lifecycleScope.launch {
            // A health-type foreground service cannot start (and crashes the app) before a Health Connect grant exists.
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            if (granted.isEmpty()) { showBanner(getString(R.string.msg_grant_first)); return@launch }
            beginSync(selectedDomain)
        }
    }

    private fun beginSync(selectedDomain: String) {
        showBanner(getString(R.string.msg_starting_sync))
        val diagnostics = DiagnosticLogger(applicationContext) { selectedDomain }
        diagnostics.record("ui_sync_button", "Sync button pressed")
        lifecycleScope.launch(Dispatchers.IO) { diagnostics.uploadPending() }
        try {
            val intent = Intent(this, HealthSyncService::class.java).putExtra("domain", selectedDomain)
            startForegroundService(intent)
            showBanner(null)
        } catch (error: Throwable) {
            diagnostics.record("ui_start_service", "Could not start sync service", error)
            showBanner(getString(R.string.msg_sync_start_failed, error.javaClass.simpleName))
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.sign_out_title)
            .setMessage(R.string.sign_out_message)
            .setPositiveButton(R.string.sign_out) { _, _ -> signOut() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun signOut() {
        settings.signOut()
        SyncWorker.cancel(this)
        statusStore.clear()
        showBanner(getString(R.string.msg_signed_out))
    }

    private fun login() {
        val selectedDomain = cleanDomain()
        if (selectedDomain.isBlank()) return showBanner(getString(R.string.msg_enter_domain))
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
            withContext(Dispatchers.Main) { showBanner(getString(R.string.msg_waiting_signin)); startActivity(Intent(Intent.ACTION_VIEW, auth)) }
            var socket: java.net.Socket
            var request: String
            do { socket = server.accept(); request = socket.getInputStream().bufferedReader().readLine() ?: ""; if (!request.contains("/callback?")) socket.close() } while (!request.contains("/callback?"))
            socket.getOutputStream().use { it.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n<html><script>window.close()</script><body>Sign-in complete. You may close this tab.</body></html>".toByteArray()) }
            socket.close(); server.close()
            val query = request.substringAfter("?").substringBefore(" ").split("&").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to URLDecoder.decode(p[1], "UTF-8") } }.toMap()
            require(query["state"] == expected && !query["code"].isNullOrBlank())
            val response = post("$base/auth/native/token", JSONObject().put("code", query["code"]).put("code_verifier", verifier).toString())
            settings.saveTokens(response.getString("access_token"), response.optString("refresh_token"))
            withContext(Dispatchers.Main) { SyncWorker.schedule(this@MainActivity); showBanner(getString(R.string.msg_signed_in)) }
        } catch (e: Exception) {
            runCatching { openServer?.close() }
            DiagnosticLogger(applicationContext) { selectedDomain }.record("login", "Sign-in failed", e)
            withContext(Dispatchers.Main) { showBanner(getString(R.string.msg_signin_error)) }
        }
        }
    }

    private fun post(url: String, body: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(text)
    }
}
