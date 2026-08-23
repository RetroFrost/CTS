package io.github.retrofrost.cts.android

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
}
