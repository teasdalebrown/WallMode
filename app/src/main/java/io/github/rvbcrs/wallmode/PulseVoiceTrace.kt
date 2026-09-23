package io.github.rvbcrs.wallmode

import android.content.Context
import java.io.File
import java.time.Instant

/** A small local lifecycle trace. It records state and transcripts, never audio. */
internal class PulseVoiceTrace(context: Context) {
    private val traceFile = File(context.filesDir, "pulse_voice_trace.log")

    @Synchronized
    fun record(event: String, detail: String = "") {
        if (traceFile.length() > MAX_BYTES) {
            val previous = File(traceFile.parentFile, "pulse_voice_trace.previous.log")
            previous.delete()
            traceFile.renameTo(previous)
        }
        val safeDetail = detail.replace('\n', ' ').replace('\r', ' ').take(MAX_DETAIL_CHARS)
        traceFile.appendText("${Instant.now()}\t$event\t$safeDetail\n")
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024L
        const val MAX_DETAIL_CHARS = 2_000
    }
}
