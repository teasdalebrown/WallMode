package io.github.rvbcrs.wallmode

internal enum class PulseCaptureEndReason { NO_SPEECH, SILENCE, SAFETY_LIMIT }

/** Silence ends normal requests; the ceiling only bounds an uninterrupted capture. */
internal object PulseCaptureEndpoint {
    const val SAMPLE_RATE = 16_000
    const val MAX_POST_WAKE_SAMPLES = SAMPLE_RATE * 30
    const val SPEECH_START_TIMEOUT_SAMPLES = SAMPLE_RATE * 3 / 2
    const val SILENCE_END_SAMPLES = SAMPLE_RATE * 2

    fun endReason(postWakeSamples: Int, speechStarted: Boolean, silenceSamples: Int): PulseCaptureEndReason? = when {
        !speechStarted && postWakeSamples >= SPEECH_START_TIMEOUT_SAMPLES -> PulseCaptureEndReason.NO_SPEECH
        speechStarted && silenceSamples >= SILENCE_END_SAMPLES -> PulseCaptureEndReason.SILENCE
        postWakeSamples >= MAX_POST_WAKE_SAMPLES -> PulseCaptureEndReason.SAFETY_LIMIT
        else -> null
    }
}
