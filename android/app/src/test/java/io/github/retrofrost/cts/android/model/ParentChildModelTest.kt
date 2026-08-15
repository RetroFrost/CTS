package io.github.retrofrost.cts.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentChildModelTest {
    @Test
    fun everyImageSubcardIsNormalizedBackToItsOwningParent() {
        val wrongParent = CtsCard(
            id = "parent-a",
            imageSubcard = ImageSubcard(
                parentCardId = "some-other-card",
                transform = NormalizedRect(-1f, 2f, 4f, 4f),
            ),
        )

        val normalized = CtsProject(cards = listOf(wrongParent)).normalized().cards.single()

        assertEquals(normalized.id, normalized.imageSubcard.parentCardId)
        assertEquals(NormalizedRect.Full, normalized.imageSubcard.transform)
    }

    @Test
    fun duplicatingAParentCreatesANewOwnedImageChild() {
        val project = CtsProject(cards = listOf(CtsCard(title = "Original")))
        val original = project.cards.single()

        val duplicated = project.duplicateCard(original.id)

        assertEquals(2, duplicated.cards.size)
        val copy = duplicated.cards[1]
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.imageSubcard.id, copy.imageSubcard.id)
        assertEquals(copy.id, copy.imageSubcard.parentCardId)
    }

    @Test
    fun replacingImageSourceDoesNotResetItsTransform() {
        val transformed = NormalizedRect(0.2f, 0.1f, 0.6f, 0.8f)
        val card = CtsCard(
            imageSubcard = ImageSubcard(
                parentCardId = "temporary",
                source = "old.png",
                transform = transformed,
            ),
        ).withOwnedImageSubcard()

        val replaced = card.copy(
            imageSubcard = card.imageSubcard.copy(source = "new.png"),
        ).withOwnedImageSubcard()

        assertEquals("new.png", replaced.imageSubcard.source)
        assertEquals(transformed, replaced.imageSubcard.transform)
        assertTrue(replaced.imageSubcard.parentCardId == replaced.id)
    }
}
