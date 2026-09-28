package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseVoiceRouteTest {
    @Test
    fun `question domains reuse the established streamed endpoint route`() {
        listOf(
            "Question. Tell me about Winston Churchill",
            "chat diagnose my network",
            "news",
            "lookup current weather",
            "status"
        ).forEach {
            assertEquals(it, "/question-stream", pulseVoiceRoutePath(it))
        }
    }

    @Test
    fun `commands retain the established transcript route`() {
        listOf(
            "time", "play David Bowie", "blackout", "turn off hall light",
            "introduce yourself", "who are you", "question who are you"
        ).forEach {
            assertEquals(it, "/transcript", pulseVoiceRoutePath(it))
        }
    }
}
