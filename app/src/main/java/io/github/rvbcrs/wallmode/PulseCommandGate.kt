package io.github.rvbcrs.wallmode

/**
 * Keeps ambient conversation away from Pulse Core. The wall tablet may only
 * route speech that begins with an explicit command domain or is an approved
 * short command.
 */
internal object PulseCommandGate {
    private val approvedShorts = setOf(
        "time",
        "date",
        "status",
        "blackout",
        "black out",
        "introduce yourself",
        "introduce your self"
    )

    private val explicitDomain = Regex(
        "^(?:play|playlist|show|open|watch|launch|scene|see|seen|seem|set|question|chat|news|lookup|look\\s+up|search|research|verify|reason|think|move|join|remove|pause|resume|next|previous|volume)(?:\\s+|$)",
        RegexOption.IGNORE_CASE
    )
    private val switchedAction = Regex("^(?:turn|switch)\\s+(?:on|off)(?:\\s+|$)", RegexOption.IGNORE_CASE)
    private val regionalNews = Regex("^(?:thai|thailand)\\s+news(?:\\s+|$)", RegexOption.IGNORE_CASE)
    private val ambiguousVisualDomain = Regex("^(?:see|seen|seem)(?:\\s+|$)", RegexOption.IGNORE_CASE)

    fun accepts(value: String): Boolean {
        val normalized = value.trim()
            .replace(Regex("[.?!]+$"), "")
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return false
        if (normalized.lowercase() in approvedShorts) return true
        if (matchesExplicitCommand(normalized)) return true

        // Politeness is allowed only when what follows is independently a
        // valid command. It must never be repaired into the verb "play".
        val withoutPlease = normalized.replaceFirst(Regex("^please\\s+", RegexOption.IGNORE_CASE), "")
        return withoutPlease != normalized &&
            !ambiguousVisualDomain.containsMatchIn(withoutPlease) &&
            (withoutPlease.lowercase() in approvedShorts || matchesExplicitCommand(withoutPlease))
    }

    fun acceptsFuzzyWake(value: String): Boolean {
        val normalized = value.trim()
            .replace(Regex("[.?!]+$"), "")
            .replace(Regex("\\s+"), " ")
            .lowercase()
        return normalized in setOf("time", "date", "status")
    }

    private fun matchesExplicitCommand(value: String): Boolean =
        explicitDomain.containsMatchIn(value) ||
            switchedAction.containsMatchIn(value) ||
            regionalNews.containsMatchIn(value)
}
