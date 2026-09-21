package com.bishop.healthconnectgate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/** Durable, privacy-preserving diagnostics. Health payloads and credentials never enter this file. */
internal class DiagnosticLogger(private val context: Context, private val domainProvider: () -> String) {
    private val file = File(context.filesDir, "diagnostics.jsonl")
    private val prefs = context.getSharedPreferences("bridge_sync", Context.MODE_PRIVATE)

    fun record(phase: String, message: String, error: Throwable? = null) {
        val event = JSONObject()
            .put("event_id", UUID.randomUUID().toString())
            .put("timestamp", Instant.now().toString())
            .put("phase", safe(phase))
            .put("message", safe(message))
        if (error != null) {
            event.put("exception_type", error.javaClass.name)
            event.put("stack_trace", safe(error.stackTraceToString()))
        }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(event.toString() + "\n", Charsets.UTF_8)
            }
        }
    }

    fun recentText(): String = synchronized(lock) {
        if (!file.exists()) return@synchronized "No local diagnostic events yet"
        file.readLines(Charsets.UTF_8).takeLast(12).joinToString("\n") { line ->
            runCatching {
                val event = JSONObject(line)
                "${event.optString("timestamp")}  ${event.optString("phase")}: ${event.optString("message")}" 
            }.getOrDefault("Malformed local diagnostic event")
        }
    }

    /** Uploads the outbox. Only one upload runs per process: concurrent callers used to send the same events several times. */
    suspend fun uploadPending() {
        if (!uploadGate.tryLock()) return
        try { uploadPendingLocked() } finally { uploadGate.unlock() }
    }

    /** Sends the outbox in batches of [MAX_EVENTS] until it is empty (at most [MAX_ROUNDS] batches per call); stops at the first failure. */
    private suspend fun uploadPendingLocked() {
        repeat(MAX_ROUNDS) { if (!uploadOnce()) return }
    }

    /** True when a batch was sent and more may follow. */
    private suspend fun uploadOnce(): Boolean = withContext(Dispatchers.IO) {
        val token = prefs.getString("access_token", null)?.takeIf { it.isNotBlank() } ?: return@withContext false
        val pending: List<String> = synchronized(lock) {
            if (!file.exists()) return@synchronized emptyList()
            file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.take(MAX_EVENTS)
        }
        if (pending.isEmpty()) return@withContext false
        val events = JSONArray()
        pending.forEach { line -> runCatching { events.put(JSONObject(line)) } }
        if (events.length() == 0) return@withContext false
        try {
            val connection = URL("https://${domainProvider()}/api/health/diagnostics").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(JSONObject().put("schema_version", 1).put("events", events).toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            connection.inputStream?.close()
            connection.disconnect()
            if (code !in 200..299) return@withContext false
            synchronized(lock) {
                if (!file.exists()) return@synchronized
                val remaining = file.readLines(Charsets.UTF_8).drop(pending.size)
                val temp = File(file.parentFile, "diagnostics.jsonl.tmp")
                temp.writeText(remaining.joinToString("\n", postfix = if (remaining.isNotEmpty()) "\n" else ""), Charsets.UTF_8)
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
            pending.size >= MAX_EVENTS
        } catch (_: Exception) {
            // Keep the outbox for a later app start/sync; never create a recursive diagnostic event.
            false
        }
    }

    private fun safe(value: String): String = value
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
        .replace(Regex("(?i)(access_token|refresh_token|token|authorization)\\s*[:=]\\s*[^,;\\s}]+"), "$1=[REDACTED]")
        .take(MAX_FIELD_LENGTH)

    companion object {
        private const val MAX_EVENTS = 50; private const val MAX_ROUNDS = 10; private const val MAX_FIELD_LENGTH = 12000
        // Instances are created all over the app; the file lock and the upload gate must be shared by all of them.
        private val lock = Any()
        private val uploadGate = kotlinx.coroutines.sync.Mutex()
    }
}

private val captureInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

internal fun installUncaughtExceptionCapture(context: Context, domainProvider: () -> String) {
    if (!captureInstalled.compareAndSet(false, true)) return
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        DiagnosticLogger(context, domainProvider).record("uncaught_exception", "Uncaught exception on ${thread.name}", error)
        previous?.uncaughtException(thread, error)
    }
}
