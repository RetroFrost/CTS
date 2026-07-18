package io.github.retrofrost.cts.android.model

/**
 * Wrap CTS badge labels before visual measurement.
 *
 * Long semantic words such as HEMISPHERES stay intact and receive their own line instead
 * of being shortened with an ellipsis. The Compose and Canvas renderers share this text.
 */
fun formatBadgeText(
    primary: String,
    secondary: String,
    targetLineLength: Int = 12,
    maximumSecondaryLines: Int = 3,
): String {
    val lines = mutableListOf<String>()
    primary.trim().takeIf(String::isNotBlank)?.let(lines::add)

    val words = secondary.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (words.isNotEmpty()) {
        val wrapped = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (current.isNotBlank() && candidate.length > targetLineLength) {
                wrapped += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotBlank()) wrapped += current

        if (wrapped.size <= maximumSecondaryLines) {
            lines += wrapped
        } else {
            lines += wrapped.take(maximumSecondaryLines - 1)
            lines += wrapped.drop(maximumSecondaryLines - 1).joinToString(" ")
        }
    }

    return lines.joinToString("\n")
}
