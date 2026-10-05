package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Test

class PulseVoiceRouteTest {
    @Test
    fun `question domains reuse the established streamed endpoint route`() {
        listOf(
            "Question. Tell me about Winston Churchill",
            "Diagnose Office Fan. Why is it unavailable?",
            "Diagnose. Office Fan",
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
    @Test
    fun `explicit Diagnose retains original problem during either followup`() {
        val text = "Diagnose the Office Fan. Why won't it connect?"
        listOf(false, true).forEach { chat ->
            listOf(false, true).forEach { prompted ->
                assertEquals(text, pulseRoutedTranscript(text, chat, prompted))
                assertEquals("/question-stream", pulseVoiceRoutePath(pulseRoutedTranscript(text, chat, prompted)))
            }
        }
        val question = "Question Diagnose Office Fan"
        assertEquals(question, pulseRoutedTranscript(question, true, true))
    }

    @Test
    fun `ordinary followups retain Chat and prompted Question modes`() {
        assertEquals("chat tell me more", pulseRoutedTranscript("tell me more", true, true))
        assertEquals("question tell me more", pulseRoutedTranscript("tell me more", false, true))
        assertEquals("tell me more", pulseRoutedTranscript("tell me more", false, false))
    }
}
