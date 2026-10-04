package io.github.rvbcrs.wallmode

/** The frozen persona proof gate: native uint8, strict >204, three frames. */
internal fun pulsePersonaWakeAccepts(scores: List<Int>): Boolean =
    scores.size == 3 && scores.all { it in 0..255 } && scores.sum() > 612

/** Integer microWakeWord input transform used by the frozen native proofs. */
internal fun pulsePersonaFeatureQuantize(raw: Int): Int =
    ((raw.coerceIn(0, 65535) * 256 + 333) / 666 - 128).coerceIn(-128, 127)
