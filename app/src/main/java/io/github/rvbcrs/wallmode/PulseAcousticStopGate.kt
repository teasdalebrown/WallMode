package io.github.rvbcrs.wallmode

/** Playback and turn transitions invalidate queued acoustic detections. */
internal class PulseAcousticStopGate {
    private val players = mutableSetOf<Any>()
    private var epoch = 0L
    @Synchronized fun playback(token: Any, active: Boolean) {
        val changed = if (active) players.add(token) else players.remove(token)
        if (changed) epoch++
    }
    @Synchronized fun invalidate() { epoch++ }
    @Synchronized fun allowed(): Boolean = players.isEmpty()
    @Synchronized fun detectionEpoch(): Long = epoch
    @Synchronized fun accepts(detectionEpoch: Long): Boolean = players.isEmpty() && epoch == detectionEpoch
}
