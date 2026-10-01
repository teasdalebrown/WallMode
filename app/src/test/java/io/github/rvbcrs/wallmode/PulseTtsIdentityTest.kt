package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseTtsIdentityTest {
    @Test fun completeAnswersStartWithFirstSentenceWithoutOmittingTheRest() {
        val answer = "Pulseutility reports a warning. Office needs attention. Current checks are OK."
        val chunks = pulseCompleteSpeechChunks(answer)
        assertEquals(answer, chunks.joinToString(" "))
        assertEquals(1, chunks.size)
        assertEquals(emptyList<String>(), pulseCompleteSpeechChunks(" "))
    }
    @Test fun tinyFragmentsRemainTogetherWithoutDroppingWords() {
        val answer = "Winston Churchill. Born. 1874. Prime Minister. Wartime leader. His writings were influential. He received the Nobel Prize in Literature."
        val chunks = pulseCompleteSpeechChunks(answer)
        assertEquals(answer, chunks.joinToString(" "))
        assertTrue(chunks.all { it.length >= 40 })
    }
    @Test fun cancelledRequestRejectsLateWork() {
        val cancellation = PulseVoiceCancellation()
        cancellation.check()
        cancellation.cancel()
        var rejected = false
        try { cancellation.check() } catch (_: java.util.concurrent.CancellationException) { rejected = true }
        assertTrue(rejected)
    }
    @Test fun longUnpunctuatedPassageIsBoundedWithoutLosingText() {
        val answer = (1..100).joinToString(" ") { "word$it" }
        val chunks = pulseCompleteSpeechChunks(answer)
        assertEquals(answer, chunks.joinToString(" "))
        assertTrue(chunks.all { it.length <= 220 })
    }
    @Test fun completeAnswersAndStreamedAnswersAreBothSupported() {
        assertFalse(pulseResponseIsStream("application/json"))
        assertFalse(pulseResponseIsStream("application/json; charset=utf-8"))
        assertTrue(pulseResponseIsStream("application/x-ndjson; charset=utf-8"))
    }
    @Test fun speechAlwaysCarriesEndpointIdentity() {
        assertEquals("/tts?text=Hello%2C+I+am+Morris.&endpoint_id=honor_endpoint",
            pulseTtsPath("Hello, I am Morris.", "honor_endpoint"))
    }

    @Test fun identityAndTextAreEncodedSeparately() {
        assertEquals("/tts?text=Question%3F+%26+answer&endpoint_id=room%2Ftablet",
            pulseTtsPath("Question? & answer", "room/tablet"))
    }
}
