package io.github.rvbcrs.wallmode

internal enum class PulseCaptureEndReason { NO_SPEECH, SILENCE, SAFETY_LIMIT }

/** Silence ends normal requests; the ceiling only bounds an uninterrupted capture. */
internal object PulseCaptureEndpoint {
    const val SAMPLE_RATE = 16_000
    const val MAX_POST_WAKE_SAMPLES = SAMPLE_RATE * 30
    const val SPEECH_START_TIMEOUT_SAMPLES = SAMPLE_RATE * 3 / 2
    const val SILENCE_END_SAMPLES = SAMPLE_RATE * 2

    fun endReason(postWakeSamples: Int, speechStarted: Boolean, silenceSamples: Int, chat: Boolean = false): PulseCaptureEndReason? = when {
        !speechStarted && postWakeSamples >= (if (chat) SAMPLE_RATE * 15 else SPEECH_START_TIMEOUT_SAMPLES) -> PulseCaptureEndReason.NO_SPEECH
        speechStarted && silenceSamples >= (if (chat) SAMPLE_RATE * 5 else SILENCE_END_SAMPLES) -> PulseCaptureEndReason.SILENCE
        postWakeSamples >= MAX_POST_WAKE_SAMPLES -> PulseCaptureEndReason.SAFETY_LIMIT
        else -> null
    }
}
