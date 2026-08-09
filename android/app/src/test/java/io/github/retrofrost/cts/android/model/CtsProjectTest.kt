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
}
