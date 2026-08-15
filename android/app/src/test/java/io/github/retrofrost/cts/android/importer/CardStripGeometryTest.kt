package io.github.retrofrost.cts.android.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStripGeometryTest {
    @Test
    fun horizontalMalesSheetDropsTwoPixelSeparators() {
        val layout = CardStripGeometry.split(
            imageWidth = 4 * 471 + 3 * 2,
            imageHeight = 872,
            cardCount = 4,
            targetAspect = 471f / 872f,
        )

        assertEquals(StripAxis.Horizontal, layout.axis)
        assertEquals(listOf(0, 473, 946, 1419), layout.regions.map { it.left })
        assertTrue(layout.regions.all { it.width == 471 && it.height == 872 })
    }

    @Test
    fun verticalCardSheetDropsTwoPixelSeparators() {
        val layout = CardStripGeometry.split(
            imageWidth = 475,
            imageHeight = 3 * 788 + 2 * 2,
            cardCount = 3,
            targetAspect = 475f / 788f,
        )

        assertEquals(StripAxis.Vertical, layout.axis)
        assertEquals(listOf(0, 790, 1580), layout.regions.map { it.top })
        assertTrue(layout.regions.all { it.width == 475 && it.height == 788 })
    }

    @Test
    fun remainderPixelsAreDistributedWithoutOverlap() {
        val layout = CardStripGeometry.split(
            imageWidth = 1001,
            imageHeight = 450,
            cardCount = 4,
            targetAspect = 0.55f,
            separatorPx = 0,
        )

        assertEquals(StripAxis.Horizontal, layout.axis)
        assertEquals(1001, layout.regions.sumOf { it.width })
        layout.regions.zipWithNext().forEach { (left, right) -> assertEquals(left.right, right.left) }
    }

    @Test
    fun detectedOrientationCanBeOverriddenBeforeImport() {
        val layout = CardStripGeometry.split(
            imageWidth = 4 * 471 + 3 * 2,
            imageHeight = 872,
            cardCount = 4,
            targetAspect = 471f / 872f,
            axisOverride = StripAxis.Vertical,
        )

        assertEquals(StripAxis.Vertical, layout.axis)
        assertEquals(4, layout.regions.size)
        assertTrue(layout.regions.all { it.width == layout.regions.first().width })
    }

    @Test
    fun unevenDetectedDividersProduceIndependentCardRegions() {
        val layout = CardStripGeometry.fromDividers(
            imageWidth = 1_000,
            imageHeight = 600,
            axis = StripAxis.Horizontal,
            dividerFractions = listOf(0.28f, 0.67f),
            separatorPx = 4,
        )

        assertEquals(listOf(278, 386, 328), layout.regions.map { it.width })
        assertEquals(listOf(0, 282, 672), layout.regions.map { it.left })
        assertEquals(3, layout.regions.size)
    }

    @Test
    fun safeSeparatorLimitProtectsTheSmallestDetectedPanel() {
        assertEquals(
            49,
            CardStripGeometry.maximumSafeSeparator(1_000, listOf(0.05f, 0.67f)),
        )
        assertEquals(0, CardStripGeometry.maximumSafeSeparator(1_000, emptyList()))
    }
}
