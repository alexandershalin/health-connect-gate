package com.bishop.healthconnectgate

import android.content.Context

/**
 * Typed access to the app's preferences. `bridge_sync` is the historical file (server domain, tokens, sync state); SyncEngine resets
 * parts of it whenever the server's window changes, so choices that must survive live in `gate_settings`.
 */
internal class Settings(context: Context) {
    private val legacy = context.getSharedPreferences("bridge_sync", Context.MODE_PRIVATE)
    private val own = context.getSharedPreferences("gate_settings", Context.MODE_PRIVATE)
    private val changes = context.getSharedPreferences(PrefsChangesState.PREFS_NAME, Context.MODE_PRIVATE)
    private val resume = context.getSharedPreferences(PrefsResumeStore.PREFS_NAME, Context.MODE_PRIVATE)

    var domain: String
        get() = legacy.getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN) ?: BuildConfig.DEFAULT_GATEWAY_DOMAIN
        set(value) { legacy.edit().putString("gateway_domain", value).apply() }

    val isSignedIn: Boolean
        get() = !legacy.getString("access_token", null).isNullOrBlank() || !legacy.getString("refresh_token", null).isNullOrBlank()

    fun saveTokens(access: String, refresh: String?) {
        legacy.edit().putString("access_token", access).putString("refresh_token", refresh.orEmpty()).apply()
    }

    /** Forgets the session and the resumable sync state; keeps the server domain and the data-type choice. */
    fun signOut() {
        val editor = legacy.edit().remove("access_token").remove("refresh_token").remove("run_id")
            .remove("config_fingerprint").remove("next_month")
        legacy.all.keys.filter { it.startsWith("completed:") }.forEach { editor.remove(it) }
        editor.apply()
        changes.edit().clear().apply() // the changes token belongs to a server; the next sign-in starts with a full read
        resume.edit().clear().apply() // so does the progress inside a half-read month
    }

    /** Ids of the chosen [DataCategory]s; null until the user chooses (then the defaults apply). */
    var selectedCategoryIds: Set<String>?
        get() = own.getStringSet("selected_categories", null)
        set(value) {
            own.edit().apply { if (value == null) remove("selected_categories") else putStringSet("selected_categories", value) }.apply()
        }
}
