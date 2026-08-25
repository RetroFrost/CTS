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
        assertEquals(0.0f, NativeTimeline.badgeTextProgress(0, 0, 34), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(0, 0, 35), 0.0001f)
        assertEquals(0.0f, NativeTimeline.badgeTextProgress(4, 0, 121), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(4, 0, 122), 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(4, 1, 122), 0.0001f)
        assertEquals(480, NativeArtwork.badgeWidth)
        assertEquals(430, NativeArtwork.badgeHeight)
    }

    @Test fun scrollingBadgesCanStartFullySettledWithoutChangingOpeningCards() {
        assertNull(NativeTimeline.badgeOffset(4, 0))
        assertEquals(0.0f, NativeTimeline.badgeOffset(4, 0, true) ?: -1f, 0.0001f)
        assertEquals(1.0f, NativeTimeline.badgeTextProgress(4, 0, 0, true), 0.0001f)
        assertEquals(0.0f, NativeTimeline.badgeTextProgress(0, 0, 0, true), 0.0001f)
    }

    @Test fun outroMatchesWorstThingsReferenceClocks() {
        assertEquals(540.0f, NativeTimeline.outroGroupX(0), 0.0001f)
        assertEquals(162.0f, NativeTimeline.outroGroupX(20), 0.0001f)
        assertEquals(0.0f, NativeTimeline.outroGroupX(60), 0.0001f)
        assertNull(NativeTimeline.outroActionBar(95))
        assertEquals(996.0f, NativeTimeline.outroActionBar(152)?.width ?: 0f, 0.0001f)
        assertEquals(1, NativeTimeline.outroActionState(252))
        assertEquals(2, NativeTimeline.outroActionState(312))
        assertEquals(3, NativeTimeline.outroActionState(372))
        assertEquals(494, NativeTimeline.outroFrames)
    }

    @Test fun fiftyCardReferenceEndsAtMeasuredFrame() {
        val project = StudioProject(cards = List(50) { StudioCard(title = "Card $it") })
        assertEquals(10_428, NativeTimeline.contentEnd(project))
        assertEquals(10_922, NativeTimeline.totalFrames(project))
        assertEquals(mapOf(0 to 0.0f, 1 to 477.0f, 2 to 954.0f, 3 to 1431.0f), NativeTimeline.positions(project, 528))
        assertEquals(-417.0f, NativeTimeline.positions(project, 900).getValue(1), 0.0001f)
        assertEquals(60.0f, NativeTimeline.positions(project, 900).getValue(2), 0.0001f)
        assertEquals(-2.0f, NativeTimeline.positions(project, 5000).getValue(21), 0.0001f)
        assertEquals(-168.0f, NativeTimeline.positions(project, 10_427).getValue(47), 0.0001f)
    }
}
