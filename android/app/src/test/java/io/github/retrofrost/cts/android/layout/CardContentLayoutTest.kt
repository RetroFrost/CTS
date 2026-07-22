package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardContentLayoutTest {
    @Test
    fun fullCardKeepsCanonicalFrames() {
        val frames = CardContentLayout.frames(CtsCard(title = "Title", description = "Description"))
        assertEquals(0.807f, frames.image.height, 0.0001f)
        assertEquals(0.807f, frames.title!!.y, 0.0001f)
        assertEquals(0.895f, frames.description!!.y, 0.0001f)
    }

    @Test
    fun missingDescriptionGivesItsSpaceToArtwork() {
        val frames = CardContentLayout.frames(CtsCard(title = "Title", description = ""))
        assertEquals(0.908f, frames.image.height, 0.0001f)
        assertEquals(0.908f, frames.title!!.y, 0.0001f)
        assertNull(frames.description)
    }

    @Test
    fun missingTitleGivesItsSpaceToArtwork() {
        val frames = CardContentLayout.frames(CtsCard(title = "", description = "Description"))
        assertEquals(0.895f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNotNull(frames.description)
    }

    @Test
    fun missingTextLetsArtworkFillTheCard() {
        val frames = CardContentLayout.frames(CtsCard(title = "", description = ""))
        assertEquals(0.996f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNull(frames.description)
    }
}
