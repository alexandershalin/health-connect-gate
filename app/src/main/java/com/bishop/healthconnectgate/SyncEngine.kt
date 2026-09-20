package com.bishop.healthconnectgate

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.reflect.KClass

internal data class SyncConfig(
    val historyStart: Instant, val historyEnd: Instant?, val chunkMonths: Int, val batchSize: Int,
    val acceptsGzip: Boolean = false, val recordFormat: Int = 1, val acceptsChanges: Boolean = false
) {
    val fingerprint: String get() = "$historyStart|${historyEnd ?: "now"}|$chunkMonths|$batchSize"
}
/** Thrown when the stored session is unusable; retrying without a new sign-in can never succeed. */
internal class AuthRequiredException(message: String) : IllegalStateException(message)

internal data class SyncProgress(val runId: String, val chunkId: String, val month: String, val state: String)

internal class SyncEngine(
    private val context: Context,
    private val client: HealthConnectClient,
    private val domainProvider: () -> String,
    private val message: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("bridge_sync", Context.MODE_PRIVATE)
    private val settings = Settings(context)
    private val status = SyncStatusStore(context)
    private var acceptsGzip = false
    private var recordFormat = 1
    private val lock = SyncLock(context)
    private val diagnostics = DiagnosticLogger(context, domainProvider)

    suspend fun run(): String = withContext(Dispatchers.IO) {
        diagnostics.uploadPending()
        if (!lock.tryAcquire()) return@withContext "Another synchronization is already running"
        var phase = "acquire_lock"
        try {
            status.begin()
            if (!settings.isSignedIn) throw AuthRequiredException("Not signed in")
            phase = "fetch_config"
            val config = fetchConfig()
            acceptsGzip = config.acceptsGzip
            recordFormat = config.recordFormat
            val runStart = Instant.now()
            val end = minOf(config.historyEnd ?: runStart, runStart)
            val start = minOf(config.historyStart, end)
            if (prefs.getString("config_fingerprint", null) != config.fingerprint) {
                val access = prefs.getString("access_token", null)
                val refresh = prefs.getString("refresh_token", null)
                prefs.edit().clear().putString("gateway_domain", domainProvider()).putString("config_fingerprint", config.fingerprint)
                    .putString("access_token", access).putString("refresh_token", refresh).apply()
            }
            val runId = prefs.getString("run_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("run_id", it).apply()
            }
            phase = "fetch_server_status"
            val serverCompletedChunks = fetchServerCompletedChunks()
            message("Preparing ${start} to ${end}")
            val grantedPermissions = client.permissionController.getGrantedPermissions()
            diagnostics.record("permissions", "Health Connect reports ${grantedPermissions.size} granted permissions")
            val types = DataSelection.typesFor(settings.selectedCategoryIds)
            if (types.none { HealthPermission.getReadPermission(it) in grantedPermissions }) throw NoPermissionException()
            // Updated and deleted records: when the server takes them, only the changes since the last sync are sent and the open window
            // is not read again. Anything unusual (no token, expired token, a server that refuses) falls back to the full read below.
            val grantedTypes = types.filter { HealthPermission.getReadPermission(it) in grantedPermissions }.toSet()
            val changesScope = ChangesSync.scopeOf(domainProvider(), grantedTypes)
            val changes = if (config.acceptsChanges) ChangesSync(HealthConnectChangeFeed(client), PrefsChangesState(context),
                { upserts, deleted -> sendChanges(runId, upserts, deleted, config.batchSize) }, log = { diagnostics.record("changes", it) }) else null
            var changesResult: ChangesResult? = null
            if (changes != null) {
                phase = "changes"
                changesResult = try {
                    changes.apply(grantedTypes, changesScope)
                } catch (e: ChangesUnavailableException) {
                    diagnostics.record("changes", "Server refused updates and deletions, reading the window in full: ${e.message}")
                    null
                }
                (changesResult as? ChangesResult.Applied)?.takeIf { it.upserts > 0 || it.deletions > 0 }?.let {
                    message("Changes applied: ${it.upserts} new or updated, ${it.deletions} deleted")
                    diagnostics.record("changes", "Applied ${it.upserts} new or updated and ${it.deletions} deleted records")
                }
                (changesResult as? ChangesResult.FullReadNeeded)?.let { diagnostics.record("changes", "Full read: ${it.reason}") }
            }
            val changesApplied = changesResult is ChangesResult.Applied
            // The server manifest is the source of truth. Never let a stale local cursor skip a server-assigned range.
            var month = YearMonth.from(start.atZone(ZoneOffset.UTC))
            val lastMonth = YearMonth.from(end.minusNanos(1).atZone(ZoneOffset.UTC))
            var totalPeriods = 0
            run { var m = month; while (!m.isAfter(lastMonth)) { totalPeriods++; m = m.plusMonths(config.chunkMonths.toLong()) } }
            var periodsDone = 0
            var runRecords = 0
            while (!month.isAfter(lastMonth)) {
                val chunkStart = maxOf(start, month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                val chunkEnd = minOf(end, month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                // A window that ends "now" changes with every run. It gets the stable id "<month>|<start>|open" (so repeated runs do not
                // pile up new server entries) and is never skipped: new records may have appeared since it was last completed.
                val open = Duration.between(chunkEnd, runStart) < Duration.ofHours(24)
                val chunkId = if (open) "${month}|${chunkStart}|open" else "${month}|${chunkStart}|${chunkEnd}"
                if (!open && (chunkId in serverCompletedChunks || prefs.getString("completed:$chunkId", null) == "1")) {
                    periodsDone++
                    month = month.plusMonths(config.chunkMonths.toLong()); continue
                }
                if (open && changesApplied) { // the changes token already delivered everything that happened in this window
                    periodsDone++
                    month = month.plusMonths(config.chunkMonths.toLong()); continue
                }
                message("Reading ${month}")
                status.progress("$month", periodsDone, totalPeriods, runRecords)
                phase = "read_records:${month}"
                var total = 0
                for (type in types) {
                    val permission = HealthPermission.getReadPermission(type)
                    if (permission !in grantedPermissions) continue
                    readPages(type, TimeRangeFilter.between(chunkStart, chunkEnd), config.batchSize) { records ->
                        total += records.size
                        message("Sending ${month}: batch ${records.size} (read $total)")
                        sendBatch(runId, chunkId, chunkStart, chunkEnd, records)
                    }
                }
                completeChunk(runId, chunkId, chunkStart, chunkEnd)
                message("${month} confirmed by server: $total records read")
                runRecords += total
                periodsDone++
                status.progress("$month", periodsDone, totalPeriods, runRecords)
                prefs.edit().putString("completed:$chunkId", "1").putString("next_month", month.plusMonths(config.chunkMonths.toLong()).toString()).apply()
                month = month.plusMonths(config.chunkMonths.toLong())
            }
            (changesResult as? ChangesResult.FullReadNeeded)?.let { changes?.remember(it, changesScope) }
            message("Completed")
            status.success(runRecords)
            prefs.edit().remove("run_id").apply()
            "Completed"
        } catch (error: Throwable) {
            diagnostics.record(phase, "SyncEngine failure", error)
            if (error !is kotlinx.coroutines.CancellationException) status.failure(ErrorText.describe(error))
            throw error
        } finally { lock.release() }
    }

    private suspend fun fetchConfig(): SyncConfig {
        val json = request("GET", "/api/health/config", null, null)
        val start = runCatching { Instant.parse(json.getString("history_start")) }.getOrElse { throw IllegalStateException("Invalid server history_start") }
        val end = json.optString("history_end", "").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrElse { throw IllegalStateException("Invalid server history_end") } }
        if (end != null && !end.isAfter(start)) throw IllegalStateException("Server history_end must be after history_start")
        return SyncConfig(
            start, end, json.optInt("chunk_months", 1).coerceIn(1, 12), json.optInt("batch_size", 250).coerceIn(25, 500),
            // Older servers do not send these: then the app keeps the plain, full format they understand.
            acceptsGzip = json.optBoolean("accepts_gzip", false), recordFormat = json.optInt("record_format", 1),
            acceptsChanges = json.optBoolean("accepts_changes", false))
    }

    private suspend fun fetchServerCompletedChunks(): Set<String> {
        val token = prefs.getString("access_token", null) ?: return emptySet()
        var json = request("GET", "/api/health/sync/status", null, token)
        if (json.optBoolean("unauthorized", false)) {
            if (!refresh()) return emptySet()
            json = request("GET", "/api/health/sync/status", null, prefs.getString("access_token", null))
        }
        val chunks = json.optJSONObject("chunks") ?: return emptySet()
        return chunks.keys().asSequence().filter { chunks.optJSONObject(it)?.optBoolean("complete", false) == true }.toSet()
    }

    @Suppress("UNCHECKED_CAST", "EXPERIMENTAL_API_USAGE")
    private suspend fun readPages(type: KClass<out Record>, filter: TimeRangeFilter, batchSize: Int, consume: suspend (List<Record>) -> Unit) {
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type as KClass<Record>, filter, emptySet(), false, batchSize, token))
            if (page.records.isNotEmpty()) consume(page.records)
            token = page.pageToken
        } while (!token.isNullOrBlank())
    }

    private suspend fun sendBatch(runId: String, chunkId: String, start: Instant, end: Instant, records: List<Record>) {
        val array = JSONArray()
        val explicit = recordFormat >= RecordJson.FORMAT
        records.forEach { array.put(encodeRecord(it, explicit)) }
        val envelope = JSONObject().put("schema_version", 1).put("run_id", runId).put("chunk_id", chunkId)
            .put("chunk_start", start.toString()).put("chunk_end", end.toString()).put("records", array)
        if (explicit) envelope.put("record_format", RecordJson.FORMAT)
        val body = envelope.toString()
        var response = request("POST", "/api/health/sync", body, prefs.getString("access_token", null), compress = acceptsGzip)
        if (response.optBoolean("unauthorized", false)) {
            if (!refresh()) throw AuthRequiredException("Hermes session expired; sign in again")
            response = request("POST", "/api/health/sync", body, prefs.getString("access_token", null), compress = acceptsGzip)
        }
        if (!response.optBoolean("ok", true) && response.optInt("accepted", -1) < 0) throw IllegalStateException("Sync rejected")
    }

    private fun encodeRecord(record: Record, explicit: Boolean): JSONObject =
        (if (explicit) RecordJson.encode(record) else null) ?: JSONObject(RecordCatalog.recordJson(record))

    /** One page of Health Connect changes: new and corrected records, then the ids of deleted ones. */
    private suspend fun sendChanges(runId: String, upserts: List<Record>, deletedIds: List<String>, batchSize: Int) {
        val explicit = recordFormat >= RecordJson.FORMAT
        val docs = upserts.map { encodeRecord(it, explicit) }
        for (envelope in ChangesEnvelope.batches(runId, if (explicit) RecordJson.FORMAT else null, docs, deletedIds, batchSize)) {
            val body = envelope.toString()
            var response = changesRequest(body)
            if (response.optBoolean("unauthorized", false) && refresh()) response = changesRequest(body)
            // Updates are optional: a 401 here (a gateway that answers unknown paths that way) must not look like an expired session and
            // force a new sign-in. A really expired session surfaces on the next request of the full read.
            if (response.optBoolean("unauthorized", false)) throw ChangesUnavailableException("HTTP 401")
            if (!response.optBoolean("ok", false)) throw IllegalStateException("Changes rejected")
        }
    }

    private fun changesRequest(body: String): JSONObject = try {
        request("POST", "/api/health/changes", body, prefs.getString("access_token", null), compress = acceptsGzip)
    } catch (e: HttpStatusException) {
        // 404/405/501: a proxy that forwards only known paths, or a server that announced more than it implements. Not fatal.
        if (e.code == 404 || e.code == 405 || e.code == 501) throw ChangesUnavailableException("HTTP ${e.code}") else throw e
    }

    private suspend fun completeChunk(runId: String, chunkId: String, start: Instant, end: Instant) {
        val body = JSONObject().put("schema_version", 1).put("run_id", runId).put("chunk_id", chunkId)
            .put("chunk_start", start.toString()).put("chunk_end", end.toString()).put("complete", true).put("records", JSONArray()).toString()
        var response = request("POST", "/api/health/sync", body, prefs.getString("access_token", null))
        if (response.optBoolean("unauthorized", false)) {
            if (!refresh()) throw AuthRequiredException("Hermes session expired; sign in again")
            response = request("POST", "/api/health/sync", body, prefs.getString("access_token", null))
        }
        if (!response.optBoolean("ok", false) || response.optString("chunk_id") != chunkId) throw IllegalStateException("Server did not confirm range")
    }

    private fun refresh(): Boolean {
        val refresh = prefs.getString("refresh_token", null) ?: return false
        return runCatching {
            val json = request("POST", "/auth/native/refresh", JSONObject().put("refresh_token", refresh).toString(), null)
            val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@runCatching false
            prefs.edit().putString("access_token", access).putString("refresh_token", json.optString("refresh_token", refresh)).apply(); true
        }.getOrDefault(false)
    }

    private fun request(method: String, path: String, body: String?, token: String?, compress: Boolean = false): JSONObject {
        val c = (URL("https://${domainProvider()}$path").openConnection() as HttpURLConnection)
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            val bytes = body.toByteArray()
            if (compress && bytes.size > GZIP_MIN_BYTES) { c.setRequestProperty("Content-Encoding", "gzip"); c.outputStream.use { it.write(gzip(bytes)) } }
            else c.outputStream.use { it.write(bytes) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        c.disconnect()
        if (code == 401) return JSONObject().put("unauthorized", true)
        if (code !in 200..299) throw HttpStatusException(code)
        return JSONObject(text)
    }
}

private const val GZIP_MIN_BYTES = 1024

internal fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(bytes.size / 8 + 64)
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

internal class SyncLock(context: Context) {
    private val file = java.io.File(context.filesDir, "health-sync.lock")
    private var channel: java.nio.channels.FileChannel? = null
    private var fileLock: java.nio.channels.FileLock? = null
    fun tryAcquire(): Boolean = runCatching { channel = java.io.RandomAccessFile(file, "rw").channel; fileLock = channel!!.tryLock(); fileLock != null }.getOrElse { release(); false }
    fun release() { runCatching { fileLock?.release() }; fileLock = null; runCatching { channel?.close() }; channel = null }
}

internal object RecordCatalog {
    val types: List<KClass<out Record>> = DataCategory.entries.flatMap { it.types }
    fun recordJson(record: Record): String = ReflectiveJson.encodeRecord(record)
}

private object ReflectiveJson {
    fun encodeRecord(record: Record): String {
        val data = linkedMapOf<String, Any?>("id" to record.javaClass.methods.firstOrNull { it.name == "getMetadata" }?.invoke(record)?.let { m -> m.javaClass.methods.firstOrNull { it.name == "getId" }?.invoke(m) }, "kind" to record.javaClass.simpleName.removeSuffix("Record"), "data" to record)
        return encode(data, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))
    }
    private fun encode(v: Any?, active: MutableSet<Any>): String = when (v) {
        null -> "null"; is String, is Char, is Enum<*> -> JSONObject.quote(v.toString()); is Number, is Boolean -> v.toString()
        is java.time.temporal.TemporalAccessor -> JSONObject.quote(v.toString()); is Iterable<*> -> v.joinToString(separator = ",", prefix = "[", postfix = "]") { encode(it, active) }
        is Map<*, *> -> v.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { "${JSONObject.quote(it.key.toString())}:${encode(it.value, active)}" }
        else -> if (!active.add(v)) JSONObject.quote(v.toString()) else try { v.javaClass.methods.filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) && it.name != "getClass" }.distinctBy { it.name }.sortedBy { it.name }.mapNotNull { m -> runCatching { (if (m.name.startsWith("get")) m.name.substring(3) else m.name.substring(2)).replaceFirstChar { it.lowercase() } to m.invoke(v) }.getOrNull() }.joinToString(separator = ",", prefix = "{", postfix = "}") { "${JSONObject.quote(it.first)}:${encode(it.second, active)}" } } finally { active.remove(v) }
    }
}
