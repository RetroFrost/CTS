package io.github.retrofrost.cts.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalArchitectureTest {
    @Test
    fun releaseIdentityIsTwoPointZeroPointOneHotfix() {
        assertEquals("2.0.1", BuildConfig.VERSION_NAME)
        assertEquals(20001, BuildConfig.VERSION_CODE)
    }

    @Test
    fun projectDefaultsToTheVerifiedReferenceContract() {
        val project = StudioProject()
        assertEquals(1920, project.width)
        assertEquals(1080, project.height)
        assertEquals(60, project.fps)
        assertTrue(project.showBadges)
        assertTrue(project.creditsEnabled)
        assertFalse(project.cards.isEmpty())
    }

    @Test
    fun cardModelKeepsReferenceBadgeValuesVerbatim() {
        val card = StudioCard(title = "Reference", value = "7M YEARS AGO")
        assertEquals("7M YEARS AGO", card.value)
    }
}
