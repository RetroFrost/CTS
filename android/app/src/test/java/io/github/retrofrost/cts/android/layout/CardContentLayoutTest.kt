package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardContentLayoutTest {
    @Test
    fun fullCardKeepsCanonicalMalesBands() {
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
    fun emptyBandsGiveTheirSpaceBackToArtwork() {
        val titleOnly = CardContentLayout.frames(VisualModel.Males, CtsCard(title = "Title"))
        assertEquals(987f / 1080f, titleOnly.image.height, 0.0001f)
        assertNotNull(titleOnly.title)
        assertNull(titleOnly.description)

        val empty = CardContentLayout.frames(VisualModel.Males, CtsCard(title = " \n", description = "\t"))
        assertEquals(1f, empty.image.height, 0.0001f)
        assertNull(empty.title)
        assertNull(empty.description)
    }
}
