package com.bishop.healthconnectgate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ErrorTextTest {
    @Test fun sessionAndPermissionProblemsTellThePersonWhatToDo() {
        assertTrue(ErrorText.describe(AuthRequiredException("x")).contains("Sign in again"))
        assertTrue(ErrorText.describe(NoPermissionException()).contains("Grant"))
    }

    @Test fun httpStatusesAreExplainedInPlainLanguage() {
        assertTrue(ErrorText.describe(HttpStatusException(503)).contains("not available"))
        assertTrue(ErrorText.describe(HttpStatusException(504)).contains("try again"))
        assertTrue(ErrorText.describe(HttpStatusException(403)).contains("Sign in"))
        assertTrue(ErrorText.describe(HttpStatusException(418)).contains("rejected"))
        assertTrue(ErrorText.describe(HttpStatusException(500)).contains("HTTP 500"))
    }

    @Test fun networkFailuresAreDistinguished() {
        assertTrue(ErrorText.describe(UnknownHostException("h")).contains("not found"))
        assertTrue(ErrorText.describe(SocketTimeoutException()).contains("cannot be reached"))
        assertTrue(ErrorText.describe(ConnectException()).contains("cannot be reached"))
        assertTrue(ErrorText.describe(SSLHandshakeException("x")).contains("secure connection"))
    }

    @Test fun anythingElseNamesTheExceptionTypeButNeverItsMessage() {
        val text = ErrorText.describe(IllegalStateException("token=abc123"))
        assertEquals("Unexpected error (IllegalStateException).", text)
    }
}
