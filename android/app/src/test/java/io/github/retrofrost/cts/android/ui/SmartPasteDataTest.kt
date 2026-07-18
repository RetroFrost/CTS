package io.github.retrofrost.cts.android.ui

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPasteDataTest {
    @Test
    fun unwrapsGoogleImageRedirects() {
        val source = normalizeArtworkSource(
            "https://www.google.com/imgres?imgurl=https%3A%2F%2Fexample.com%2Fkiribati.png&imgrefurl=https%3A%2F%2Fexample.com",
        )

        assertEquals("https://example.com/kiribati.png", source)
    }

    @Test
    fun imageColumnImportsArtworkAndKeepsTransforms() {
        val transform = NormalizedRect(0.1f, 0.1f, 0.8f, 0.8f)
        val existing = CtsCard(title = "Old").let { card ->
            card.copy(imageSubcard = card.imageSubcard.copy(transform = transform))
        }
        val table = "Value\tLabel\tTitle\tDescription\tImage\n" +
            "10/10\tFOUR HEMISPHERES\tKiribati\tPacific islands\thttps://example.com/kiribati.jpg"

        val parsed = parseSmartCards(table, listOf(existing))

        assertEquals("https://example.com/kiribati.jpg", parsed.single().imageSubcard.source)
        assertEquals(transform, parsed.single().imageSubcard.transform)
    }

    @Test
    fun copiedArtworkFillsCardsFromSelectedIndex() {
        val cards = listOf(CtsCard(title = "A"), CtsCard(title = "B"), CtsCard(title = "C"))

        val updated = applyArtworkToCards(cards, listOf("one", "two"), 1)

        assertEquals(null, updated[0].imageSubcard.source)
        assertEquals("one", updated[1].imageSubcard.source)
        assertEquals("two", updated[2].imageSubcard.source)
    }
}
