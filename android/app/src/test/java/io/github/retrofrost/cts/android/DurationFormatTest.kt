package dev.infinitycomparison.cc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationFormatTest {
    @Test fun parsesMinutesAndSeconds() {
        assertEquals(90.0, DurationFormat.parse("01:30") ?: 0.0, 0.0)
    }

    @Test fun rejectsInvalidSeconds() {
        assertNull(DurationFormat.parse("01:99"))
    }

    @Test fun formatsDuration() {
        assertEquals("02:05", DurationFormat.format(125.8))
    }

    @Test fun badgeShineKeepsMeasuredIndependentClocks() {
        assertEquals(0.0f, NativeTimeline.badgeShineProgress(0, 108) ?: -1f, 0.0001f)
        assertNull(NativeTimeline.badgeShineProgress(0, 133))
        assertEquals(0.0f, NativeTimeline.badgeShineProgress(4, 208) ?: -1f, 0.0001f)
        assertNull(NativeTimeline.badgeShineProgress(4, 241))
    }
}
