package com.bishop.healthconnectgate

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.cancellation.CancellationException

/*
 * Making a long synchronisation survive a bad connection.
 *
 * A phone on a weak or switching network loses connections (SocketTimeoutException on the phone, "499 client closed request" in the proxy).
 * The upload is idempotent (the server ignores an id it already has, or replaces it by a newer version), so a failed request can always be
 * repeated. This file holds the pure logic; SyncEngine wires it to the network and Health Connect.
 */

internal enum class ErrorClass {
    /** Worth repeating: the connection dropped, timed out or the server is briefly unavailable. */
    TRANSIENT,

    /** The session is gone or refused: repeating cannot help until the user signs in again (a later sync can resume). */
    AUTH,

    /** Repeating the same request cannot succeed (bad request, no permission, unexpected state). */
    PERMANENT
}

internal object ErrorClassifier {
    fun classify(error: Throwable): ErrorClass = when (error) {
        is AuthRequiredException -> ErrorClass.AUTH
        is NoPermissionException, is ChangesUnavailableException -> ErrorClass.PERMANENT
        is HttpStatusException -> when (error.code) {
            401, 403 -> ErrorClass.AUTH
            408, 425, 429, 500, 502, 503, 504 -> ErrorClass.TRANSIENT
            else -> ErrorClass.PERMANENT
        }
        is javax.net.ssl.SSLPeerUnverifiedException, is java.security.cert.CertificateException -> ErrorClass.PERMANENT
        // SocketTimeout, ConnectException, UnknownHost, SocketException, EOF, a dropped TLS handshake...
        is IOException -> ErrorClass.TRANSIENT
        else -> ErrorClass.PERMANENT
    }

    /** A failed run is resumable unless the error is permanent: the next run continues from the saved progress. */
    fun isResumable(error: Throwable): Boolean = classify(error) != ErrorClass.PERMANENT

    /** WorkManager: repeat the run only for transient errors, and give up after [maxAttempts] repeats. */
    fun shouldRetryWork(error: Throwable, runAttemptCount: Int, maxAttempts: Int = 8): Boolean =
        classify(error) == ErrorClass.TRANSIENT && runAttemptCount < maxAttempts
}

internal class RetryPolicy(val delaysMs: List<Long> = DEFAULT_DELAYS, private val jitter: Double = 0.2) {
    /** Pause before repeat number [attempt] (0-based), spread by +-[jitter] so that many phones do not knock at the same second. */
    fun delayFor(attempt: Int, random: () -> Double = { Math.random() }): Long =
        (delaysMs[attempt] * (1 + jitter * (random() * 2 - 1))).toLong().coerceAtLeast(0)

    companion object { val DEFAULT_DELAYS = listOf(2_000L, 5_000L, 15_000L) }
}

/** Runs [block]; a transient failure is repeated after the policy's pauses, anything else (and cancellation) is thrown at once. */
internal suspend fun <T> withRetry(
    policy: RetryPolicy = RetryPolicy(),
    sleep: suspend (Long) -> Unit = { delay(it) },
    random: () -> Double = { Math.random() },
    onRetry: (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (attempt >= policy.delaysMs.size || ErrorClassifier.classify(e) != ErrorClass.TRANSIENT) throw e
            val wait = policy.delayFor(attempt, random)
            attempt++
            onRetry(attempt, wait, e)
            sleep(wait)
        }
    }
}

internal object BatchPlan {
    /** Upper bound of one request body before compression. A weak uplink times out on big bodies long before the server minds. */
    const val MAX_BODY_BYTES = 384 * 1024

    /** Cuts [items] into consecutive parts of at most [maxItems] items and about [maxBytes] bytes (one oversized item goes alone). */
    fun <T> split(items: List<T>, maxItems: Int, maxBytes: Int = MAX_BODY_BYTES, sizeOf: (T) -> Int): List<List<T>> {
        require(maxItems >= 1) { "maxItems must be positive" }
        val parts = mutableListOf<List<T>>()
        var current = ArrayList<T>()
        var bytes = 0
        for (item in items) {
            val size = sizeOf(item)
            if (current.isNotEmpty() && (current.size >= maxItems || bytes + size > maxBytes)) {
                parts += current
                current = ArrayList()
                bytes = 0
            }
            current += item
            bytes += size
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }
}

/** How many records go into one request: halves after a timeout, grows back towards the configured size after a streak of successes. */
internal class AdaptiveBatch(private val configured: Int) {
    val minSize: Int = minOf(25, configured)
    var limit: Int = configured
        private set
    private var streak = 0

    fun onTimeout() { limit = maxOf(minSize, limit / 2); streak = 0 }

    fun onSuccess() {
        if (limit < configured && ++streak >= GROW_AFTER) { limit = minOf(configured, limit + maxOf(1, limit / 2)); streak = 0 }
    }

    private companion object { const val GROW_AFTER = 5 }
}

/**
 * Sends already serialised records through [post]. A timed-out body is split in halves (down to [AdaptiveBatch.minSize]) instead of being
 * repeated unchanged; other transient failures are repeated after the policy's pauses. Returns only when the server confirmed everything.
 */
internal class AdaptiveSender(
    private val batch: AdaptiveBatch,
    private val policy: RetryPolicy = RetryPolicy(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Double = { Math.random() },
    private val onRetry: (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
    private val post: suspend (docs: List<String>) -> Unit
) {
    suspend fun send(docs: List<String>) {
        for (part in BatchPlan.split(docs, batch.limit, sizeOf = { it.length })) sendPart(part)
    }

    private suspend fun sendPart(part: List<String>) {
        var attempt = 0
        while (true) {
            try {
                post(part)
                batch.onSuccess()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (ErrorClassifier.classify(e) != ErrorClass.TRANSIENT) throw e
                if (e is SocketTimeoutException && attempt == 0 && part.size > batch.minSize) {
                    batch.onTimeout()
                    val half = (part.size + 1) / 2
                    sendPart(part.subList(0, half))
                    sendPart(part.subList(half, part.size))
                    return
                }
                if (attempt >= policy.delaysMs.size) throw e
                val wait = policy.delayFor(attempt, random)
                attempt++
                onRetry(attempt, wait, e)
                sleep(wait)
            }
        }
    }
}

internal object SyncEnvelope {
    /** `{head..., "records":[doc, doc...]}` built from documents that are already JSON text, so nothing is serialised twice. */
    fun body(head: JSONObject, docs: List<String>): String {
        val text = head.toString()
        require(text.endsWith("}")) { "head must be a JSON object" }
        val separator = if (text.length > 2) "," else ""
        return text.dropLast(1) + separator + "\"records\":[" + docs.joinToString(",") + "]}"
    }
}
