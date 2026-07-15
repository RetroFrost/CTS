package io.github.retrofrost.cts.android.ui

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class BatchArtworkTest {
    @Test
    fun assignsSourcesFromSelectedCardAndPreservesTransforms() {
        val firstTransform = NormalizedRect(0.1f, 0.2f, 0.7f, 0.6f)
        val secondTransform = NormalizedRect(0.05f, 0.1f, 0.8f, 0.75f)
        val project = CtsProject(
            cards = listOf(
                CtsCard(title = "One").let { card ->
                    card.copy(imageSubcard = card.imageSubcard.copy(transform = firstTransform))
                },
                CtsCard(title = "Two").let { card ->
                    card.copy(imageSubcard = card.imageSubcard.copy(transform = secondTransform))
                },
                CtsCard(title = "Three"),
            ),
        ).normalized()

        val updated = assignArtworkSources(
            project = project,
            sources = listOf("content://art/a", "content://art/b"),
            startIndex = 1,
        )

        assertEquals(null, updated.cards[0].imageSubcard.source)
        assertEquals("content://art/a", updated.cards[1].imageSubcard.source)
        assertEquals("content://art/b", updated.cards[2].imageSubcard.source)
        assertEquals(firstTransform, updated.cards[0].imageSubcard.transform)
        assertEquals(secondTransform, updated.cards[1].imageSubcard.transform)
    }

    @Test
    fun extraArtworkDoesNotCreateUnexpectedCards() {
        val project = CtsProject(cards = listOf(CtsCard(title = "Only"))).normalized()

        val updated = assignArtworkSources(
            project = project,
            sources = listOf("one", "two", "three"),
            startIndex = 0,
        )

        assertEquals(1, updated.cards.size)
        assertEquals("one", updated.cards.single().imageSubcard.source)
    }
}
