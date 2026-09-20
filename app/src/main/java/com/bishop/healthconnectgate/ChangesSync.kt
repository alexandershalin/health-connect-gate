package com.bishop.healthconnectgate

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ChangesTokenRequest
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

/*
 * Updated and deleted records (Health Connect Changes API).
 *
 * The time-window read only ever sees records as they are now inside a window it chooses to read: a record the source app corrected
 * later keeps its old value on the server, and a deleted record never disappears. With a changes token Health Connect reports exactly
 * what was added, corrected or deleted since the last sync, for any period. The token is an addition, never a requirement: whenever it
 * is missing, expired, unreadable or the server does not announce `accepts_changes`, the app does what it did before.
 */

/** One page of changes since a token. */
internal class ChangePage(val upserts: List<Record>, val deletedIds: List<String>, val nextToken: String, val hasMore: Boolean)

internal interface ChangeFeed {
    /** A token that marks "now" for these record types. */
    suspend fun newToken(types: Set<KClass<out Record>>): String

    /** The next page after [token], or null when the token is no longer usable (expired). */
    suspend fun read(token: String): ChangePage?
}

internal class HealthConnectChangeFeed(private val client: HealthConnectClient) : ChangeFeed {
    override suspend fun newToken(types: Set<KClass<out Record>>): String = client.getChangesToken(ChangesTokenRequest(recordTypes = types))

    override suspend fun read(token: String): ChangePage? {
        val response = client.getChanges(token)
        if (response.changesTokenExpired) return null
        return ChangePage(
            upserts = response.changes.filterIsInstance<UpsertionChange>().map { it.record },
            deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId },
            nextToken = response.nextChangesToken,
            hasMore = response.hasMore
        )
    }
}

/** Where the token and what it belongs to are kept. It lives in its own file: SyncEngine wipes `bridge_sync` when the server window moves. */
internal interface ChangesState {
    var token: String?
    /** Server + record types the token was issued for; a different value makes the token worthless. */
    var scope: String?
    /** When the whole current window was last read in full. */
    var fullReadAt: Instant?
}

internal class PrefsChangesState(context: Context) : ChangesState {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    override var token: String?
        get() = prefs.getString("token", null)
        set(value) { prefs.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply() }
    override var scope: String?
        get() = prefs.getString("scope", null)
        set(value) { prefs.edit().apply { if (value == null) remove("scope") else putString("scope", value) }.apply() }
    override var fullReadAt: Instant?
        get() = prefs.getLong("full_read_at", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
        set(value) { prefs.edit().apply { if (value == null) remove("full_read_at") else putLong("full_read_at", value.toEpochMilli()) }.apply() }

    companion object { const val PREFS_NAME = "gate_changes" }
}

/** Sends one page of changes to the server. Throws [ChangesUnavailableException] when the server cannot take them. */
internal fun interface ChangeUploader {
    suspend fun upload(upserts: List<Record>, deletedIds: List<String>)
}

/** The server announced `accepts_changes` but refused the request (a proxy that only forwards known paths, an older build...). */
internal class ChangesUnavailableException(message: String) : IllegalStateException(message)

internal sealed interface ChangesResult {
    /** Everything up to now is on the server; the open time window need not be read again. */
    class Applied(val upserts: Int, val deletions: Int) : ChangesResult

    /** No usable token. Read the open window in full as before, then call [ChangesSync.remember] (only after that succeeded). */
    class FullReadNeeded(val newToken: String?, val reason: String) : ChangesResult
}

internal class ChangesSync(
    private val feed: ChangeFeed,
    private val state: ChangesState,
    private val uploader: ChangeUploader,
    private val now: () -> Instant = { Instant.now() },
    private val log: (String) -> Unit = {}
) {
    /**
     * Drains the changes that happened since the stored token. The token moves forward only after a page reached the server, so
     * a failure (network, sign-in) repeats that page next time; a repeated page is harmless because the server applies versions by time.
     * Failures of Health Connect itself are not fatal: the caller falls back to a full read.
     */
    suspend fun apply(types: Set<KClass<out Record>>, scope: String): ChangesResult {
        val stored = state.token
        if (stored == null || state.scope != scope) return fullRead(types, "no token for this server and data selection")
        val last = state.fullReadAt
        if (last == null || Duration.between(last, now()) > FULL_READ_EVERY) return fullRead(types, "periodic full read")
        var token: String = stored
        var upserts = 0
        var deletions = 0
        while (true) {
            val page = try {
                feed.read(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Health Connect could not read changes: ${e.javaClass.simpleName}")
                null
            } ?: return fullRead(types, "the changes token is expired or unreadable")
            if (page.upserts.isNotEmpty() || page.deletedIds.isNotEmpty()) uploader.upload(page.upserts, page.deletedIds)
            state.token = page.nextToken // checkpoint: everything before this token is on the server
            token = page.nextToken
            upserts += page.upserts.size
            deletions += page.deletedIds.size
            if (!page.hasMore) return ChangesResult.Applied(upserts, deletions)
        }
    }

    /** Call after the full read finished successfully: from now on only changes are needed. */
    fun remember(result: ChangesResult.FullReadNeeded, scope: String) {
        val token = result.newToken ?: return
        state.token = token
        state.scope = scope
        state.fullReadAt = now()
    }

    private suspend fun fullRead(types: Set<KClass<out Record>>, reason: String): ChangesResult.FullReadNeeded {
        state.token = null // it is replaced by a fresh one once the full read has succeeded
        // The token is taken BEFORE the read, so anything that changes while the window is being read is reported next time.
        val fresh = try {
            feed.newToken(types)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Health Connect gave no changes token: ${e.javaClass.simpleName}")
            null
        }
        return ChangesResult.FullReadNeeded(fresh, reason)
    }

    companion object {
        /** Safety net against a token that silently lost changes (server restored from a backup, lost upload...). */
        val FULL_READ_EVERY: Duration = Duration.ofDays(7)

        /** Identifies what a token is good for: the server and the exact set of record types. */
        fun scopeOf(domain: String, types: Collection<KClass<out Record>>): String {
            val text = domain + "|" + types.map { it.java.name }.sorted().joinToString(",")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        }
    }
}

/** The wire format of `POST /api/health/changes` (see the API contract in the README). */
internal object ChangesEnvelope {
    const val MAX_DELETIONS = 500

    fun build(runId: String, format: Int?, upserts: List<JSONObject>, deletedIds: List<String>): JSONObject {
        val envelope = JSONObject().put("schema_version", 1).put("run_id", runId)
            .put("upserts", JSONArray(upserts)).put("deleted_ids", JSONArray(deletedIds))
        if (format != null) envelope.put("record_format", format)
        return envelope
    }

    /** Splits a page into requests the server accepts: all records first (at most [batchSize] each), then the deletions. */
    fun batches(runId: String, format: Int?, upserts: List<JSONObject>, deletedIds: List<String>, batchSize: Int): List<JSONObject> =
        upserts.chunked(batchSize.coerceIn(1, 500)).map { build(runId, format, it, emptyList()) } +
            deletedIds.chunked(MAX_DELETIONS).map { build(runId, format, emptyList(), it) }
}
