package com.bishop.healthconnectgate

import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

class ChangesSyncTest {
    private val types: Set<KClass<out Record>> = setOf(StepsRecord::class)
    private val scope = ChangesSync.scopeOf("server.example", types)
    private val t0 = Instant.parse("2026-09-20T10:00:00Z")
    private var clock = t0

    private class FakeState(override var token: String? = null, override var scope: String? = null, override var fullReadAt: Instant? = null) : ChangesState

    private fun steps(count: Long): Record = StepsRecord(Instant.parse("2026-09-19T10:00:00Z"), null, Instant.parse("2026-09-19T10:30:00Z"), null, count, Metadata.manualEntry())

    private class FakeFeed(val pages: MutableMap<String, ChangePage?> = mutableMapOf(), var freshToken: String = "fresh", var failNewToken: Boolean = false,
                           var failRead: Boolean = false) : ChangeFeed {
        val reads = mutableListOf<String>()
        var newTokenCalls = 0
        override suspend fun newToken(types: Set<KClass<out Record>>): String {
            newTokenCalls++
            if (failNewToken) throw SecurityException("no permission")
            return freshToken
        }
        override suspend fun read(token: String): ChangePage? {
            reads += token
            if (failRead) throw IllegalStateException("boom")
            return pages[token]
        }
    }

    private class Recorder : ChangeUploader {
        val calls = mutableListOf<Pair<Int, List<String>>>()
        var failWith: Throwable? = null
        override suspend fun upload(upserts: List<Record>, deletedIds: List<String>) {
            failWith?.let { throw it }
            calls += upserts.size to deletedIds
        }
    }

    private fun sync(feed: ChangeFeed, state: ChangesState, uploader: ChangeUploader, logs: MutableList<String> = mutableListOf()) =
        ChangesSync(feed, state, uploader, now = { clock }, log = { logs += it })

    private fun ready(token: String = "t1") = FakeState(token, scope, t0.minus(Duration.ofHours(1)))

    @Test fun withoutATokenTheOpenWindowMustBeReadInFullAndTheTokenIsOnlyRememberedAfterwards() = runBlocking {
        val state = FakeState()
        val feed = FakeFeed(freshToken = "T0")
        val uploader = Recorder()
        val sync = sync(feed, state, uploader)
        val result = sync.apply(types, scope) as ChangesResult.FullReadNeeded
        assertEquals("T0", result.newToken)
        assertNull("not stored before the full read succeeded", state.token)
        assertTrue(uploader.calls.isEmpty())
        sync.remember(result, scope)
        assertEquals("T0", state.token); assertEquals(scope, state.scope); assertEquals(clock, state.fullReadAt)
    }

    @Test fun aTokenIsPagedThroughAndCheckpointedAfterEveryUploadedPage() = runBlocking {
        val state = ready("t1")
        val feed = FakeFeed(mutableMapOf(
            "t1" to ChangePage(listOf(steps(1), steps(2)), listOf("d1"), "t2", true),
            "t2" to ChangePage(listOf(steps(3)), emptyList(), "t3", false)))
        val uploader = Recorder()
        val result = sync(feed, state, uploader).apply(types, scope) as ChangesResult.Applied
        assertEquals(3, result.upserts); assertEquals(1, result.deletions)
        assertEquals(listOf(2 to listOf("d1"), 1 to emptyList<String>()), uploader.calls)
        assertEquals(listOf("t1", "t2"), feed.reads)
        assertEquals("t3", state.token)
    }

    @Test fun anEmptyPageAdvancesTheTokenWithoutAnUpload() = runBlocking {
        val state = ready("t1")
        val feed = FakeFeed(mutableMapOf("t1" to ChangePage(emptyList(), emptyList(), "t2", false)))
        val uploader = Recorder()
        val result = sync(feed, state, uploader).apply(types, scope) as ChangesResult.Applied
        assertEquals(0, result.upserts + result.deletions)
        assertTrue(uploader.calls.isEmpty()); assertEquals("t2", state.token)
    }

    @Test fun anExpiredTokenMeansAFullReadWithAFreshToken() = runBlocking {
        val state = ready("old")
        val feed = FakeFeed(mutableMapOf("old" to null), freshToken = "T9")
        val result = sync(feed, state, Recorder()).apply(types, scope) as ChangesResult.FullReadNeeded
        assertEquals("T9", result.newToken)
        assertNull("the dead token is dropped", state.token)
    }

    @Test fun aChangedServerOrDataSelectionInvalidatesTheToken() = runBlocking {
        for (other in listOf(ChangesSync.scopeOf("another.example", types), ChangesSync.scopeOf("server.example", types + setOf(androidx.health.connect.client.records.WeightRecord::class)))) {
            val feed = FakeFeed(mutableMapOf("t1" to ChangePage(listOf(steps(1)), emptyList(), "t2", false)))
            val result = sync(feed, ready("t1"), Recorder()).apply(types, other)
            assertTrue(result is ChangesResult.FullReadNeeded)
            assertTrue("the stale token must not be read", feed.reads.isEmpty())
        }
    }

    @Test fun scopesDifferByServerAndTypesButNotByOrder() {
        val a = ChangesSync.scopeOf("s", listOf(StepsRecord::class, androidx.health.connect.client.records.WeightRecord::class))
        val b = ChangesSync.scopeOf("s", listOf(androidx.health.connect.client.records.WeightRecord::class, StepsRecord::class))
        assertEquals(a, b)
        assertNotEquals(a, ChangesSync.scopeOf("s", listOf(StepsRecord::class)))
        assertNotEquals(a, ChangesSync.scopeOf("t", listOf(StepsRecord::class, androidx.health.connect.client.records.WeightRecord::class)))
    }

    @Test fun aWeekOldFullReadIsRepeatedEvenWithAValidToken() = runBlocking {
        val state = FakeState("t1", scope, t0.minus(Duration.ofDays(8)))
        val feed = FakeFeed(mutableMapOf("t1" to ChangePage(listOf(steps(1)), emptyList(), "t2", false)), freshToken = "T5")
        val result = sync(feed, state, Recorder()).apply(types, scope) as ChangesResult.FullReadNeeded
        assertEquals("T5", result.newToken)
        assertTrue(feed.reads.isEmpty())
        val recent = FakeState("t1", scope, t0.minus(Duration.ofDays(6)))
        assertTrue(sync(FakeFeed(mutableMapOf("t1" to ChangePage(emptyList(), emptyList(), "t2", false))), recent, Recorder()).apply(types, scope) is ChangesResult.Applied)
    }

    @Test fun aTokenWithoutARecordedFullReadIsNotTrusted() = runBlocking {
        val result = sync(FakeFeed(freshToken = "T1"), FakeState("t1", scope, null), Recorder()).apply(types, scope)
        assertTrue(result is ChangesResult.FullReadNeeded)
    }

    @Test fun aFailedUploadLeavesTheTokenWhereItWasAndPropagates() = runBlocking {
        val state = ready("t1")
        val feed = FakeFeed(mutableMapOf("t1" to ChangePage(listOf(steps(1)), emptyList(), "t2", false)))
        val uploader = Recorder().apply { failWith = java.io.IOException("offline") }
        try { sync(feed, state, uploader).apply(types, scope); fail("expected the upload failure") } catch (e: java.io.IOException) { /* expected */ }
        assertEquals("t1", state.token)
    }

    @Test fun anUploadRefusedByTheServerPropagatesAsUnavailable() = runBlocking {
        val state = ready("t1")
        val feed = FakeFeed(mutableMapOf("t1" to ChangePage(listOf(steps(1)), emptyList(), "t2", false)))
        val uploader = Recorder().apply { failWith = ChangesUnavailableException("HTTP 404") }
        try { sync(feed, state, uploader).apply(types, scope); fail("expected ChangesUnavailableException") } catch (e: ChangesUnavailableException) { /* expected */ }
        assertEquals("t1", state.token)
    }

    @Test fun healthConnectFailuresFallBackToAFullReadInsteadOfFailingTheSync() = runBlocking {
        val logs = mutableListOf<String>()
        val result = sync(FakeFeed(failRead = true, freshToken = "T2"), ready("t1"), Recorder(), logs).apply(types, scope)
        assertTrue(result is ChangesResult.FullReadNeeded)
        assertTrue(logs.any { it.contains("IllegalStateException") })
    }

    @Test fun noTokenAtAllStillAllowsTheFullReadAndNothingIsRemembered() = runBlocking {
        val state = FakeState()
        val logs = mutableListOf<String>()
        val sync = sync(FakeFeed(failNewToken = true), state, Recorder(), logs)
        val result = sync.apply(types, scope) as ChangesResult.FullReadNeeded
        assertNull(result.newToken)
        sync.remember(result, scope)
        assertNull(state.token)
        assertTrue(logs.any { it.contains("SecurityException") })
    }

    // -- wire format
    @Test fun envelopeCarriesRecordsIdsAndFormat() {
        val e = ChangesEnvelope.build("run-1", 2, listOf(JSONObject().put("id", "a")), listOf("x", "y"))
        assertEquals(1, e.getInt("schema_version")); assertEquals("run-1", e.getString("run_id")); assertEquals(2, e.getInt("record_format"))
        assertEquals("a", e.getJSONArray("upserts").getJSONObject(0).getString("id"))
        assertEquals(listOf("x", "y"), (0 until 2).map { e.getJSONArray("deleted_ids").getString(it) })
        assertTrue(!ChangesEnvelope.build("r", null, emptyList(), emptyList()).has("record_format"))
    }

    @Test fun batchesRespectTheServerLimitsAndSendRecordsBeforeDeletions() {
        val docs = (1..1201).map { JSONObject().put("id", "r$it") }
        val ids = (1..1001).map { "d$it" }
        val batches = ChangesEnvelope.batches("run", 2, docs, ids, 500)
        assertEquals(listOf(500, 500, 201, 0, 0, 0), batches.map { it.getJSONArray("upserts").length() })
        assertEquals(listOf(0, 0, 0, 500, 500, 1), batches.map { it.getJSONArray("deleted_ids").length() })
        assertEquals(1201, batches.sumOf { it.getJSONArray("upserts").length() }); assertEquals(1001, batches.sumOf { it.getJSONArray("deleted_ids").length() })
        assertTrue(ChangesEnvelope.batches("run", 2, emptyList(), emptyList(), 500).isEmpty())
        assertEquals(500, ChangesEnvelope.batches("run", 2, docs, emptyList(), 100000).first().getJSONArray("upserts").length())
    }
}
