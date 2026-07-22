package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineEngineTest {
    @Before fun resetDurationChoice() = DurationRuntime.resetForTests()

    @Test
    fun androidExposesOnlyTheCanonicalFourCardModel() {
        assertEquals(listOf(VisualModel.Illustrated), VisualModel.entries)
        assertEquals(4, VisualModel.Illustrated.visibleCards)
    }

    @Test
    fun automaticDurationIncludesTheFullReferenceOutro() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val expected = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS + SCROLL_SECONDS +
            END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS +
            OUTRO_HOLD_SECONDS + FADE_SECONDS
        assertEquals(expected, TimelineEngine.automaticDuration(project), 0.0001f)
    }

    @Test
    fun customLengthChangesOnlySecondsPerScrollingCard() {
        val automaticProject = CtsProject(model = VisualModel.Illustrated)
        val automaticDuration = TimelineEngine.automaticDuration(automaticProject)
        val customProject = automaticProject.copy(customDurationSeconds = automaticDuration + 6f)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        assertEquals(automaticDuration + 6f, TimelineEngine.duration(customProject), 0.0001f)
        assertEquals(SCROLL_SECONDS + 6f, TimelineEngine.secondsPerCard(customProject), 0.0001f)
        assertEquals(scrollStart, TimelineEngine.modelTime(customProject, scrollStart), 0.0001f)
    }

    @Test
    fun firstCardSlidesFromOneSlotLeftInsteadOfBeingWiped() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val first = TimelineEngine.placements(project, 0f).first()
        assertEquals(-1f, first.xInCards, 0.001f)
        assertEquals(1f, first.bodyReveal, 0.001f)
        assertFalse(first.badgeVisible)

        val entering = TimelineEngine.placements(project, 0.7f).first()
        assertTrue(entering.xInCards in -1f..0f)
        assertTrue(entering.badgeVisible)
        val settled = TimelineEngine.placements(project, BODY_WIPE_SECONDS).first()
        assertEquals(0f, settled.xInCards, 0.001f)
    }

    @Test
    fun scrollingMovesEachCompleteParentByOneCardWidthWithEasing() {
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
        assertEquals(1f, halfway.first { it.cardIndex == 4 }.bodyReveal, 0.0001f)
    }

    @Test
    fun incomingBadgeBeginsBeforeTheCardFinishesArriving() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val beforeLead = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS - BADGE_DELAY_SECONDS - 0.01f,
        ).first { it.cardIndex == 4 }
        assertFalse(beforeLead.badgeVisible)
        val duringLead = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS - BADGE_DELAY_SECONDS + 0.01f,
        ).first { it.cardIndex == 4 }
        assertTrue(duringLead.badgeVisible)
        assertTrue(duringLead.xInCards > 3f)
    }

    @Test
    fun outroCoversOnlyTheFirstThreeColumnsAndThenShowsContent() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollEnd = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS + SCROLL_SECONDS
        assertEquals(0f, TimelineEngine.outroCoverProgress(project, scrollEnd), 0.001f)
        assertTrue(TimelineEngine.outroCoverProgress(project, scrollEnd + END_HOLD_SECONDS + OUTRO_COVER_SECONDS) > 0.99f)
        assertTrue(
            TimelineEngine.outroContentAlpha(
                project,
                scrollEnd + END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS + 0.12f,
            ) > 0.99f,
        )
        val finalPlacement = TimelineEngine.placements(project, scrollEnd + END_HOLD_SECONDS).last()
        assertEquals(4, finalPlacement.cardIndex)
        assertEquals(3f, finalPlacement.xInCards, 0.001f)
    }
}
