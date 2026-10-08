package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class PulseAcousticStopGateTest {
    @Test fun knownDiagnosisPlaybackAllowsNewAcousticStop() {
        val gate = PulseAcousticStopGate(); val player = Any()
        val before = gate.detectionEpoch()
        gate.playback(player, true, "Home Assistant received a device MQTT report. Battery is 100 percent.")
        assertTrue(gate.allowed())
        assertFalse(gate.accepts(before))
        assertTrue(gate.accepts(gate.detectionEpoch()))
    }

    @Test fun assistantStopWordsAndUnknownAudioRemainSuppressed() {
        for (text in listOf(null, "", "Say Stop to interrupt", "It has stopped", "Stopping playback", "Use the stopper", "A nonstop announcement")) {
            val gate = PulseAcousticStopGate(); val player = Any()
            gate.playback(player, true, text)
            assertFalse(gate.allowed()); assertFalse(gate.accepts(gate.detectionEpoch()))
            gate.playback(player, false)
            assertTrue(gate.allowed())
        }
    }

    @Test fun finishingCueCannotRearmStopOverlappingUnsafeAnswer() {
        val gate = PulseAcousticStopGate(); val cue = Any(); val answer = Any()
        gate.playback(cue, true, "Just a moment"); gate.playback(answer, true, "Stop the fan")
        gate.playback(cue, false)
        assertFalse(gate.allowed())
        gate.playback(answer, false)
        assertTrue(gate.allowed())
    }

    @Test fun unknownCueFinishingRearmsKnownSafeAnswerButRejectsOldDetection() {
        val gate = PulseAcousticStopGate(); val cue = Any(); val answer = Any()
        gate.playback(cue, true); gate.playback(answer, true, "Battery is 100 percent")
        val suppressedEpoch = gate.detectionEpoch()
        gate.playback(cue, false)
        assertTrue(gate.allowed()); assertFalse(gate.accepts(suppressedEpoch))
        assertTrue(gate.accepts(gate.detectionEpoch()))
    }

    @Test fun queuedStopCannotCancelAfterCompletePlaybackCycleOrNewTurn() {
        val gate = PulseAcousticStopGate(); val player = Any()
        val old = gate.detectionEpoch()
        assertTrue(gate.accepts(old))
        gate.playback(player, true, "The battery is full"); gate.playback(player, false)
        assertTrue(gate.allowed()); assertFalse(gate.accepts(old))
        val current = gate.detectionEpoch()
        assertTrue(gate.accepts(current))
        gate.invalidate()
        assertFalse(gate.accepts(current))
    }

    @Test fun repeatedCallbackDoesNotInvalidateCurrentClipDetection() {
        val gate = PulseAcousticStopGate(); val player = Any()
        gate.playback(player, true, "Battery is full"); val epoch = gate.detectionEpoch()
        gate.playback(player, true, "Battery is full")
        assertTrue(gate.accepts(epoch))
    }

    @Test fun physicalCancelRemainsIndependentOfPlaybackSuppression() {
        val acoustic = PulseAcousticStopGate(); val hardware = PulsePlaybackGate()
        val handover = PulseSpeechHandover(); var stopped = false
        acoustic.playback(hardware, true, "Say Stop to interrupt")
        handover.registerCue(hardware) { hardware.cancel { stopped = true } }
        hardware.start {}
        handover.cancel()
        assertTrue(stopped); assertTrue(hardware.cancelled)
        try { handover.audioReady(); fail("Cancelled request accepted audio") }
        catch (_: CancellationException) {}
        acoustic.playback(hardware, false)
        assertTrue(acoustic.allowed())
    }
}
