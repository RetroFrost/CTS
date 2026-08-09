package io.github.retrofrost.cts.android.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaPackImporterTest {
    @Test
    fun safeRelativeMediaPathsAreAccepted() {
        assertEquals("images/card-001.png", MegaPackImporter.safeEntryReference("./images/card-001.png"))
        assertEquals("audio/theme.mp3", MegaPackImporter.safeEntryReference("audio/theme.mp3"))
    }

    @Test
    fun traversalAndAbsolutePathsAreRejected() {
        listOf(
            "../secret.png",
            "images/../../secret.png",
            "/absolute/card.png",
            "C:\\cards\\one.png",
            "images//card.png",
        ).forEach { unsafe ->
            assertTrue(runCatching { MegaPackImporter.safeEntryReference(unsafe) }.isFailure)
        }
    }
}
