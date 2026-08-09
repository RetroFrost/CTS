package io.github.retrofrost.cts.android.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardImageRecognitionTest {
    @Test
    fun flagsBlankAndDuplicateTiles() {
        val blank = CardRaster(48, 48, IntArray(48 * 48) { 0xFFFFFFFF.toInt() })
        val detailedPixels = IntArray(48 * 48) { index ->
            val x = index % 48
            val y = index / 48
            if ((x / 6 + y / 6) % 2 == 0) 0xFF123C88.toInt() else 0xFFE8A82C.toInt()
        }
        val detailed = CardRaster(48, 48, detailedPixels)
        val duplicate = CardRaster(48, 48, detailedPixels.copyOf())

        val analyses = CardImageRecognizer.analyze(listOf(blank, detailed, duplicate))

        assertTrue(analyses[0].blank)
        assertTrue(!analyses[1].blank)
        assertEquals(1, analyses[2].duplicateOf)
    }

    @Test
    fun focusMovesTowardHighContrastContent() {
        val pixels = IntArray(120 * 80) { 0xFF777777.toInt() }
        for (y in 12 until 68) {
            for (x in 88 until 116) {
                pixels[y * 120 + x] = if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }

        val analysis = CardImageRecognizer.analyze(listOf(CardRaster(120, 80, pixels))).single()

        assertTrue(analysis.suggestedFocusX > 0.58f)
        assertTrue(analysis.suggestedZoom >= 1f)
    }
}
