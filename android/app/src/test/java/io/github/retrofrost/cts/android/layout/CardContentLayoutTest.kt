package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardContentLayoutTest {
    @Test
    fun fullCardKeepsCanonicalFrames() {
        val frames = CardContentLayout.frames(
            VisualModel.Males,
            CtsCard(title = "Title", description = "Description"),
        )
        assertEquals(9f / 480f, frames.image.x, 0.0001f)
        assertEquals(471f / 480f, frames.image.width, 0.0001f)
        assertEquals(872f / 1080f, frames.image.height, 0.0001f)
        assertEquals(872f / 1080f, frames.title!!.y, 0.0001f)
        assertEquals(965f / 1080f, frames.description!!.y, 0.0001f)
    }

    @Test
    fun missingDescriptionGivesItsSpaceToArtwork() {
        val frames = CardContentLayout.frames(VisualModel.Males, CtsCard(title = "Title", description = ""))
        assertEquals(987f / 1080f, frames.image.height, 0.0001f)
        assertEquals(987f / 1080f, frames.title!!.y, 0.0001f)
        assertNull(frames.description)
    }

    @Test
    fun missingTitleGivesItsSpaceToArtwork() {
        val frames = CardContentLayout.frames(VisualModel.Males, CtsCard(title = "", description = "Description"))
        assertEquals(965f / 1080f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNotNull(frames.description)
    }

    @Test
    fun missingTextLetsArtworkFillTheCard() {
        val frames = CardContentLayout.frames(VisualModel.Males, CtsCard(title = "", description = ""))
        assertEquals(1f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNull(frames.description)
    }

    @Test
    fun whitespaceOnlyFieldsDoNotReserveTextBands() {
        val frames = CardContentLayout.frames(
            VisualModel.Males,
            CtsCard(title = " \n\t ", description = "   "),
        )

        assertEquals(1f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNull(frames.description)
    }

    @Test
    fun relationshipsUsesMeasuredReferenceBands() {
        val frames = CardContentLayout.frames(
            VisualModel.Relationships,
            CtsCard(title = "First Love", description = "Description"),
        )

        assertEquals(475f / 480f, frames.image.width, 0.0001f)
        assertEquals(788f / 1080f, frames.image.height, 0.0001f)
        assertEquals(788f / 1080f, frames.title!!.y, 0.0001f)
        assertEquals(118f / 1080f, frames.title!!.height, 0.0001f)
        assertEquals(916f / 1080f, frames.description!!.y, 0.0001f)
        assertEquals(164f / 1080f, frames.description!!.height, 0.0001f)
        assertEquals(906f / 1080f, CardContentLayout.relationshipsRule(CtsCard(description = "Description"))!!.y, 0.0001f)
        assertEquals(10f / 1080f, CardContentLayout.relationshipsRule(CtsCard(description = "Description"))!!.height, 0.0001f)
    }

    @Test
    fun relationshipsEmptyFieldsGiveTheirSpaceToArtwork() {
        val empty = CardContentLayout.frames(
            VisualModel.Relationships,
            CtsCard(title = " \n", description = "\t"),
        )
        assertEquals(1f, empty.image.height, 0.0001f)
        assertNull(empty.title)
        assertNull(empty.description)
        assertNull(CardContentLayout.relationshipsRule(CtsCard()))

        val titleOnly = CardContentLayout.frames(
            VisualModel.Relationships,
            CtsCard(title = "Seawater", description = ""),
        )
        assertEquals(962f / 1080f, titleOnly.image.height, 0.0001f)
        assertNotNull(titleOnly.title)
        assertNull(titleOnly.description)
    }
}
