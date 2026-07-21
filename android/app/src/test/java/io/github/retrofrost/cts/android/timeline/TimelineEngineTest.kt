package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {
    @Test
    fun androidExposesOnlyTheCanonicalFourCardModel() {
        assertEquals(listOf(VisualModel.Illustrated), VisualModel.entries)
        assertEquals(4, VisualModel.Illustrated.visibleCards)
        assertEquals(VisualModel.Illustrated, VisualModel.fromId("reference_detail"))
        assertEquals(VisualModel.Illustrated, VisualModel.fromId("classic_compact"))
    }

    @Test
    fun automaticDurationIncludesTheReferenceIntroHold() {
        val project = CtsProject(model = VisualModel.Illustrated)
        // Five cards: 4*2s reveal + 0.8s intro hold + 1*(10/3)s scroll
        // + 2s final hold + 0.8s fade.
        assertEquals(14.933333f, TimelineEngine.automaticDuration(project), 0.0001f)
    }

    @Test
    fun firstCardUsesAHorizontalWipeBeforeItsBadgeSettles() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val firstFrame = TimelineEngine.placements(project, 0f)
        assertEquals(1, firstFrame.size)
        assertEquals(0f, firstFrame.first().bodyReveal, 0.001f)
        assertFalse(firstFrame.first().badgeVisible)

        val entering = TimelineEngine.placements(project, 0.7f).first()
        assertTrue(entering.bodyReveal > 0.75f)
        assertTrue(entering.badgeVisible)
        assertTrue(entering.badgeSettle in 0f..0.25f)

        val settledBody = TimelineEngine.placements(project, BODY_WIPE_SECONDS).first()
        assertEquals(1f, settledBody.bodyReveal, 0.001f)
    }

    @Test
    fun scrollingMovesEachParentByOneCardWidthWithEasing() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val before = TimelineEngine.placements(project, scrollStart)
        val halfway = TimelineEngine.placements(project, scrollStart + SCROLL_SECONDS / 2f)
        val after = TimelineEngine.placements(project, scrollStart + SCROLL_SECONDS)

        val beforeSecond = before.first { it.cardIndex == 1 }
        val halfwaySecond = halfway.first { it.cardIndex == 1 }
        val afterSecond = after.first { it.cardIndex == 1 }

        assertTrue(halfwaySecond.xInCards < beforeSecond.xInCards)
        assertTrue(halfwaySecond.xInCards > afterSecond.xInCards)
        assertEquals(1f, beforeSecond.xInCards - afterSecond.xInCards, 0.0001f)
    }

    @Test
    fun incomingBadgeAppearsOnlyWhenItsCardReachesTheFourthSlot() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val justBeforeArrival = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS - 0.01f,
        ).first { it.cardIndex == 4 }
        assertFalse(justBeforeArrival.badgeVisible)

        val atArrival = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS,
        ).first { it.cardIndex == 4 }
        assertTrue(atArrival.badgeVisible)
        assertEquals(0f, atArrival.badgeSettle, 0.01f)
        assertEquals(3f, atArrival.xInCards, 0.001f)
    }
}
