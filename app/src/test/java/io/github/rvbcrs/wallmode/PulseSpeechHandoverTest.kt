package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PulseSpeechHandoverTest {
    @Test fun readyAudioStopsStartedCueBeforeAnswerHardwareStarts() {
        val turn = PulseSpeechHandover(); val cue = PulsePlaybackGate()
        val events = mutableListOf<String>()
        assertTrue(turn.registerCue(cue) { cue.cancel { events.add("cue stopped") } })
        cue.start { events.add("cue played") }
        turn.audioReady()
        PulsePlaybackGate().start { events.add("answer played") }
        assertEquals(listOf("cue played", "cue stopped", "answer played"), events)
    }

    @Test fun readyAnswerCancelsCueBeforeItsFirstSample() {
        val turn = PulseSpeechHandover(); val cue = PulsePlaybackGate()
        assertTrue(turn.registerCue(cue) { cue.cancel {} })
        turn.audioReady()
        try { cue.start { fail("Late cue played") }; fail("Expected cancellation") }
        catch (_: CancellationException) {}
        assertFalse(turn.registerCue(Any()) {})
    }

    @Test fun finishingOlderCuePreservesNewCueCancellation() {
        val turn = PulseSpeechHandover(); val first = Any(); val second = Any()
        assertTrue(turn.registerCue(first) {})
        turn.cueFinished(first)
        var stopped = false
        assertTrue(turn.registerCue(second) { stopped = true })
        turn.cueFinished(first); turn.audioReady()
        assertTrue(stopped)
    }

    @Test fun overlappingCuesCannotQueueBehindOneAnother() {
        val turn = PulseSpeechHandover()
        assertTrue(turn.registerCue(Any()) {})
        assertFalse(turn.registerCue(Any()) {})
    }

    @Test fun stopRejectsPendingAnswerAndLateCues() {
        val turn = PulseSpeechHandover(); var stopped = false
        turn.registerCue(Any()) { stopped = true }; turn.cancel()
        assertTrue(stopped)
        assertFalse(turn.registerCue(Any()) {})
        try { turn.audioReady(); fail("Cancelled answer became ready") }
        catch (_: CancellationException) {}
    }

    @Test fun conversationContextUsesAcceptedTenMinutes() {
        assertEquals(600_000L, CHAT_SESSION_MILLIS)
    }
}
