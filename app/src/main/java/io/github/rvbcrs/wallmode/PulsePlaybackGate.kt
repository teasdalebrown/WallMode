package io.github.rvbcrs.wallmode

import java.util.concurrent.CancellationException

/** Stop and the first hardware play share one lock, including the play call. */
internal class PulsePlaybackGate {
    @Volatile var cancelled = false
        private set
    @Volatile var hasStarted = false
        private set

    @Synchronized fun checkActive() {
        if (cancelled) throw CancellationException("Speech playback cancelled")
    }

    @Synchronized fun start(play: () -> Unit) {
        checkActive()
        play()
        hasStarted = true
    }

    @Synchronized fun cancel(stop: () -> Unit) {
        cancelled = true
        stop()
    }
}
