package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.NormalizedRect

data class CardContentFrames(
    val image: NormalizedRect,
    val title: NormalizedRect?,
    val description: NormalizedRect?,
)

/**
 * Empty text fields consume no card height. Remaining rows stay anchored to the bottom and
 * the artwork grows into every released slot, matching CTS's optional-column behavior.
 */
object CardContentLayout {
    private const val LEFT = 0.008f
    private const val WIDTH = 0.984f
    private const val CONTENT_BOTTOM = 0.996f
    private const val TITLE_HEIGHT = 0.088f
    private const val DESCRIPTION_HEIGHT = 0.101f

    fun frames(card: CtsCard): CardContentFrames {
        var cursor = CONTENT_BOTTOM
        val description = if (card.description.isNotBlank()) {
            cursor -= DESCRIPTION_HEIGHT
            NormalizedRect(LEFT, cursor, WIDTH, DESCRIPTION_HEIGHT)
        } else {
            null
        }
        val title = if (card.title.isNotBlank()) {
            cursor -= TITLE_HEIGHT
            NormalizedRect(LEFT, cursor, WIDTH, TITLE_HEIGHT)
        } else {
            null
        }
        return CardContentFrames(
            image = NormalizedRect(LEFT, 0f, WIDTH, cursor.coerceAtLeast(0f)),
            title = title,
            description = description,
        )
    }
}
