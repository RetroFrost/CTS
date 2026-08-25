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

    @Test fun openingBadgeShineFinishesBeforeTheNextCardEntrance() {
        assertEquals(0.0f, NativeTimeline.badgeShineProgress(0, 95) ?: -1f, 0.0001f)
        assertEquals(24.0f / 25.0f, NativeTimeline.badgeShineProgress(0, 119) ?: -1f, 0.0001f)
        assertNull(NativeTimeline.badgeShineProgress(0, 120))

        // Scrolling cards retain their independently measured shine clock.
        assertEquals(0.0f, NativeTimeline.badgeShineProgress(4, 208) ?: -1f, 0.0001f)
        assertNull(NativeTimeline.badgeShineProgress(4, 241))
    }

    @Test fun badgeTextIsPresentFromTheBadgesFirstVisibleFrame() {
        assertEquals(0.0f, NativeTimeline.badgeTextProgress(0, 0, -1), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(0, 0, 0), 0.0001f)
        assertEquals(0.0f, NativeTimeline.badgeTextProgress(4, 0, 121), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(4, 0, 122), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(4, 1, 122), 0.0001f)
        assertEquals(325, NativeArtwork.badgeWidth)
        assertEquals(375, NativeArtwork.badgeHeight)
    }

    @Test fun scrollingBadgesCanStartFullySettledWithoutChangingOpeningCards() {
        assertNull(NativeTimeline.badgeOffset(4, 0))
        assertEquals(0.0f, NativeTimeline.badgeOffset(4, 0, true) ?: -1f, 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(4, 0, 0, true), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(0, 0, 0, true), 0.0001f)
    }

    @Test fun outroKeepsMeasuredWipeAndRiseClocks() {
        assertEquals(0.0f, NativeTimeline.outroCoverY(9), 0.0001f)
        assertEquals(28.0f, NativeTimeline.outroCoverY(10), 0.0001f)
        assertEquals(1080.0f, NativeTimeline.outroCoverY(26), 0.0001f)
        assertEquals(-210.0f, NativeTimeline.outroGroupTop(43) ?: 0f, 0.0001f)
        assertEquals(0.0f, NativeTimeline.outroGroupTop(53) ?: -1f, 0.0001f)
        assertNull(NativeTimeline.outroActionBar(53))
        assertEquals(540.0f, NativeTimeline.outroActionBar(102)?.width ?: 0f, 0.0001f)
        assertEquals(185.0f, NativeTimeline.outroSubscribe(102)?.width ?: 0f, 0.0001f)
    }
}
