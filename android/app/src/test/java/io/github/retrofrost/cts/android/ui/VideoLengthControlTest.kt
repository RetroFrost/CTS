package io.github.retrofrost.cts.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLengthControlTest {
    @Test
    fun parsesMinuteSecondDuration() {
        assertEquals(97f, parseDurationInput("1:37")!!, 0.0001f)
    }

    @Test
    fun parsesPlainSeconds() {
        assertEquals(45f, parseDurationInput("45")!!, 0.0001f)
    }

    @Test
    fun rejectsInvalidSecondField() {
        assertNull(parseDurationInput("1:75"))
    }

    @Test
    fun formatsDurationForEditor() {
        assertEquals("1:37", formatDurationInput(97f))
    }
}
