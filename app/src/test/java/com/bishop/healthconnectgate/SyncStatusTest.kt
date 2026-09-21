package com.bishop.healthconnectgate

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
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata

class SyncStatusTest {
    private val prefs = FakePrefs()
    private val store = SyncStatusStore(prefs)
    private val timeout = SocketTimeoutException("read timed out")

    @Test fun aFailedRunBecomesInterruptedAndShowsTheProblemWithItsTime() {
        store.begin(now = 1_000); store.progress("2026-05", 2, 3, 500, now = 2_000)
        store.failure(timeout, now = 3_000)
        val st = store.read(now = 4_000)
        assertEquals(SyncState.INTERRUPTED, st.state)
        assertEquals("The server cannot be reached. Check your connection.", st.lastError); assertEquals(3_000L, st.lastErrorMs)
        assertEquals(2, st.monthsDone); assertEquals(500, st.recordsThisRun)      // the progress stays visible
        val model = StatusModelBuilder.build(st)
        assertEquals(SyncLine.INTERRUPTED, model.line); assertEquals(st.lastError, model.errorText); assertTrue(model.syncEnabled)
    }

    @Test fun aProblemThatNeedsTheUserIsFailedNotInterrupted() {
        store.begin(now = 1_000); store.failure(NoPermissionException(), now = 2_000)
        assertEquals(SyncState.FAILED, store.read(now = 3_000).state)
    }

    @Test fun aNewRunClearsTheOldProblemImmediately() {
        store.begin(now = 1_000); store.failure(timeout, now = 2_000)
        store.begin(now = 3_000)
        val st = store.read(now = 3_500)
        assertEquals(SyncState.RUNNING, st.state); assertEquals("", st.lastError); assertEquals(0L, st.lastErrorMs)
        val model = StatusModelBuilder.build(st)
        assertEquals(SyncLine.RUNNING, model.line); assertNull(model.errorText); assertFalse(model.syncEnabled)
    }

    @Test fun anOldProblemIsNeverShownWhileRunningOrIdle() {
        prefs.edit().putString("state", "RUNNING").putString("err", "stale").putLong("started", 1).putLong("updated", 1).apply()
        assertNull(StatusModelBuilder.build(store.read(now = 2)).errorText)
        prefs.edit().putString("state", "IDLE").apply()
        assertNull(StatusModelBuilder.build(store.read(now = 2)).errorText)
    }

    @Test fun successClearsTheProblemAndRecordsTheResult() {
        store.begin(now = 1_000); store.failure(timeout, now = 2_000); store.begin(now = 3_000); store.success(1234)
        val st = store.read(now = System.currentTimeMillis())
        assertEquals(SyncState.IDLE, st.state); assertEquals("", st.lastError); assertEquals(1234, st.lastSuccessRecords); assertTrue(st.lastSuccessMs > 0)
    }

    @Test fun aRunThatStopsReportingProgressShowsAsInterruptedAfterThreeMinutes() {
        store.begin(now = 0); store.progress("2026-05", 1, 3, 100, now = 60_000)
        assertEquals(SyncState.RUNNING, store.read(now = 60_000 + 179_000).state)
        assertEquals(SyncState.INTERRUPTED, store.read(now = 60_000 + 181_000).state)
        store.progress("2026-05", 1, 3, 200, now = 60_000 + 170_000)   // a confirmed batch is a heartbeat
        assertEquals(SyncState.RUNNING, store.read(now = 60_000 + 200_000).state)
    }

    // -- the whole story: connection lost, the button is pressed again, the import continues, the message goes away
    private val t0 = Instant.parse("2026-05-01T00:00:00Z")
    private val chunk = ChunkInfo("2026-05|a|b", t0, Instant.parse("2026-06-01T00:00:00Z"))
    private val stepsType: KClass<out Record> = StepsRecord::class
    private fun steps(minute: Int): Record { val s = t0.plusSeconds(minute * 60L); return StepsRecord(s, null, s.plusSeconds(30), null, 1, Metadata.manualEntry()) }

    private class Source(val all: List<Record>) : RecordSource {
        var readCalls = 0
        override suspend fun read(type: KClass<out Record>, from: Instant, to: Instant, pageSize: Int, pageToken: String?): SourcePage {
            readCalls++
            val sorted = all.filter { val s = recordStart(it)!!; !s.isBefore(from) && s.isBefore(to) }.sortedByDescending { recordStart(it) }
            val off = pageToken?.toInt() ?: 0
            return SourcePage(sorted.drop(off).take(pageSize), if (off + pageSize < sorted.size) (off + pageSize).toString() else null)
        }
    }

    @Test fun connectionLostThenSyncAgainContinuesAndClearsTheMessage() = runBlocking {
        val resume = MemoryResumeStore(); val all = List(20) { steps(it) }
        val delivered = mutableListOf<Record>()

        // run 1: the phone loses the connection while the third batch is being sent
        store.begin(now = 1_000)
        var sends = 0
        try {
            ChunkImporter(Source(all), { _, r -> if (++sends == 3) throw timeout else { delivered += r; store.progress("2026-05", 0, 1, delivered.size, now = 1_100) } }, resume)
                .import(chunk, listOf(stepsType), pageSize = 5)
            fail("expected the timeout")
        } catch (e: SocketTimeoutException) { store.failure(e, now = 2_000) }
        assertEquals(SyncState.INTERRUPTED, store.read(now = 2_100).state)
        assertEquals(10, delivered.size)

        // run 2: the button is pressed; the old message disappears at once and the import continues where it stopped
        store.begin(now = 60_000)
        assertNull(StatusModelBuilder.build(store.read(now = 60_001)).errorText)
        val before = delivered.size
        val total = ChunkImporter(Source(all), { _, r -> delivered += r }, resume).import(chunk, listOf(stepsType), pageSize = 5)
        resume.clear(chunk.id); store.success(total)

        assertEquals(21, total); assertTrue("only the overlap is sent twice", delivered.size - before <= 11)
        assertEquals((0..19).toSet(), delivered.map { ((recordStart(it)!!.epochSecond - t0.epochSecond) / 60).toInt() }.toSet())
        val st = store.read(now = System.currentTimeMillis())
        assertEquals(SyncState.IDLE, st.state); assertEquals("", st.lastError); assertTrue(resume.entries.isEmpty())
    }
}
