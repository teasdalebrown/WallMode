package io.github.rvbcrs.wallmode

import java.util.concurrent.CancellationException

/** An optional cached wait cue yields to the first answer PCM, never a timer. */
internal class PulseSpeechHandover {
    private var cancelled = false
    private var answerText = false
    private var answerAudio = false
    private var cueToken: Any? = null
    private var stopCue: (() -> Unit)? = null

    @Synchronized fun textAvailable() { answerText = true }

    @Synchronized fun registerCue(token: Any, progress: Boolean = false, stop: () -> Unit): Boolean {
        if (cancelled || answerAudio || cueToken != null || progress != answerText) return false
        cueToken = token
        stopCue = stop
        return true
    }

    @Synchronized fun cueFinished(token: Any) {
        if (cueToken === token) { cueToken = null; stopCue = null }
    }

    @Synchronized fun audioReady() {
        if (cancelled) throw CancellationException("Voice turn cancelled")
        answerAudio = true
        stopCue?.invoke()
        stopCue = null
        cueToken = null
    }

    @Synchronized fun cancel() {
        cancelled = true
        stopCue?.invoke()
        stopCue = null
        cueToken = null
    }
}
