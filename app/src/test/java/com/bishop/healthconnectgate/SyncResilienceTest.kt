package com.bishop.healthconnectgate

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

class SyncResilienceTest {
    private val noJitter = RetryPolicy(jitter = 0.0)

    // -- classification
    @Test fun networkTroubleIsTransient() {
        for (e in listOf(SocketTimeoutException(), ConnectException(), UnknownHostException(), java.io.EOFException(), IOException("reset")))
            assertEquals(e.javaClass.simpleName, ErrorClass.TRANSIENT, ErrorClassifier.classify(e))
    }

    @Test fun serverHiccupsAreTransientAndClientErrorsAreNot() {
        for (code in listOf(408, 429, 500, 502, 503, 504)) assertEquals(code.toString(), ErrorClass.TRANSIENT, ErrorClassifier.classify(HttpStatusException(code)))
        for (code in listOf(400, 404, 413, 415)) assertEquals(code.toString(), ErrorClass.PERMANENT, ErrorClassifier.classify(HttpStatusException(code)))
        for (code in listOf(401, 403)) assertEquals(code.toString(), ErrorClass.AUTH, ErrorClassifier.classify(HttpStatusException(code)))
    }

    @Test fun sessionAndPermissionProblemsAreNotTransient() {
        assertEquals(ErrorClass.AUTH, ErrorClassifier.classify(AuthRequiredException("expired")))
        assertEquals(ErrorClass.PERMANENT, ErrorClassifier.classify(NoPermissionException()))
        assertEquals(ErrorClass.PERMANENT, ErrorClassifier.classify(IllegalStateException("Sync rejected")))
        assertEquals(ErrorClass.PERMANENT, ErrorClassifier.classify(javax.net.ssl.SSLPeerUnverifiedException("bad cert")))
    }

    @Test fun resumableAndWorkerRetryDecisions() {
        assertTrue(ErrorClassifier.isResumable(SocketTimeoutException()))
        assertTrue(ErrorClassifier.isResumable(AuthRequiredException("x")))
        assertFalse(ErrorClassifier.isResumable(NoPermissionException()))
        assertTrue(ErrorClassifier.shouldRetryWork(SocketTimeoutException(), 0))
        assertTrue(ErrorClassifier.shouldRetryWork(SocketTimeoutException(), 7))
        assertFalse(ErrorClassifier.shouldRetryWork(SocketTimeoutException(), 8))
        assertFalse(ErrorClassifier.shouldRetryWork(AuthRequiredException("x"), 0))
        assertFalse(ErrorClassifier.shouldRetryWork(HttpStatusException(400), 0))
    }

    // -- withRetry
    @Test fun aTransientFailureIsRepeatedWithGrowingPauses() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val result = withRetry(noJitter, sleep = { sleeps += it }) { if (++calls < 3) throw SocketTimeoutException("slow") else "ok" }
        assertEquals("ok", result); assertEquals(3, calls); assertEquals(listOf(2000L, 5000L), sleeps)
    }

    @Test fun aPermanentFailureIsThrownAtOnce() = runBlocking {
        var calls = 0
        try { withRetry(noJitter, sleep = {}) { calls++; throw HttpStatusException(400) }; fail() } catch (e: HttpStatusException) { assertEquals(400, e.code) }
        assertEquals(1, calls)
    }

    @Test fun theLastFailureIsThrownAfterTheRetriesAreUsedUp() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        try { withRetry(noJitter, sleep = { sleeps += it }) { calls++; throw ConnectException("down") }; fail() } catch (e: ConnectException) { /* expected */ }
        assertEquals(4, calls); assertEquals(RetryPolicy.DEFAULT_DELAYS, sleeps)
    }

    @Test fun cancellationIsNeverRetried() = runBlocking {
        var calls = 0
        try { withRetry(noJitter, sleep = {}) { calls++; throw CancellationException("stop") }; fail() } catch (e: CancellationException) { /* expected */ }
        assertEquals(1, calls)
    }

    @Test fun jitterStaysWithinTwentyPercent() {
        val policy = RetryPolicy()
        assertEquals(1600L, policy.delayFor(0) { 0.0 }); assertEquals(2400L, policy.delayFor(0) { 1.0 }); assertEquals(2000L, policy.delayFor(0) { 0.5 })
    }

    // -- batches
    @Test fun batchesRespectTheCountAndTheByteLimit() {
        val docs = List(10) { "x".repeat(100) }
        assertEquals(listOf(4, 4, 2), BatchPlan.split(docs, 4, 10_000) { it.length }.map { it.size })
        assertEquals(listOf(3, 3, 3, 1), BatchPlan.split(docs, 100, 300) { it.length }.map { it.size })
        assertEquals(listOf(1, 1), BatchPlan.split(listOf("y".repeat(500), "z".repeat(500)), 100, 300) { it.length }.map { it.size }) // oversized items travel alone
        assertTrue(BatchPlan.split(emptyList<String>(), 10) { it.length }.isEmpty())
    }

    @Test fun theBatchLimitHalvesOnTimeoutAndGrowsBackAfterASuccessStreak() {
        val batch = AdaptiveBatch(200)
        batch.onTimeout(); assertEquals(100, batch.limit)
        batch.onTimeout(); batch.onTimeout(); batch.onTimeout(); batch.onTimeout(); assertEquals(25, batch.limit)
        repeat(4) { batch.onSuccess() }; assertEquals(25, batch.limit)
        batch.onSuccess(); assertEquals(37, batch.limit)
        repeat(60) { batch.onSuccess() }; assertEquals(200, batch.limit)
    }

    // -- sender
    private fun docs(n: Int) = List(n) { """{"id":"r$it"}""" }

    @Test fun aHealthyUploadIsOneRequestPerPart() = runBlocking {
        val posts = mutableListOf<Int>()
        AdaptiveSender(AdaptiveBatch(100), noJitter, sleep = {}) { posts += it.size }.send(docs(250))
        assertEquals(listOf(100, 100, 50), posts)
    }

    @Test fun aTimedOutBodyIsSentInHalvesAndTheLimitShrinks() = runBlocking {
        val batch = AdaptiveBatch(100)
        val attempts = mutableListOf<Int>()
        val confirmed = mutableListOf<String>()
        var first = true
        AdaptiveSender(batch, noJitter, sleep = {}) { part ->
            attempts += part.size
            if (first) { first = false; throw SocketTimeoutException("read timed out") }
            confirmed += part
        }.send(docs(80))
        assertEquals(listOf(80, 40, 40), attempts)
        assertEquals(docs(80), confirmed)
        assertEquals(50, batch.limit)
    }

    @Test fun otherTransientFailuresAreRepeatedWithPauses() = runBlocking {
        val sleeps = mutableListOf<Long>(); val retries = mutableListOf<Int>()
        var calls = 0
        AdaptiveSender(AdaptiveBatch(100), noJitter, sleep = { sleeps += it }, onRetry = { n, _, _ -> retries += n }) {
            if (++calls <= 2) throw HttpStatusException(503)
        }.send(docs(5))
        assertEquals(3, calls); assertEquals(listOf(2000L, 5000L), sleeps); assertEquals(listOf(1, 2), retries)
    }

    @Test fun aTinyBatchThatKeepsTimingOutIsRepeatedThenReported() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        try { AdaptiveSender(AdaptiveBatch(100), noJitter, sleep = { sleeps += it }) { calls++; throw SocketTimeoutException() }.send(docs(10)); fail() }
        catch (e: SocketTimeoutException) { /* expected: the run stops and the next one resumes */ }
        assertEquals(4, calls); assertEquals(3, sleeps.size)
    }

    @Test fun aRejectedRequestIsNotRepeated() = runBlocking {
        var calls = 0
        try { AdaptiveSender(AdaptiveBatch(100), noJitter, sleep = {}) { calls++; throw HttpStatusException(413) }.send(docs(3)); fail() } catch (e: HttpStatusException) { /* expected */ }
        assertEquals(1, calls)
    }

    // -- envelope
    @Test fun theEnvelopeIsValidJsonWithTheRecordsAppended() {
        val head = JSONObject().put("schema_version", 1).put("run_id", "r").put("chunk_id", "c")
        val body = SyncEnvelope.body(head, docs(3))
        val json = JSONObject(body)
        assertEquals(3, json.getJSONArray("records").length()); assertEquals("r", json.getString("run_id")); assertEquals("r2", json.getJSONArray("records").getJSONObject(2).getString("id"))
        assertEquals(0, JSONObject(SyncEnvelope.body(head, emptyList())).getJSONArray("records").length())
        assertEquals(1, JSONObject(SyncEnvelope.body(JSONObject(), docs(1))).getJSONArray("records").length())
    }
}
