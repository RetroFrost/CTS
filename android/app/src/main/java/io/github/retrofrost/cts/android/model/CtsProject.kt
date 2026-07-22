package io.github.retrofrost.cts.android.model

import io.github.retrofrost.cts.android.shared.SHARED_SAMPLE_CARDS
import io.github.retrofrost.cts.android.shared.SharedContract
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
    /** The sole model is generated from the same contract as CTS desktop. */
    Illustrated(
        SharedContract.MODEL_ID,
        SharedContract.MODEL_LABEL,
        SharedContract.VISIBLE_CARDS,
    ),
    ;

    companion object {
        /** Every historical model id is intentionally folded into the shared design. */
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
    val version: Int = SharedContract.PROJECT_VERSION,
    val name: String = "Untitled comparison",
    val model: VisualModel = VisualModel.Illustrated,
    val cards: List<CtsCard> = sampleCards(),
    /** Retained only for old project-file compatibility; the canonical badge is always shown. */
    val showHexagons: Boolean = true,
    val customDurationSeconds: Float? = null,
) {
    fun normalized(): CtsProject = copy(
        version = SharedContract.PROJECT_VERSION,
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

fun sampleCards(): List<CtsCard> = SHARED_SAMPLE_CARDS.map { sample ->
    CtsCard(
        badgePrimary = sample.badgePrimary,
        badgeSecondary = sample.badgeSecondary,
        title = sample.title,
        description = sample.description,
    )
}
