package com.bishop.healthconnectgate

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Instant
import kotlin.reflect.KClass

class ChunkImporterTest {
    private val t0 = Instant.parse("2026-05-01T00:00:00Z")
    private val chunk = ChunkInfo("2026-05|2026-05-01T00:00:00Z|2026-06-01T00:00:00Z", t0, Instant.parse("2026-06-01T00:00:00Z"))
    private val stepsType: KClass<out Record> = StepsRecord::class
    private val weightType: KClass<out Record> = WeightRecord::class

    private fun steps(minute: Int): Record {
        val start = t0.plusSeconds(minute * 60L)
        return StepsRecord(start, null, start.plusSeconds(30), null, minute + 1L, Metadata.manualEntry())
    }

    private fun weight(minute: Int): Record = WeightRecord(t0.plusSeconds(minute * 60L), null, Mass.kilograms(70.0 + minute), Metadata.manualEntry())

    /** Health Connect stand-in: newest first per type (or oldest first with [ascending]), filtered by start time, paged with a token. */
    private class FakeSource(val records: Map<KClass<out Record>, List<Record>>, val ascending: Boolean = false) : RecordSource {
        val reads = mutableListOf<Triple<String, Instant, Instant>>()
        override suspend fun read(type: KClass<out Record>, from: Instant, to: Instant, pageSize: Int, pageToken: String?): SourcePage {
            reads += Triple(type.java.simpleName, from, to)
            val sorted = (records[type] ?: emptyList()).filter { val s = recordStart(it)!!; !s.isBefore(from) && s.isBefore(to) }
                .sortedBy { recordStart(it) }.let { if (ascending) it else it.reversed() }
            val offset = pageToken?.toInt() ?: 0
            val page = sorted.drop(offset).take(pageSize)
            return SourcePage(page, if (offset + pageSize < sorted.size) (offset + pageSize).toString() else null)
        }
    }

    private class Sink(private val failOn: MutableSet<Int> = mutableSetOf()) : BatchSink {
        val sent = mutableListOf<Record>()
        var calls = 0
        fun failOnCall(n: Int) { failOn += n }
        override suspend fun send(chunk: ChunkInfo, records: List<Record>) {
            calls++
            if (calls in failOn) throw SocketTimeoutException("read timed out")
            sent += records
        }
    }

    private fun starts(records: List<Record>) = records.map { recordStart(it)!!.epochSecond }

    @Test fun importsEveryTypeNewestFirstAndRemembersItIsDone() = runBlocking {
        val store = MemoryResumeStore(); val sink = Sink()
        val source = FakeSource(mapOf(stepsType to List(10) { steps(it) }, weightType to List(3) { weight(it) }))
        val total = ChunkImporter(source, sink, store).import(chunk, listOf(stepsType, weightType), pageSize = 4)
        assertEquals(13, total); assertEquals(13, sink.sent.size)
        assertEquals(starts(sink.sent.take(10)), starts(sink.sent.take(10)).sortedDescending()) // newest first inside the type
        assertTrue(store.entries.values.all { it.done })
    }

    @Test fun aDroppedConnectionIsContinuedNotRestarted() = runBlocking {
        val store = MemoryResumeStore()
        val all = List(20) { steps(it) }
        val source = FakeSource(mapOf(stepsType to all))
        val first = Sink().apply { failOnCall(3) }             // two pages (10 newest records) are confirmed, then the connection times out
        try { ChunkImporter(source, first, store).import(chunk, listOf(stepsType), pageSize = 5); fail("expected the timeout") } catch (e: SocketTimeoutException) { /* expected */ }
        assertEquals(10, first.sent.size)
        val saved = store.get(chunk.id, StepsRecord::class.java.name)!!
        assertFalse(saved.done); assertEquals(10, saved.sent); assertEquals(t0.plusSeconds(10 * 60).toEpochMilli(), saved.oldestStartMs)

        val second = Sink(); val resumedSource = FakeSource(mapOf(stepsType to all))
        val total = ChunkImporter(resumedSource, second, store).import(chunk, listOf(stepsType), pageSize = 5)
        assertEquals(21, total)                                  // 10 confirmed by the first attempt + 11 now (the overlapping record counts twice)
        assertEquals(11, second.sent.size)                       // the 10 older records and ONE overlapping record, not the whole month again
        assertEquals((0..19).toSet(), (first.sent + second.sent).map { ((recordStart(it)!!.epochSecond - t0.epochSecond) / 60).toInt() }.toSet())
        assertEquals(t0.plusSeconds(10 * 60 + 1), resumedSource.reads.first().third) // the window was cut just below the confirmed part
        assertTrue(store.get(chunk.id, StepsRecord::class.java.name)!!.done)
    }

    @Test fun finishedTypesAreNotReadAgain() = runBlocking {
        val store = MemoryResumeStore()
        val data = mapOf(stepsType to List(6) { steps(it) }, weightType to List(4) { weight(it) })
        val first = Sink().apply { failOnCall(3) }              // steps: 2 pages of 3 are done, weight fails on its first page
        try { ChunkImporter(FakeSource(data), first, store).import(chunk, listOf(stepsType, weightType), pageSize = 3); fail() } catch (e: SocketTimeoutException) { /* expected */ }
        val source = FakeSource(data); val second = Sink()
        ChunkImporter(source, second, store).import(chunk, listOf(stepsType, weightType), pageSize = 3)
        assertTrue("steps must not be read again", source.reads.none { it.first == "StepsRecord" })
        assertEquals(4, second.sent.size)
    }

    @Test fun anInterruptedImportCanBeInterruptedAgainAndStillEndsComplete() = runBlocking {
        val store = MemoryResumeStore()
        val all = List(30) { steps(it) }
        val delivered = mutableListOf<Record>()
        var fromCall = 3   // the 3rd send of every run fails: two pages are confirmed per run
        repeat(2) {
            val sink = Sink().apply { failOnCall(fromCall) }
            try { ChunkImporter(FakeSource(mapOf(stepsType to all)), sink, store).import(chunk, listOf(stepsType), pageSize = 5); fail() } catch (e: SocketTimeoutException) { /* expected */ }
            delivered += sink.sent
        }
        val last = Sink()
        val total = ChunkImporter(FakeSource(mapOf(stepsType to all)), last, store).import(chunk, listOf(stepsType), pageSize = 5)
        delivered += last.sent
        assertTrue(total >= 30)
        assertEquals((0..29).toSet(), delivered.map { ((recordStart(it)!!.epochSecond - t0.epochSecond) / 60).toInt() }.toSet())
        assertTrue("only the overlap may be sent twice", delivered.size <= 30 + 3)
    }

    @Test fun aRunThatWasFullyDeliveredButNotConfirmedSendsNothingAgain() = runBlocking {
        val store = MemoryResumeStore()
        val data = mapOf(stepsType to List(8) { steps(it) })
        ChunkImporter(FakeSource(data), Sink(), store).import(chunk, listOf(stepsType), pageSize = 4)   // the "chunk complete" request then fails: nothing is cleared
        val source = FakeSource(data); val sink = Sink()
        val total = ChunkImporter(source, sink, store).import(chunk, listOf(stepsType), pageSize = 4)
        assertEquals(8, total); assertTrue(source.reads.isEmpty()); assertEquals(0, sink.calls)
    }

    @Test fun clearingTheChunkStartsItOverAndOldProgressExpires() = runBlocking {
        val store = MemoryResumeStore(); val data = mapOf(stepsType to List(6) { steps(it) })
        ChunkImporter(FakeSource(data), Sink(), store).import(chunk, listOf(stepsType), pageSize = 3)
        store.clear(chunk.id)
        val again = Sink(); ChunkImporter(FakeSource(data), again, store).import(chunk, listOf(stepsType), pageSize = 3)
        assertEquals(6, again.sent.size)

        var clock = 1_000L
        val expiring = MemoryResumeStore()
        ChunkImporter(FakeSource(data), Sink(), expiring) { clock }.import(chunk, listOf(stepsType), pageSize = 3)
        clock += ChunkImporter.TTL_MS + 1                      // a server restored from a backup must not be trusted for long
        val late = Sink(); ChunkImporter(FakeSource(data), late, expiring) { clock }.import(chunk, listOf(stepsType), pageSize = 3)
        assertEquals(6, late.sent.size)
    }

    @Test fun partialProgressIsNotTrustedWhenTheSourceIsNotNewestFirst() = runBlocking {
        val store = MemoryResumeStore(); val data = mapOf(stepsType to List(12) { steps(it) })
        val first = Sink().apply { failOnCall(3) }
        try { ChunkImporter(FakeSource(data, ascending = true), first, store).import(chunk, listOf(stepsType), pageSize = 4); fail() } catch (e: SocketTimeoutException) { /* expected */ }
        assertNull(store.get(chunk.id, StepsRecord::class.java.name)!!.oldestStartMs)
        val second = Sink()
        ChunkImporter(FakeSource(data, ascending = true), second, store).import(chunk, listOf(stepsType), pageSize = 4)
        assertEquals(12, second.sent.size)                       // everything again: correctness before economy
    }

    @Test fun recordStartReadsIntervalAndInstantRecords() {
        assertEquals(t0.plusSeconds(120), recordStart(steps(2)))
        assertEquals(t0.plusSeconds(180), recordStart(weight(3)))
    }
}
