package io.github.rvbcrs.wallmode

/** Playback and turn transitions invalidate queued acoustic detections. */
internal class PulseAcousticStopGate {
    private val players = mutableMapOf<Any, Boolean>()
    private var epoch = 0L
    @Synchronized fun playback(token: Any, active: Boolean, spokenText: String? = null) {
        // Unknown audio and assistant Stop words remain suppressed. Ordinary
        // known speech must not disable the very interruption it needs.
        val changed = if (active) {
            val suppress = spokenText.isNullOrBlank() || STOP_LIKE.containsMatchIn(spokenText)
            val previous = players.put(token, suppress)
            previous != suppress
        } else players.remove(token) != null
        if (changed) epoch++
    }
    @Synchronized fun invalidate() { epoch++ }
    @Synchronized fun allowed(): Boolean = players.values.none { it }
    @Synchronized fun detectionEpoch(): Long = epoch
    @Synchronized fun accepts(detectionEpoch: Long): Boolean = allowed() && epoch == detectionEpoch
    private companion object {
        val STOP_LIKE = Regex("\\b(?:stop\\w*|nonstop)\\b", RegexOption.IGNORE_CASE)
    }
}
