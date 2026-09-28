package io.github.rvbcrs.wallmode

internal data class PulseWakePhraseResult(
    val rawTranscript: String,
    val command: String,
    val verified: Boolean,
    val strong: Boolean = false
)

internal object PulseWakePhrase {
    private val establishedCommand = Regex(
        "\\b(?:play|playlist|show|scene|see|seen|seem|set|question|chat|news|lookup|look\\s+up|search|research|verify|reason|think|move|join|remove|pause|resume|next|previous|volume|status|time|date|blackout|black\\s+out|turn|switch)\\b",
        RegexOption.IGNORE_CASE
    )
    private val detectedWakePrefix = Regex(
        "^(?:[\\p{L}']+\\s+pulse|haypoles|paypost|they\\s+both|(?:take|play)\\s+false|hey[,]?\\s+(?:pulse|folks|post|pauls|polls|poles|holes)|(?:eight|8)\\s+(?:volts|polls|poles)|pause)[,.:;!?-]*\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val strongPrefix = Regex(
        "^(?:[\\p{L}']+\\s+pulse|haypoles|paypost|hey\\s+polls|hey\\s+holes)[,.:;!?-]*\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val fuzzyPrefix = Regex(
        "^(?:hey[,]?\\s+(?:both|folks|polls|poles|holes)|(?:eight|8)\\s+(?:volts|polls|poles)|pause)[,.:;!?-]*\\s+(time|date|status)$",
        RegexOption.IGNORE_CASE
    )
    private val strongWakeOnly = Regex(
        "^(?:[\\p{L}']+\\s+pulse|haypoles|paypost|hey\\s+polls|hey\\s+holes)[,.:;!?-]*$",
        RegexOption.IGNORE_CASE
    )
    fun parse(value: String): PulseWakePhraseResult {
        val raw = value.trim()
        val compact = raw.replace(Regex("[.?!]+$"), "").trim()
        val strongMatch = strongPrefix.matchEntire(compact)
        val fuzzyMatch = fuzzyPrefix.matchEntire(compact)
        return if (strongMatch != null) {
            PulseWakePhraseResult(raw, strongMatch.groupValues[1].trim(), verified = true, strong = true)
        } else if (fuzzyMatch != null) {
            PulseWakePhraseResult(raw, fuzzyMatch.groupValues[1].trim(), verified = true, strong = false)
        } else if (strongWakeOnly.matches(compact)) {
            PulseWakePhraseResult(raw, "", verified = true, strong = true)
        } else {
            PulseWakePhraseResult(raw, "", verified = false)
        }
    }

    /**
     * The local model has already established the wake event before this is
     * called. Strip only the observed STT renderings of that wake phrase and
     * leave command acceptance to Pulse Core's shared endpoint gate.
     */
    fun commandAfterDetectedWake(value: String): String {
        val raw = value.trim()
        val compact = raw.replace(Regex("[.?!]+$"), "").trim()
        detectedWakePrefix.matchEntire(compact)?.groupValues?.get(1)?.trim()?.let { return repairObservedCommandStart(it) }
        val command = establishedCommand.find(compact) ?: return raw
        val leadingWords = compact.substring(0, command.range.first)
            .split(Regex("\\s+"))
            .count { it.any(Char::isLetterOrDigit) }
        return if (leadingWords in 1..3) compact.substring(command.range.first).trim() else raw
    }

    private fun repairObservedCommandStart(value: String): String = value.replaceFirst(
        Regex("^late\\s+(?=(?:19|20)\\d0s?\\b)", RegexOption.IGNORE_CASE),
        "play "
    )
}
