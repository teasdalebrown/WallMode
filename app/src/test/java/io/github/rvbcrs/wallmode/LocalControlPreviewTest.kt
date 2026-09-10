package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalControlPreviewTest {
    @Test
    fun validatesRateLimitAndJpegBoundary() {
        assertEquals(0L, previewRetryAfterMillis(10_000L, Long.MIN_VALUE))
        assertEquals(1_500L, previewRetryAfterMillis(10_000L, 9_500L))
        assertEquals(0L, previewRetryAfterMillis(10_000L, 8_000L))

        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        assertTrue(isValidPreviewJpeg(jpeg))
        assertFalse(isValidPreviewJpeg(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assertFalse(isValidPreviewJpeg(jpeg, maxBytes = 3))
    }

    @Test
    fun invalidatesSessionWhenCredentialChangesOrExpires() {
        val session = LocalControlSession(expiresAtMillis = 20_000L, credentialIdentity = "hash-a")

        assertTrue(session.isValid(10_000L, "hash-a"))
        assertFalse(session.isValid(10_000L, "hash-b"))
        assertFalse(session.isValid(10_000L, ""))
        assertFalse(session.isValid(20_001L, "hash-a"))
    }

    @Test
    fun photoFormTokenIsSessionBoundAndSurvivesSessionRenewal() {
        val session = LocalControlSession(20_000L, "hash-a")
        val otherSession = LocalControlSession(20_000L, "hash-a")
        assertTrue(session.acceptsCsrfToken(session.csrfToken))
        assertTrue(session.copy(expiresAtMillis = 30_000L).acceptsCsrfToken(session.csrfToken))
        assertFalse(session.acceptsCsrfToken(null))
        assertFalse(session.acceptsCsrfToken(""))
        assertFalse(session.acceptsCsrfToken(otherSession.csrfToken))
    }
}
