package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.ModelMode
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineEngineTest {
    @Before fun resetDurationChoice() = DurationRuntime.resetForTests()

    @Test
    fun androidExposesBothIndependentReferenceModels() {
        assertEquals(listOf(VisualModel.Males, VisualModel.Relationships), VisualModel.entries)
        assertEquals(4, VisualModel.Males.visibleCards)
        assertEquals(4, VisualModel.Relationships.visibleCards)
    }

    @Test
    fun automaticDurationIncludesTheFullReferenceOutro() {
        val project = CtsProject(model = VisualModel.Males)
        val expected = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS + SCROLL_SECONDS +
            END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS +
            OUTRO_HOLD_SECONDS + FADE_SECONDS
        assertEquals(expected, TimelineEngine.automaticDuration(project), 0.0001f)
    }

    @Test
    fun customLengthChangesOnlySecondsPerScrollingCard() {
        val automaticProject = CtsProject(model = VisualModel.Males, modelMode = ModelMode.Custom)
        val automaticDuration = TimelineEngine.automaticDuration(automaticProject)
        val customProject = automaticProject.copy(customDurationSeconds = automaticDuration + 6f)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        assertEquals(automaticDuration + 6f, TimelineEngine.duration(customProject), 0.0001f)
        assertEquals(SCROLL_SECONDS + 6f, TimelineEngine.secondsPerCard(customProject), 0.0001f)
        assertEquals(scrollStart, TimelineEngine.modelTime(customProject, scrollStart), 0.0001f)
    }

    @Test
    fun firstCardSlidesFromOneSlotLeftInsteadOfBeingWiped() {
        val project = CtsProject(model = VisualModel.Males)
        val first = TimelineEngine.placements(project, 0f).first()
        assertEquals(-1f, first.xInCards, 0.001f)
        assertEquals(1f, first.bodyReveal, 0.001f)
        assertTrue(first.badgeVisible)

        val entering = TimelineEngine.placements(project, 0.7f).first()
        assertTrue(entering.xInCards in -1f..0f)
        assertTrue(entering.badgeVisible)
        val settled = TimelineEngine.placements(project, TimelineEngine.MALES_BODY_SECONDS).first()
        assertEquals(0f, settled.xInCards, 0.001f)
    }

    @Test
    fun scrollingMovesEachCompleteParentByOneCardWidthWithEasing() {
        val project = CtsProject(model = VisualModel.Males)
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
        val project = CtsProject(model = VisualModel.Males)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val beforeLead = TimelineEngine.placements(project, scrollStart + 2.05f)
            .first { it.cardIndex == 4 }
        assertFalse(beforeLead.badgeVisible)
        val duringLead = TimelineEngine.placements(project, scrollStart + 2.07f)
            .first { it.cardIndex == 4 }
        assertTrue(duringLead.badgeVisible)
        assertTrue(duringLead.xInCards > 3f)
    }

    @Test
    fun malesExactReferenceUsesMeasuredContinuousConveyorAndAffineBadge() {
        assertEquals(16_741, TimelineEngine.MALES_REFERENCE_FRAMES)
        val project = CtsProject(model = VisualModel.Males, modelMode = ModelMode.ExactReference)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val start = TimelineEngine.placements(project, scrollStart + 2.0f).first { it.cardIndex == 1 }
        val quarter = TimelineEngine.placements(project, scrollStart + 2.2f)
            .first { it.cardIndex == 1 }
        val half = TimelineEngine.placements(project, scrollStart + 2.4f)
            .first { it.cardIndex == 1 }
        assertEquals(half.xInCards - start.xInCards, 2f * (quarter.xInCards - start.xInCards), 0.015f)

        val opening = TimelineEngine.placements(project, 0.8f).first()
        assertTrue(opening.badgeAffine.m00 > 1f)
        assertTrue(opening.badgeAffine.m10 < 0f)
    }

    @Test
    fun outroCoversOnlyTheFirstThreeColumnsAndThenShowsContent() {
        val project = CtsProject(model = VisualModel.Males)
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

    @Test
    fun relationshipsExactReferenceLocksTheMeasuredFrameDuration() {
        val project = CtsProject(
            model = VisualModel.Relationships,
            modelMode = ModelMode.ExactReference,
            cards = List(40) { CtsCard(title = "Relationship $it") },
            customDurationSeconds = 42f,
        )
        assertEquals(
            TimelineEngine.RELATIONSHIPS_REFERENCE_FRAMES /
                TimelineEngine.RELATIONSHIPS_REFERENCE_FPS.toFloat(),
            TimelineEngine.duration(project),
            0.0001f,
        )
        assertEquals(185.5f, TimelineEngine.duration(project), 0.0001f)
    }

    @Test
    fun relationshipsPreludeHasSeparateInfinityAndDisclaimerPhases() {
        val project = CtsProject(model = VisualModel.Relationships)
        assertTrue(TimelineEngine.relationshipsInfinityProgress(project, 187f / 60f) in 0.49f..0.51f)
        assertTrue(TimelineEngine.relationshipsDisclaimerAlpha(project, 8.2f) > 0.9f)
        assertTrue(TimelineEngine.placements(project, 6.1f).isEmpty())
        assertTrue(TimelineEngine.placements(project, 6.3f).isNotEmpty())
    }

    @Test
    fun relationshipsFrameElevenUsesMeasuredOctagonBounds() {
        val project = CtsProject(model = VisualModel.Relationships)
        val sourceFrame = 374 + 11
        val placement = TimelineEngine.placements(project, sourceFrame / 60f).first()
        val rect = placement.badgeRect!!
        assertEquals(208f / 480f, rect.x, 0.0002f)
        assertEquals(170.3f / 1080f, rect.y, 0.0002f)
        assertEquals(48f / 480f, rect.width, 0.0002f)
        assertEquals(47.4f / 1080f, rect.height, 0.0002f)
    }

    @Test
    fun relationshipsBadgeUsesCanonicalTextAndShineClock() {
        val project = CtsProject(model = VisualModel.Relationships)
        val firstCardStart = TimelineEngine.RELATIONSHIPS_INTRO_FRAMES / 60f
        val beforeText = TimelineEngine.placements(project, firstCardStart + 87f / 60f).first()
        val duringShine = TimelineEngine.placements(project, firstCardStart + 107f / 60f).first()
        val settled = TimelineEngine.placements(project, firstCardStart + 120f / 60f).first()

        assertEquals(0.9f, beforeText.badgeAgeSeconds, 0.001f)
        assertTrue(duringShine.badgeAgeSeconds in 1.72f..2.24f)
        assertEquals(2.3f, settled.badgeAgeSeconds, 0.001f)
    }
}
