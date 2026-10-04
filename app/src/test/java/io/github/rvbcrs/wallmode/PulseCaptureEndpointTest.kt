package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class PulseCaptureEndpointTest {
    private val chunk = 1_280

    @Test fun continuingDetailedQuestionPassesOldSevenSecondCutoff() {
        // The same 80 ms chunk accounting used by the microphone loop.
        for (samples in chunk..16 * 16_000 step chunk) {
            assertNull("Submitted continuing speech at $samples samples",
                PulseCaptureEndpoint.endReason(samples, true, 0))
        }
        assertEquals(PulseCaptureEndReason.SILENCE,
            PulseCaptureEndpoint.endReason(18 * 16_000, true, 2 * 16_000))
    }

    @Test fun shortCommandEndsAfterTwoSecondsSilenceWithoutWaitingForCeiling() {
        assertNull(PulseCaptureEndpoint.endReason(47_360, true, 30_720))
        assertEquals(PulseCaptureEndReason.SILENCE,
            PulseCaptureEndpoint.endReason(48_640, true, 32_000))
    }

    @Test fun absentSpeechStillTimesOutAtFirstChunkPastOneAndHalfSeconds() {
        assertNull(PulseCaptureEndpoint.endReason(23_040, false, 0))
        assertEquals(PulseCaptureEndReason.NO_SPEECH,
            PulseCaptureEndpoint.endReason(24_320, false, 0))
    }

    @Test fun uninterruptedNoiseOrSpeechStillHasBoundedMemoryAndDuration() {
        assertNull(PulseCaptureEndpoint.endReason(478_720, true, 0))
        assertEquals(PulseCaptureEndReason.SAFETY_LIMIT,
            PulseCaptureEndpoint.endReason(480_000, true, 0))
    }

    @Test fun boundaryWithSilenceReportsNaturalEndRatherThanCeiling() {
        assertEquals(PulseCaptureEndReason.SILENCE,
            PulseCaptureEndpoint.endReason(480_000, true, 32_000))
    }
}
