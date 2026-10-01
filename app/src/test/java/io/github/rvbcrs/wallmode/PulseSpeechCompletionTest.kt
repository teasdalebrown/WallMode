package io.github.rvbcrs.wallmode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseSpeechCompletionTest {
    @Test fun queuedUiCallbackMustNotOpenListening() {
        assertFalse(pulseSpeechMayFinish(true, false, 0, 1))
    }
    @Test fun playingOrQueuedSpeechMustFinishFirst() {
        assertFalse(pulseSpeechMayFinish(true, true, 0, 0))
        assertFalse(pulseSpeechMayFinish(true, false, 1, 0))
        assertFalse(pulseSpeechMayFinish(false, false, 0, 0))
        assertTrue(pulseSpeechMayFinish(true, false, 0, 0))
    }
}
