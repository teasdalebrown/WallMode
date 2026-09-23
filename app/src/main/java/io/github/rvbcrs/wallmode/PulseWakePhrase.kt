package io.github.rvbcrs.wallmode

internal data class PulseWakePhraseResult(
    val rawTranscript: String,
    val command: String,
    val verified: Boolean,
    val strong: Boolean = false
)

internal object PulseWakePhrase {
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
}
