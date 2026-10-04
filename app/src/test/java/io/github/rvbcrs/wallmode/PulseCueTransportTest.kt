package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class PulseCueTransportTest {
    @Test fun optionalCueUsesOnlyReviewedCacheAndCorrectEndpoint() {
        val url = pulseSpeechUrl("http://core/api/bridge", "One moment.", "honor_endpoint", true)
        assertTrue(url.contains("endpoint_id=honor_endpoint"))
        assertTrue(url.endsWith("&cache_only=true"))
        assertFalse(url.contains("stream=true"))
    }

    @Test fun ordinaryShortAndLongAnswersKeepStreaming() {
        for (text in listOf("Done.", "A complete explanation. ".repeat(30))) {
            val url = pulseSpeechUrl("http://core/api/bridge", text, "honor_endpoint", false)
            assertTrue(url.endsWith("&stream=true"))
            assertFalse(url.contains("cache_only"))
        }
    }
}
