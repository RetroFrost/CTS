package io.github.retrofrost.cts.android.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardStripRecognitionTest {
    @Test
    fun findsHorizontalPanelsAndTheirSeparators() {
        val panelWidths = listOf(88, 104, 96)
        val separator = 3
        val width = panelWidths.sum() + separator * 2
        val height = 170
        val pixels = IntArray(width * height)
        val colors = intArrayOf(0xFF235FA4.toInt(), 0xFFD58B2A.toInt(), 0xFF4FAE72.toInt())
        var left = 0
        panelWidths.forEachIndexed { panelIndex, panelWidth ->
            for (y in 0 until height) {
                for (x in left until left + panelWidth) {
                    val detail = if ((x + y) % 19 < 8) 0x00060606 else 0
                    pixels[y * width + x] = colors[panelIndex] + detail
                }
            }
            left += panelWidth
            if (panelIndex < panelWidths.lastIndex) {
                for (y in 0 until height) {
                    for (x in left until left + separator) pixels[y * width + x] = 0xFF080808.toInt()
                }
                left += separator
            }
        }

        val recognition = CardStripRecognizer.recognize(
            argb = pixels,
            width = width,
            height = height,
            cardCount = 3,
            targetAspect = 0.56f,
        )

        assertEquals(StripAxis.Horizontal, recognition.selectedAxis)
        val candidate = recognition.horizontal
        assertTrue(kotlin.math.abs(candidate.dividerFractions[0] - 89.5f / width) < 0.025f)
        assertTrue(kotlin.math.abs(candidate.dividerFractions[1] - 196.5f / width) < 0.025f)
        assertTrue(candidate.separatorFraction * width in 2f..4f)
        assertTrue(candidate.confidence > 0.55f)
    }

    @Test
    fun doesNotInventWideSeparatorsAtAColorEdge() {
        val width = 300
        val height = 180
        val pixels = IntArray(width * height) { index ->
            when (index % width) {
                in 0 until 100 -> 0xFF224C91.toInt()
                in 100 until 200 -> 0xFFC7742C.toInt()
                else -> 0xFF3D9858.toInt()
            }
        }

        val candidate = CardStripRecognizer.recognize(pixels, width, height, 3, 0.56f).horizontal

        assertTrue(candidate.separatorFraction * width <= 1f)
    }

    @Test
    fun aThinInvalidAxisFallsBackWithoutBreakingTheValidAxis() {
        val width = 600
        val height = 8
        val pixels = IntArray(width * height) { 0xFF4477AA.toInt() }

        val recognition = CardStripRecognizer.recognize(pixels, width, height, 12, 0.6f)

        assertEquals(11, recognition.vertical.dividerFractions.size)
        assertTrue(recognition.vertical.dividerConfidences.all { it == 0f })
    }
}
