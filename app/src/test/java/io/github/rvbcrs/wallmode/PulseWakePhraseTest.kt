package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseWakePhraseTest {
    @Test
    fun `extracts command only after an observed wake phrase`() {
        val parsed = PulseWakePhrase.parse("Hey Pulse, time.")
        assertTrue(parsed.verified)
        assertEquals("time", parsed.command)
    }

    @Test
    fun `accepts observed STT wake variants`() {
        listOf(
            "Haypoles News",
            "Paypost status",
            "Hey polls, time",
            "Pause. Time.",
            "Eight volts. Time.",
            "Big pulse. Time."
        ).forEach {
            assertTrue(it, PulseWakePhrase.parse(it).verified)
        }
    }

    @Test
    fun `pause is a wake variant only with punctuation and a following command`() {
        assertFalse(PulseWakePhrase.parse("pause").verified)
        assertFalse(PulseWakePhrase.parse("pause the music").verified)
        assertEquals("Time", PulseWakePhrase.parse("Pause. Time.").command)
    }

    @Test
    fun `pulse suffix permits a short mistranscribed first wake word`() {
        val parsed = PulseWakePhrase.parse("Big pulse. Time.")
        assertEquals("Time", parsed.command)
        assertTrue(parsed.strong)
        assertFalse(PulseWakePhrase.parse("the very big pulse was surprising").verified)
    }

    @Test
    fun `generic hey transcription is verified but not strong`() {
        val parsed = PulseWakePhrase.parse("Hey folks, time.")
        assertTrue(parsed.verified)
        assertFalse(parsed.strong)
        assertEquals("time", parsed.command)
    }

    @Test
    fun `compact fuzzy wake can recover a harmless trailing short command`() {
        val parsed = PulseWakePhrase.parse("8 polls time")
        assertTrue(parsed.verified)
        assertFalse(parsed.strong)
        assertEquals("time", parsed.command)
    }

    @Test
    fun `compact fuzzy wake cannot recover an action command`() {
        assertFalse(PulseWakePhrase.parse("8 polls blackout").verified)
        assertFalse(PulseWakePhrase.parse("hey folks turn off hall main").verified)
        assertFalse(PulseWakePhrase.parse("some unrelated words time").verified)
        assertFalse(PulseWakePhrase.parse("random false wake time").verified)
    }

    @Test
    fun `ordinary speech cannot masquerade as a verified wake`() {
        listOf(
            "time",
            "Please see the complete disclaimer at PissedConsumer.com",
            "It always makes me laugh when I go into a local pub"
        ).forEach {
            val parsed = PulseWakePhrase.parse(it)
            assertFalse(it, parsed.verified)
            assertEquals("", parsed.command)
        }
    }

    @Test
    fun `wake without a command is verified but empty`() {
        val parsed = PulseWakePhrase.parse("Hey Pulse")
        assertTrue(parsed.verified)
        assertEquals("", parsed.command)
    }

    @Test
    fun `confirmed local wake strips observed STT wake renderings before shared routing`() {
        assertEquals(
            "question tell me about Winston Churchill",
            PulseWakePhrase.commandAfterDetectedWake(
                "Hey Post, question tell me about Winston Churchill."
            )
        )
        assertEquals(
            "question tell me about Winston Churchill",
            PulseWakePhrase.commandAfterDetectedWake(
                "Hey folks, question tell me about Winston Churchill."
            )
        )
        assertEquals(
            "question tell me about Winston Churchill",
            PulseWakePhrase.commandAfterDetectedWake(
                "They both question tell me about Winston Churchill."
            )
        )
        assertEquals(
            "question tell me about Winston Churchill",
            PulseWakePhrase.commandAfterDetectedWake(
                "Hey Pauls, question tell me about Winston Churchill."
            )
        )
        assertEquals(
            "Question. Tell me about Winston Churchill",
            PulseWakePhrase.commandAfterDetectedWake(
                "Take false. Question. Tell me about Winston Churchill."
            )
        )
        assertEquals(
            "Question. Tell me about Winston Churchill",
            PulseWakePhrase.commandAfterDetectedWake(
                "Play false. Question. Tell me about Winston Churchill."
            )
        )
        assertEquals("News", PulseWakePhrase.commandAfterDetectedWake("Hank Foles, News."))
        assertEquals(
            "scene, turn on office main",
            PulseWakePhrase.commandAfterDetectedWake("8 bolts, scene, turn on office main.")
        )
        assertEquals(
            "play 1960s rock in the office",
            PulseWakePhrase.commandAfterDetectedWake("Hey, Pulse. Late 1960s rock in the office.")
        )
    }

    @Test
    fun `confirmed local wake leaves command acceptance to Pulse Core`() {
        assertEquals(
            "ordinary phone conversation",
            PulseWakePhrase.commandAfterDetectedWake("ordinary phone conversation")
        )
    }
}
