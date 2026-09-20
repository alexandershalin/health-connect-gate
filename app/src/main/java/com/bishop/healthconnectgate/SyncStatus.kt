package com.bishop.healthconnectgate

import android.content.Context
import android.content.SharedPreferences

internal enum class SyncState { IDLE, RUNNING, FAILED }

/** What the status screen shows. Times are epoch milliseconds, 0 = never. */
internal data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    val detail: String = "",
    val monthsDone: Int = 0,
    val monthsTotal: Int = 0,
    val recordsThisRun: Int = 0,
    val lastSuccessMs: Long = 0,
    val lastSuccessRecords: Int = 0,
    val lastError: String = "",
    val lastErrorMs: Long = 0
)

/** Persisted status of the synchronisation, written by the engine and observed by the UI. */
internal class SyncStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("gate_status", Context.MODE_PRIVATE)

    fun read(now: Long = System.currentTimeMillis()): SyncStatus {
        val started = prefs.getLong("started", 0)
        var state = runCatching { SyncState.valueOf(prefs.getString("state", "IDLE") ?: "IDLE") }.getOrDefault(SyncState.IDLE)
        // A run that was killed with the process must not show "running" forever.
        if (state == SyncState.RUNNING && now - started > STALE_RUN_MS) state = SyncState.IDLE
        return SyncStatus(
            state, prefs.getString("detail", "") ?: "", prefs.getInt("done", 0), prefs.getInt("total", 0), prefs.getInt("records", 0),
            prefs.getLong("ok_ms", 0), prefs.getInt("ok_records", 0), prefs.getString("err", "") ?: "", prefs.getLong("err_ms", 0))
    }

    fun begin() {
        prefs.edit().putString("state", SyncState.RUNNING.name).putLong("started", System.currentTimeMillis())
            .putString("detail", "").putInt("done", 0).putInt("total", 0).putInt("records", 0).apply()
    }

    fun progress(detail: String, done: Int, total: Int, records: Int) {
        prefs.edit().putString("state", SyncState.RUNNING.name).putString("detail", detail)
            .putInt("done", done).putInt("total", total).putInt("records", records).apply()
    }

    fun success(records: Int) {
        prefs.edit().putString("state", SyncState.IDLE.name).putString("detail", "").putLong("ok_ms", System.currentTimeMillis())
            .putInt("ok_records", records).putString("err", "").apply()
    }

    fun failure(text: String) {
        prefs.edit().putString("state", SyncState.FAILED.name).putString("detail", "").putString("err", text)
            .putLong("err_ms", System.currentTimeMillis()).apply()
    }

    fun clear() { prefs.edit().clear().apply() }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object { const val STALE_RUN_MS = 30 * 60 * 1000L }
}

/** Server answered with a status the app cannot work with. */
internal class HttpStatusException(val code: Int) : IllegalStateException("Hermes HTTP $code")

/** No Health Connect read permission for any selected data type. */
internal class NoPermissionException : IllegalStateException("No Health Connect read permissions granted for the selected data types")

/** Turns exceptions into sentences a person can act on. */
internal object ErrorText {
    fun describe(error: Throwable): String = when (error) {
        is AuthRequiredException -> "Your session has expired. Sign in again."
        is NoPermissionException -> "Grant Health Connect access for the selected data types first."
        is HttpStatusException -> when (error.code) {
            403 -> "The server refused access (HTTP 403). Sign in again."
            502, 503, 504 -> "The server is not available right now (HTTP ${error.code}). The app will try again."
            in 400..499 -> "The server rejected the request (HTTP ${error.code})."
            else -> "The server reported an error (HTTP ${error.code})."
        }
        is java.net.UnknownHostException -> "The server was not found. Check the domain and your connection."
        is java.net.SocketTimeoutException, is java.net.ConnectException -> "The server cannot be reached. Check your connection."
        is javax.net.ssl.SSLException -> "The secure connection to the server failed (certificate or protocol problem)."
        else -> "Unexpected error (${error.javaClass.simpleName})."
    }
}
