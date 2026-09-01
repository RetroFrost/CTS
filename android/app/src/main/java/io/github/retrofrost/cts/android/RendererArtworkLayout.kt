package io.github.retrofrost.cts.android

/**
 * Single source of truth for card artwork bounds.
 *
 * The renderer owns the canonical image frame through [RendererSpec.imageHeight].
 * Empty title/description bands may be reclaimed by the artwork, but the app must
 * never invent a fixed 471×N image frame independently of the renderer spec.
 */
internal object RendererArtworkLayout {
    fun imageBottom(card: StudioCard, spec: RendererSpec): Float {
        val referenceHeight = spec.referenceHeight.coerceAtLeast(1).toFloat()
        val canonicalDescriptionHeight = (referenceHeight - spec.descriptionTop).coerceAtLeast(0f)
        var bottom = spec.imageHeight.coerceAtLeast(1f)
        if (card.title.isBlank()) bottom += spec.titleHeight.coerceAtLeast(0f)
        if (card.description.isBlank()) bottom += canonicalDescriptionHeight
        return bottom.coerceIn(1f, referenceHeight)
    }
}
