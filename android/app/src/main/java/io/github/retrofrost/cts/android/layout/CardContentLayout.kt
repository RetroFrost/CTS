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
 * Pixel geometry measured from the supplied 1920 x 1080 source videos. Males keeps CTS's
 * optional-column behavior; Relationships keeps its fixed white/rule/description bands.
 */
object CardContentLayout {
    private const val SOURCE_WIDTH = 480f
    private const val SOURCE_HEIGHT = 1080f

    private const val MALES_LEFT_PX = 9f
    private const val MALES_WIDTH_PX = 471f
    private const val MALES_IMAGE_HEIGHT_PX = 872f
    private const val MALES_TITLE_HEIGHT_PX = 93f
    private const val MALES_DESCRIPTION_HEIGHT_PX = 115f

    private const val RELATIONSHIPS_WIDTH_PX = 475f
    private const val RELATIONSHIPS_IMAGE_HEIGHT_PX = 788f
    private const val RELATIONSHIPS_TITLE_HEIGHT_PX = 118f
    private const val RELATIONSHIPS_DESCRIPTION_TOP_PX = 916f
    private const val RELATIONSHIPS_DESCRIPTION_HEIGHT_PX = 164f
    private const val RELATIONSHIPS_RULE_TOP_PX = 906f
    private const val RELATIONSHIPS_RULE_HEIGHT_PX = 10f

    fun frames(model: VisualModel, card: CtsCard): CardContentFrames = when (model) {
        VisualModel.Males -> malesFrames(card)
        VisualModel.Relationships -> relationshipsFrames()
    }

    fun artworkAspect(model: VisualModel): Float = when (model) {
        VisualModel.Males -> MALES_WIDTH_PX / MALES_IMAGE_HEIGHT_PX
        VisualModel.Relationships -> RELATIONSHIPS_WIDTH_PX / RELATIONSHIPS_IMAGE_HEIGHT_PX
    }

    fun relationshipsRule(): NormalizedRect = NormalizedRect(
        x = 0f,
        y = RELATIONSHIPS_RULE_TOP_PX / SOURCE_HEIGHT,
        width = RELATIONSHIPS_WIDTH_PX / SOURCE_WIDTH,
        height = RELATIONSHIPS_RULE_HEIGHT_PX / SOURCE_HEIGHT,
    )

    fun bottomRule(): NormalizedRect = NormalizedRect(
        x = 0f,
        y = 1078f / SOURCE_HEIGHT,
        width = 1f,
        height = 2f / SOURCE_HEIGHT,
    )

    private fun malesFrames(card: CtsCard): CardContentFrames {
        val left = MALES_LEFT_PX / SOURCE_WIDTH
        val width = MALES_WIDTH_PX / SOURCE_WIDTH
        val titleHeight = MALES_TITLE_HEIGHT_PX / SOURCE_HEIGHT
        val descriptionHeight = MALES_DESCRIPTION_HEIGHT_PX / SOURCE_HEIGHT

        var cursor = 1f
        val description = if (card.description.isNotBlank()) {
            cursor -= descriptionHeight
            NormalizedRect(left, cursor, width, descriptionHeight)
        } else {
            null
        }
        val title = if (card.title.isNotBlank()) {
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

    private fun relationshipsFrames(): CardContentFrames = CardContentFrames(
        image = NormalizedRect(
            0f,
            0f,
            RELATIONSHIPS_WIDTH_PX / SOURCE_WIDTH,
            RELATIONSHIPS_IMAGE_HEIGHT_PX / SOURCE_HEIGHT,
        ),
        title = NormalizedRect(
            0f,
            RELATIONSHIPS_IMAGE_HEIGHT_PX / SOURCE_HEIGHT,
            RELATIONSHIPS_WIDTH_PX / SOURCE_WIDTH,
            RELATIONSHIPS_TITLE_HEIGHT_PX / SOURCE_HEIGHT,
        ),
        description = NormalizedRect(
            0f,
            RELATIONSHIPS_DESCRIPTION_TOP_PX / SOURCE_HEIGHT,
            RELATIONSHIPS_WIDTH_PX / SOURCE_WIDTH,
            RELATIONSHIPS_DESCRIPTION_HEIGHT_PX / SOURCE_HEIGHT,
        ),
    )
}
