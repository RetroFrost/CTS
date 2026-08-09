package io.github.retrofrost.cts.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CtsProjectTest {
    @Test
    fun exactReferenceLocksNativeVideoFormat() {
        val project = CtsProject(
            modelMode = ModelMode.ExactReference,
            export = ExportSettings(width = 1280, height = 720, fps = 30),
        ).normalized()

        assertEquals(1920, project.export.width)
        assertEquals(1080, project.export.height)
        assertEquals(60, project.export.fps)
    }

    @Test
    fun customModeRetainsChosenVideoFormat() {
        val project = CtsProject(
            modelMode = ModelMode.Custom,
            export = ExportSettings(width = 1280, height = 720, fps = 30),
        ).normalized()

        assertEquals(1280, project.export.width)
        assertEquals(720, project.export.height)
        assertEquals(30, project.export.fps)
    }

    @Test
    fun imageRecognitionCropValuesAreNormalized() {
        val card = CtsCard(
            imageSubcard = ImageSubcard(
                parentCardId = "wrong-parent",
                cropFocusX = -2f,
                cropFocusY = Float.NaN,
                cropZoom = 9f,
            ),
        ).withOwnedImageSubcard()

        assertEquals(0f, card.imageSubcard.cropFocusX)
        assertEquals(0.5f, card.imageSubcard.cropFocusY)
        assertEquals(3f, card.imageSubcard.cropZoom)
        assertEquals(card.id, card.imageSubcard.parentCardId)
    }
}
