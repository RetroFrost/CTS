package io.github.retrofrost.cts.android.importer

import kotlin.math.abs
import kotlin.math.ln

enum class StripAxis { Horizontal, Vertical }

data class PixelRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class CardStripLayout(
    val axis: StripAxis,
    val regions: List<PixelRegion>,
)

/** Pure strip geometry, kept independent from Android so it can be tested locally. */
object CardStripGeometry {
    const val DEFAULT_SEPARATOR_PX = 2

    fun split(
        imageWidth: Int,
        imageHeight: Int,
        cardCount: Int,
        targetAspect: Float,
        separatorPx: Int = DEFAULT_SEPARATOR_PX,
    ): CardStripLayout {
        require(imageWidth > 0 && imageHeight > 0) { "The selected image has no readable dimensions." }
        require(cardCount > 0) { "Add at least one card before importing a card strip." }
        require(separatorPx >= 0) { "Separator size cannot be negative." }

        val horizontalPanelWidth = available(imageWidth, cardCount, separatorPx) / cardCount.toFloat()
        val verticalPanelHeight = available(imageHeight, cardCount, separatorPx) / cardCount.toFloat()
        val horizontalAspect = horizontalPanelWidth / imageHeight
        val verticalAspect = imageWidth / verticalPanelHeight
        val axis = if (
            aspectDistance(horizontalAspect, targetAspect) <= aspectDistance(verticalAspect, targetAspect)
        ) StripAxis.Horizontal else StripAxis.Vertical

        val regions = when (axis) {
            StripAxis.Horizontal -> splitAxis(imageWidth, imageHeight, cardCount, separatorPx, true)
            StripAxis.Vertical -> splitAxis(imageHeight, imageWidth, cardCount, separatorPx, false)
        }
        require(regions.all { it.width > 0 && it.height > 0 }) {
            "The sheet is too small for $cardCount cards and ${separatorPx}px separators."
        }
        return CardStripLayout(axis, regions)
    }

    private fun available(length: Int, count: Int, separatorPx: Int): Int =
        (length - separatorPx * (count - 1)).coerceAtLeast(1)

    private fun aspectDistance(actual: Float, target: Float): Float =
        abs(ln(actual.coerceAtLeast(0.0001f) / target.coerceAtLeast(0.0001f)))

    private fun splitAxis(
        primaryLength: Int,
        secondaryLength: Int,
        count: Int,
        separatorPx: Int,
        horizontal: Boolean,
    ): List<PixelRegion> {
        val usable = primaryLength - separatorPx * (count - 1)
        require(usable >= count) { "The sheet does not contain enough pixels for every card." }
        return List(count) { index ->
            val start = index * usable / count + index * separatorPx
            val end = (index + 1) * usable / count + index * separatorPx
            if (horizontal) PixelRegion(start, 0, end, secondaryLength)
            else PixelRegion(0, start, secondaryLength, end)
        }
    }
}
