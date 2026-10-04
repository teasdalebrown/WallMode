package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class PulsePersonaWakeTest {
    @Test fun `native gate retains strict comparison and full rolling window`() {
        assertFalse(pulsePersonaWakeAccepts(listOf(204, 204, 204)))
        assertTrue(pulsePersonaWakeAccepts(listOf(204, 204, 205)))
        assertFalse(pulsePersonaWakeAccepts(listOf(255, 255)))
        assertFalse(pulsePersonaWakeAccepts(listOf(256, 255, 255)))
    }
    @Test fun `native features match retained integer transform at ties and saturation`() {
        assertEquals(-128, pulsePersonaFeatureQuantize(0))
        assertEquals(-127, pulsePersonaFeatureQuantize(2))
        assertEquals(0, pulsePersonaFeatureQuantize(333))
        assertEquals(127, pulsePersonaFeatureQuantize(666))
        assertEquals(127, pulsePersonaFeatureQuantize(65535))
    }
    @Test fun `named audio transport carries detection without changing endpoint`() {
        assertEquals("/audio?endpoint_id=honor_endpoint&wake_word=annabel", pulseAudioPath("honor_endpoint", "annabel"))
        assertEquals("/audio?endpoint_id=honor_endpoint&wake_word=morris", pulseAudioPath("honor_endpoint", "morris"))
        assertEquals("/audio?endpoint_id=honor_endpoint&wake_word=hey_pulse", pulseAudioPath("honor_endpoint", "hey_pulse"))
    }
    @Test fun `only the detected persona prefix is removed`() {
        assertEquals("what time is it?", PulseWakePhrase.commandAfterDetectedWake("Annabelle, what time is it?", "annabel"))
        assertEquals("time", PulseWakePhrase.commandAfterDetectedWake("Morris. time", "morris"))
        assertEquals("turn off Office Main", PulseWakePhrase.commandAfterDetectedWake("Hey Annabel, turn off Office Main", "annabel"))
        assertEquals("", PulseWakePhrase.commandAfterDetectedWake("Morris", "morris"))
        assertEquals("ordinary conversation", PulseWakePhrase.commandAfterDetectedWake("ordinary conversation", "annabel"))
    }
}
