package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PulseAcousticStopGateTest {
    @Test fun onlyActualPlaybackSuppressesAcousticStop() {
        val gate = PulseAcousticStopGate(); val player = Any()
        assertTrue(gate.allowed()) // Backend preparation remains cancellable.
        gate.playback(player, true)
        assertFalse(gate.allowed()) // Including the words stop or stopper in assistant speech.
        gate.playback(player, false)
        assertTrue(gate.allowed()) // Stop restored after speech.
    }

    @Test fun finishingCueCannotRearmStopOverlappingItsAnswer() {
        val gate = PulseAcousticStopGate(); val cue = Any(); val answer = Any()
        gate.playback(cue, true); gate.playback(answer, true)
        gate.playback(cue, false)
        assertFalse(gate.allowed())
        gate.playback(answer, false)
        assertTrue(gate.allowed())
    }

    @Test fun queuedStopCannotCancelAfterCompletePlaybackCycleOrNewTurn() {
        val gate = PulseAcousticStopGate(); val player = Any()
        val old = gate.detectionEpoch()
        assertTrue(gate.accepts(old))
        gate.playback(player, true); gate.playback(player, false)
        assertTrue(gate.allowed()); assertFalse(gate.accepts(old))
        val current = gate.detectionEpoch()
        assertTrue(gate.accepts(current))
        gate.invalidate()
        assertFalse(gate.accepts(current))
    }

    @Test fun physicalCancelRemainsIndependentOfPlaybackSuppression() {
        val acoustic = PulseAcousticStopGate(); val hardware = PulsePlaybackGate()
        val handover = PulseSpeechHandover(); var stopped = false
        acoustic.playback(hardware, true)
        handover.registerCue(hardware) { hardware.cancel { stopped = true } }
        hardware.start {}
        handover.cancel() // Same operation called by physical Cancel.
        assertTrue(stopped); assertTrue(hardware.cancelled)
        try { handover.audioReady(); fail("Cancelled request accepted audio") }
        catch (_: CancellationException) {}
        acoustic.playback(hardware, false)
        assertTrue(acoustic.allowed())
    }
}
