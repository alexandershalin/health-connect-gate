package com.bishop.healthconnectgate

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

/*
 * Reading one chunk (a month) and resuming it after an interruption.
 *
 * The chunk is read type by type, and inside a type from the NEWEST record to the oldest. After the server confirmed a batch the importer saves
 * "everything not older than X of this type is on the server". After a dropped connection the next run skips the finished types and continues a
 * half-read type just below X (with one second of overlap, duplicates are harmless), instead of sending the whole month again.
 * The checkpoints are dropped when the chunk is confirmed complete, when they are older than [TTL_MS] (a server restored from a backup must
 * not be trusted for long) and on sign-out.
 */

internal class ChunkInfo(val id: String, val start: Instant, val end: Instant)

internal class SourcePage(val records: List<Record>, val nextToken: String?)

/** Where records come from, newest first inside a type. */
internal interface RecordSource {
    suspend fun read(type: KClass<out Record>, from: Instant, to: Instant, pageSize: Int, pageToken: String?): SourcePage
}

/** Sends a batch and returns only after the server confirmed it. */
internal fun interface BatchSink {
    suspend fun send(chunk: ChunkInfo, records: List<Record>)
}

internal data class TypeProgress(val done: Boolean, val oldestStartMs: Long?, val sent: Int, val savedAtMs: Long)

internal interface ResumeStore {
    fun get(chunkId: String, typeKey: String): TypeProgress?
    fun put(chunkId: String, typeKey: String, progress: TypeProgress)
    fun clear(chunkId: String)
    fun clearAll()
}

private val startGetters = java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Method>>()

/** When the record starts: `startTime` of an interval record, `time` of an instantaneous one (the common interfaces are internal to Health Connect). */
internal fun recordStart(record: Record): Instant? {
    val getter = startGetters.getOrPut(record.javaClass) {
        java.util.Optional.ofNullable(
            runCatching { record.javaClass.getMethod("getStartTime") }.getOrNull() ?: runCatching { record.javaClass.getMethod("getTime") }.getOrNull())
    }
    return if (getter.isPresent) runCatching { getter.get().invoke(record) as? Instant }.getOrNull() else null
}

internal class ChunkImporter(
    private val source: RecordSource,
    private val sink: BatchSink,
    private val store: ResumeStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    /** Reads and sends the chunk. Returns how many records were sent and confirmed for it, earlier attempts included (the resume overlap counts twice). Throws on the first failure. */
    suspend fun import(
        chunk: ChunkInfo,
        types: List<KClass<out Record>>,
        pageSize: Int,
        onBatch: (type: String, batchSize: Int, confirmed: Int) -> Unit = { _, _, _ -> }
    ): Int {
        var confirmed = 0
        for (type in types) {
            val key = type.java.name
            val saved = store.get(chunk.id, key)?.takeIf { now() - it.savedAtMs <= TTL_MS }
            if (saved?.done == true) { confirmed += saved.sent; continue }
            var sent = saved?.sent ?: 0
            confirmed += sent // what an earlier attempt already got confirmed for this type
            var oldest: Long? = saved?.oldestStartMs
            var ordered = true
            var upper = chunk.end
            if (oldest != null) upper = minOf(chunk.end, Instant.ofEpochMilli(oldest + OVERLAP_MS))
            if (upper.isAfter(chunk.start)) {
                var token: String? = null
                do {
                    val page = source.read(type, chunk.start, upper, pageSize, token)
                    if (page.records.isNotEmpty()) {
                        sink.send(chunk, page.records)
                        sent += page.records.size
                        confirmed += page.records.size
                        val starts = page.records.mapNotNull { recordStart(it)?.toEpochMilli() }
                        if (ordered && starts.size == page.records.size) {
                            val pageOldest = starts.min()
                            // Newest first is what makes "everything newer than X is done" true. If a page ever breaks that, stop trusting partial progress.
                            if (oldest != null && starts.max() > oldest + OVERLAP_MS) ordered = false else oldest = minOf(oldest ?: pageOldest, pageOldest)
                        } else ordered = false
                        store.put(chunk.id, key, TypeProgress(false, if (ordered) oldest else null, sent, now()))
                        onBatch(key.substringAfterLast('.'), page.records.size, confirmed)
                    }
                    token = page.nextToken
                } while (!token.isNullOrBlank())
            }
            store.put(chunk.id, key, TypeProgress(true, oldest, sent, now()))
        }
        return confirmed
    }

    companion object {
        const val OVERLAP_MS = 1_000L
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}

internal class HealthConnectSource(private val client: HealthConnectClient) : RecordSource {
    @Suppress("UNCHECKED_CAST", "EXPERIMENTAL_API_USAGE")
    override suspend fun read(type: KClass<out Record>, from: Instant, to: Instant, pageSize: Int, pageToken: String?): SourcePage {
        // ascendingOrder = false: newest first, which is what the importer's checkpoints rely on.
        val page = client.readRecords(ReadRecordsRequest(type as KClass<Record>, TimeRangeFilter.between(from, to), emptySet(), false, pageSize, pageToken))
        return SourcePage(page.records, page.pageToken)
    }
}

internal class PrefsResumeStore(context: Context) : ResumeStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(chunkId: String, typeKey: String): TypeProgress? {
        val parts = prefs.getString(key(chunkId, typeKey), null)?.split('|') ?: return null
        if (parts.size != 4) return null
        return TypeProgress(parts[0] == "1", parts[1].toLongOrNull(), parts[2].toIntOrNull() ?: return null, parts[3].toLongOrNull() ?: return null)
    }

    override fun put(chunkId: String, typeKey: String, progress: TypeProgress) {
        prefs.edit().putString(key(chunkId, typeKey), "${if (progress.done) 1 else 0}|${progress.oldestStartMs ?: ""}|${progress.sent}|${progress.savedAtMs}").apply()
    }

    override fun clear(chunkId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$chunkId#") }.forEach { editor.remove(it) }
        editor.apply()
    }

    override fun clearAll() { prefs.edit().clear().apply() }

    private fun key(chunkId: String, typeKey: String) = "$chunkId#$typeKey"

    companion object { const val PREFS_NAME = "gate_resume" }
}
