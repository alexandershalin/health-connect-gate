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
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.reflect.KClass

internal data class SyncConfig(val historyStart: Instant, val historyEnd: Instant?, val chunkMonths: Int, val batchSize: Int) {
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
    private val lock = SyncLock(context)
    private val diagnostics = DiagnosticLogger(context, domainProvider)

    suspend fun run(): String = withContext(Dispatchers.IO) {
        diagnostics.uploadPending()
        if (!lock.tryAcquire()) return@withContext "Another synchronization is already running"
        var phase = "acquire_lock"
        try {
            phase = "fetch_config"
            val config = fetchConfig()
            val end = minOf(config.historyEnd ?: Instant.now(), Instant.now())
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
            if (grantedPermissions.isEmpty()) throw IllegalStateException("No Health Connect read permissions granted")
            // The server manifest is the source of truth. Never let a stale local cursor skip a server-assigned range.
            var month = YearMonth.from(start.atZone(ZoneOffset.UTC))
            val lastMonth = YearMonth.from(end.minusNanos(1).atZone(ZoneOffset.UTC))
            while (!month.isAfter(lastMonth)) {
                val chunkStart = maxOf(start, month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                val chunkEnd = minOf(end, month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                val chunkId = "${month}|${chunkStart}|${chunkEnd}"
                if (chunkId in serverCompletedChunks || prefs.getString("completed:$chunkId", null) == "1") {
                    month = month.plusMonths(config.chunkMonths.toLong()); continue
                }
                message("Reading ${month}")
                phase = "read_records:${month}"
                var total = 0
                for (type in RecordCatalog.types) {
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
                prefs.edit().putString("completed:$chunkId", "1").putString("next_month", month.plusMonths(config.chunkMonths.toLong()).toString()).apply()
                month = month.plusMonths(config.chunkMonths.toLong())
            }
            message("Completed")
            prefs.edit().remove("run_id").apply()
            "Completed"
        } catch (error: Throwable) {
            diagnostics.record(phase, "SyncEngine failure", error)
            throw error
        } finally { lock.release() }
    }

    private suspend fun fetchConfig(): SyncConfig {
        val json = request("GET", "/api/health/config", null, null)
        val start = runCatching { Instant.parse(json.getString("history_start")) }.getOrElse { throw IllegalStateException("Invalid server history_start") }
        val end = json.optString("history_end", "").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrElse { throw IllegalStateException("Invalid server history_end") } }
        if (end != null && !end.isAfter(start)) throw IllegalStateException("Server history_end must be after history_start")
        return SyncConfig(start, end, json.optInt("chunk_months", 1).coerceIn(1, 12), json.optInt("batch_size", 250).coerceIn(25, 500))
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
        records.forEach { array.put(JSONObject(RecordCatalog.recordJson(it))) }
        val body = JSONObject().put("schema_version", 1).put("run_id", runId).put("chunk_id", chunkId)
            .put("chunk_start", start.toString()).put("chunk_end", end.toString()).put("records", array).toString()
        var response = request("POST", "/api/health/sync", body, prefs.getString("access_token", null))
        if (response.optBoolean("unauthorized", false)) {
            if (!refresh()) throw AuthRequiredException("Hermes session expired; sign in again")
            response = request("POST", "/api/health/sync", body, prefs.getString("access_token", null))
        }
        if (!response.optBoolean("ok", true) && response.optInt("accepted", -1) < 0) throw IllegalStateException("Sync rejected")
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

    private fun request(method: String, path: String, body: String?, token: String?): JSONObject {
        val c = (URL("https://${domainProvider()}$path").openConnection() as HttpURLConnection)
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        c.disconnect()
        if (code == 401) return JSONObject().put("unauthorized", true)
        if (code !in 200..299) throw IllegalStateException("Hermes HTTP $code")
        return JSONObject(text)
    }
}

internal class SyncLock(context: Context) {
    private val file = java.io.File(context.filesDir, "health-sync.lock")
    private var channel: java.nio.channels.FileChannel? = null
    private var fileLock: java.nio.channels.FileLock? = null
    fun tryAcquire(): Boolean = runCatching { channel = java.io.RandomAccessFile(file, "rw").channel; fileLock = channel!!.tryLock(); fileLock != null }.getOrElse { release(); false }
    fun release() { runCatching { fileLock?.release() }; fileLock = null; runCatching { channel?.close() }; channel = null }
}

internal object RecordCatalog {
    val types: List<KClass<out Record>> = listOf(
        androidx.health.connect.client.records.ActiveCaloriesBurnedRecord::class, androidx.health.connect.client.records.BasalBodyTemperatureRecord::class,
        androidx.health.connect.client.records.BasalMetabolicRateRecord::class, androidx.health.connect.client.records.BloodGlucoseRecord::class,
        androidx.health.connect.client.records.BloodPressureRecord::class, androidx.health.connect.client.records.BodyFatRecord::class,
        androidx.health.connect.client.records.BodyTemperatureRecord::class, androidx.health.connect.client.records.BodyWaterMassRecord::class,
        androidx.health.connect.client.records.BoneMassRecord::class, androidx.health.connect.client.records.CervicalMucusRecord::class,
        androidx.health.connect.client.records.CyclingPedalingCadenceRecord::class, androidx.health.connect.client.records.DistanceRecord::class,
        androidx.health.connect.client.records.ElevationGainedRecord::class, androidx.health.connect.client.records.ExerciseSessionRecord::class,
        androidx.health.connect.client.records.FloorsClimbedRecord::class, androidx.health.connect.client.records.HeartRateRecord::class,
        androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord::class, androidx.health.connect.client.records.HeightRecord::class,
        androidx.health.connect.client.records.HydrationRecord::class, androidx.health.connect.client.records.IntermenstrualBleedingRecord::class,
        androidx.health.connect.client.records.LeanBodyMassRecord::class, androidx.health.connect.client.records.MenstruationFlowRecord::class,
        androidx.health.connect.client.records.MenstruationPeriodRecord::class, androidx.health.connect.client.records.MindfulnessSessionRecord::class,
        androidx.health.connect.client.records.NutritionRecord::class, androidx.health.connect.client.records.OvulationTestRecord::class,
        androidx.health.connect.client.records.OxygenSaturationRecord::class, androidx.health.connect.client.records.PlannedExerciseSessionRecord::class,
        androidx.health.connect.client.records.PowerRecord::class, androidx.health.connect.client.records.RespiratoryRateRecord::class,
        androidx.health.connect.client.records.RestingHeartRateRecord::class, androidx.health.connect.client.records.SexualActivityRecord::class,
        androidx.health.connect.client.records.SkinTemperatureRecord::class, androidx.health.connect.client.records.SleepSessionRecord::class,
        androidx.health.connect.client.records.SpeedRecord::class, androidx.health.connect.client.records.StepsCadenceRecord::class,
        androidx.health.connect.client.records.StepsRecord::class, androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class,
        androidx.health.connect.client.records.Vo2MaxRecord::class, androidx.health.connect.client.records.WeightRecord::class,
        androidx.health.connect.client.records.WheelchairPushesRecord::class)
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
