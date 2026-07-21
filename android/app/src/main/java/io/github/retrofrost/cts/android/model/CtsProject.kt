package io.github.retrofrost.cts.android.model

import java.util.UUID

/**
 * The Android renderer is intentionally hierarchical:
 *
 * Timeline -> parent card -> child image subcard.
 *
 * An image subcard never owns timeline coordinates. Its transform is normalized inside
 * the image frame of exactly one parent card, so it cannot reveal early or follow the
 * complete card strip by accident.
 */
enum class VisualModel(
    val id: String,
    val label: String,
    val visibleCards: Int,
) {
    /**
     * CTS Android has one canonical design: the four-column illustrated comparison
     * supplied as the visual reference. Keep the historical `illustrated_cards` id so
     * Android and desktop project files continue to open without migration prompts.
     */
    Illustrated("illustrated_cards", "Reference Timeline", 4),
    ;

    companion object {
        /**
         * Every legacy model id is intentionally folded into the sole Android design.
         * Imported projects retain their data, images, transforms, and timing while the
         * old visual-model choice is discarded.
         */
        fun fromId(@Suppress("UNUSED_PARAMETER") id: String?): VisualModel = Illustrated
    }
}

data class NormalizedRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
) {
    fun clamped(minimumSize: Float = 0.08f): NormalizedRect {
        val safeWidth = width.coerceIn(minimumSize, 1f)
        val safeHeight = height.coerceIn(minimumSize, 1f)
        return copy(
            x = x.coerceIn(0f, 1f - safeWidth),
            y = y.coerceIn(0f, 1f - safeHeight),
            width = safeWidth,
            height = safeHeight,
        )
    }

    companion object {
        val Full = NormalizedRect()
    }
}

data class ImageSubcard(
    val id: String = UUID.randomUUID().toString(),
    val parentCardId: String,
    val source: String? = null,
    val transform: NormalizedRect = NormalizedRect.Full,
)

data class CtsCard(
    val id: String = UUID.randomUUID().toString(),
    val badgePrimary: String = "",
    val badgeSecondary: String = "",
    val title: String = "",
    val description: String = "",
    val imageSubcard: ImageSubcard = ImageSubcard(parentCardId = id),
) {
    fun withOwnedImageSubcard(subcard: ImageSubcard = imageSubcard): CtsCard =
        copy(imageSubcard = subcard.copy(parentCardId = id, transform = subcard.transform.clamped()))
}

data class CtsProject(
    val version: Int = 3,
    val name: String = "Untitled comparison",
    val model: VisualModel = VisualModel.Illustrated,
    val cards: List<CtsCard> = sampleCards(),
    /** Retained only for old project-file compatibility; the canonical badge is always shown. */
    val showHexagons: Boolean = true,
    val customDurationSeconds: Float? = null,
) {
    fun normalized(): CtsProject = copy(
        model = VisualModel.Illustrated,
        cards = cards.map { it.withOwnedImageSubcard() },
        showHexagons = true,
    )

    fun updateCard(cardId: String, update: (CtsCard) -> CtsCard): CtsProject = copy(
        cards = cards.map { card ->
            if (card.id == cardId) update(card).withOwnedImageSubcard() else card
        },
    )

    fun removeCard(cardId: String): CtsProject = copy(cards = cards.filterNot { it.id == cardId })

    fun duplicateCard(cardId: String): CtsProject {
        val index = cards.indexOfFirst { it.id == cardId }
        if (index < 0) return this
        val source = cards[index]
        val duplicateId = UUID.randomUUID().toString()
        val duplicate = source.copy(
            id = duplicateId,
            imageSubcard = source.imageSubcard.copy(
                id = UUID.randomUUID().toString(),
                parentCardId = duplicateId,
            ),
        )
        return copy(cards = cards.toMutableList().apply { add(index + 1, duplicate) })
    }

    fun addBlankCard(): CtsProject {
        val card = CtsCard(title = "New card")
        return copy(cards = cards + card)
    }
}

fun sampleCards(): List<CtsCard> = listOf(
    CtsCard(
        badgePrimary = "10",
        badgeSecondary = "SECONDS OLD",
        title = "Breathing",
        description = "A baby's first breath requires blood flow through the heart.",
    ),
    CtsCard(
        badgePrimary = "1",
        badgeSecondary = "HOUR OLD",
        title = "Suckling",
        description = "Newborns instinctively try to feed within just hours.",
    ),
    CtsCard(
        badgePrimary = "3",
        badgeSecondary = "DAYS OLD",
        title = "Recognizing Mom's Smell",
        description = "Within days a baby can recognize a familiar scent.",
    ),
    CtsCard(
        badgePrimary = "6.5",
        badgeSecondary = "MONTHS OLD",
        title = "Recognizing Their Own Name",
        description = "A baby turns toward their name months before speaking.",
    ),
    CtsCard(
        badgePrimary = "8",
        badgeSecondary = "MONTHS OLD",
        title = "Object Permanence",
        description = "Objects still exist even when they are out of sight.",
    ),
)