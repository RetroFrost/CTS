package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel

data class CardContentFrames(
    val image: NormalizedRect,
    val title: NormalizedRect?,
    val description: NormalizedRect?,
)

/**
 * Pixel geometry measured from the canonical 1920 x 1080 Males reference video.
 * Empty text bands collapse and give the recovered space to the artwork.
 */
object CardContentLayout {
    private const val SOURCE_WIDTH = 480f
    private const val SOURCE_HEIGHT = 1080f

    private const val MALES_LEFT_PX = 9f
    private const val MALES_WIDTH_PX = 471f
    private const val MALES_IMAGE_HEIGHT_PX = 872f
    private const val MALES_TITLE_HEIGHT_PX = 93f
    private const val MALES_DESCRIPTION_HEIGHT_PX = 115f

    fun frames(model: VisualModel, card: CtsCard): CardContentFrames = malesFrames(card)

    fun artworkAspect(model: VisualModel): Float = MALES_WIDTH_PX / MALES_IMAGE_HEIGHT_PX

    fun bottomRule(): NormalizedRect = NormalizedRect(
        x = 0f,
        y = 1078f / SOURCE_HEIGHT,
        width = 1f,
        height = 2f / SOURCE_HEIGHT,
    )

    private fun malesFrames(card: CtsCard): CardContentFrames {
        val displayCard = card.withNormalizedText()
        val left = MALES_LEFT_PX / SOURCE_WIDTH
        val width = MALES_WIDTH_PX / SOURCE_WIDTH
        val titleHeight = MALES_TITLE_HEIGHT_PX / SOURCE_HEIGHT
        val descriptionHeight = MALES_DESCRIPTION_HEIGHT_PX / SOURCE_HEIGHT

        var cursor = 1f
        val description = if (displayCard.description.isNotEmpty()) {
            cursor -= descriptionHeight
            NormalizedRect(left, cursor, width, descriptionHeight)
        } else {
            null
        }
        val title = if (displayCard.title.isNotEmpty()) {
            cursor -= titleHeight
            NormalizedRect(left, cursor, width, titleHeight)
        } else {
            null
        }
        return CardContentFrames(
            image = NormalizedRect(left, 0f, width, cursor.coerceAtLeast(0f)),
            title = title,
            description = description,
        )
    }

}
