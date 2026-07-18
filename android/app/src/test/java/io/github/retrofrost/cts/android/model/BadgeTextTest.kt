package io.github.retrofrost.cts.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BadgeTextTest {
    @Test
    fun keepsLongWordsWholeOnTheirOwnLine() {
        val text = formatBadgeText("10.0/10", "FOUR HEMISPHERES")

        assertEquals("10.0/10\nFOUR\nHEMISPHERES", text)
        assertFalse(text.contains("…"))
    }

    @Test
    fun wrapsMultiwordLabelsWithoutSplittingWords() {
        val text = formatBadgeText("9.4/10", "COUNTRY IN A CITY")

        assertEquals("9.4/10\nCOUNTRY IN A\nCITY", text)
    }
}
