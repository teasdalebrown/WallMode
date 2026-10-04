package io.github.rvbcrs.wallmode

/** Cues never become answer text. One completed answer owns one TTS request. */
internal class PulseQuestionDelivery {
    private val chunks = mutableListOf<String>()
    var answer = ""
        private set
    var finished = false
        private set

    fun chunk(text: String) {
        check(!finished) { "Answer chunk arrived after completion" }
        text.trim().takeIf { it.isNotBlank() }?.let(chunks::add)
    }

    fun finish(completeAnswer: String?) {
        check(!finished) { "Duplicate question completion" }
        answer = completeAnswer?.trim()?.takeIf { it.isNotBlank() }
            ?: chunks.joinToString(" ")
        finished = true
    }

    fun speech(): String {
        check(finished) { "Question stream ended before completion" }
        // Prefer the actual streamed speech over companion metadata.
        return chunks.joinToString(" ").ifBlank { answer }
    }
}
