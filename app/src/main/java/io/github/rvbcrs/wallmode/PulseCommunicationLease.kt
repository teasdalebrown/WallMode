package io.github.rvbcrs.wallmode

/** Reversible speech-turn lease; no routing preference or gain is changed. */
internal class PulseCommunicationLease(
    private val previousMode: Int,
    private val communicationMode: Int,
    private val setMode: (Int) -> Unit,
    private val setCapture: (Boolean, Boolean) -> Boolean,
    private val setSpeechVolumeTarget: (Boolean) -> Unit = {}
) {
    private var closed = false
    private var entered = false
    fun enter(): Boolean {
        if (closed) return false
        if (entered) return true
        return try {
            setMode(communicationMode)
            if (setCapture(true, true)) { setSpeechVolumeTarget(true); entered = true; true } else { close(true); false }
        } catch (_: Exception) { close(true); false }
    }

    fun close(resumeCapture: Boolean) {
        if (closed) return
        closed = true
        // Release the communication recorder before returning its route/mode.
        try { setCapture(false, false) } finally {
            try { setMode(previousMode) } finally {
                try { setSpeechVolumeTarget(false) } finally { if (resumeCapture) setCapture(false, true) }
            }
        }
    }
}
