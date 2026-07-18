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
    Reference("reference_detail", "Reference Detail", 4),
    Illustrated("illustrated_cards", "Illustrated Cards", 3),
    Compact("classic_compact", "Classic Compact", 4),
    ;

    companion object {
        fun fromId(id: String?): VisualModel = entries.firstOrNull { it.id == id } ?: Reference
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

/** One global soundtrack synchronized to the comparison timeline. */
data class CtsSoundtrack(
    val source: String,
    val displayName: String = "Soundtrack",
    val volume: Float = 1f,
    val loop: Boolean = true,
) {
    fun normalized(): CtsSoundtrack = copy(
        displayName = displayName.ifBlank { "Soundtrack" },
        volume = volume.coerceIn(0f, 1f),
    )
}

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
    val model: VisualModel = VisualModel.Reference,
    val cards: List<CtsCard> = sampleCards(),
    val showHexagons: Boolean = true,
    val customDurationSeconds: Float? = null,
    val soundtrack: CtsSoundtrack? = null,
) {
    fun normalized(): CtsProject = copy(
        cards = cards.map { it.withOwnedImageSubcard() },
        soundtrack = soundtrack?.normalized(),
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
        badgePrimary = "2008",
        title = "Android 1.0",
        description = "The first commercial Android release.",
    ),
    CtsCard(
        badgePrimary = "2011",
        title = "Android 4.0",
        description = "Phones and tablets met under the Holo design language.",
    ),
    CtsCard(
        badgePrimary = "2014",
        title = "Android 5.0",
        description = "Material Design introduced depth, motion, and bold color.",
    ),
    CtsCard(
        badgePrimary = "2021",
        title = "Android 12",
        description = "Material You made the interface react to the wallpaper.",
    ),
    CtsCard(
        badgePrimary = "2025",
        title = "Android 16",
        description = "A modern Android generation built around adaptive experiences.",
    ),
)
