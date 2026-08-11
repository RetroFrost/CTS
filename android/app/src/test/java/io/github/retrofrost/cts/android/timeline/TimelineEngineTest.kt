package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.IntroVideoSettings
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
    fun customMp4IntroPrecedesButDoesNotReplaceReferenceIntro() {
        val withoutIntro = CtsProject(model = VisualModel.Relationships)
        val withIntro = withoutIntro.copy(
            introVideo = IntroVideoSettings(
                uri = "content://intro.mp4",
                displayName = "intro.mp4",
                durationSeconds = 7.5f,
            ),
        )

        assertEquals(
            TimelineEngine.automaticDuration(withoutIntro) + 7.5f,
            TimelineEngine.automaticDuration(withIntro),
            0.001f,
        )
        assertTrue(TimelineEngine.customIntroVisible(withIntro, 7.49f))
        assertTrue(!TimelineEngine.customIntroVisible(withIntro, 7.5f))
        assertEquals(0, TimelineEngine.relationshipsSourceFrame(withIntro, 7.5f))
    }

    @Test
    fun androidExposesBothIndependentReferenceModels() {
        assertEquals(listOf(VisualModel.Males, VisualModel.Relationships), VisualModel.entries)
        assertEquals(4, VisualModel.Males.visibleCards)
        assertEquals(4, VisualModel.Relationships.visibleCards)
    }

    @Test
    fun referenceModelIgnoresCustomTimingRequests() {
        val project = CtsProject(
            model = VisualModel.Males,
            modelMode = ModelMode.Custom,
            customDurationSeconds = 42f,
        )
        assertEquals(TimelineEngine.automaticDuration(project), TimelineEngine.duration(project), 0.0001f)
        assertEquals(
            SCROLL_SECONDS / TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE,
            TimelineEngine.secondsPerCard(project),
            0.0001f,
        )
    }

    @Test
    fun firstCardSlidesFromOneSlotLeftInsteadOfBeingWiped() {
        val project = CtsProject(model = VisualModel.Males)
        val rate = TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE
        val first = TimelineEngine.placements(project, 0f).first()
        assertEquals(-1f, first.xInCards, 0.001f)
        assertEquals(1f, first.bodyReveal, 0.001f)
        assertTrue(first.badgeVisible)

        val entering = TimelineEngine.placements(project, 0.7f / rate).first()
        assertTrue(entering.xInCards in -1f..0f)
        assertTrue(entering.badgeVisible)
        val settled = TimelineEngine.placements(project, TimelineEngine.MALES_BODY_SECONDS / rate).first()
        assertEquals(0f, settled.xInCards, 0.001f)
    }

    @Test
    fun scrollingMovesEachCompleteParentByOneCardWidthWithEasing() {
        val project = CtsProject(model = VisualModel.Males)
        val rate = TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val before = TimelineEngine.placements(project, scrollStart / rate)
        val halfway = TimelineEngine.placements(project, (scrollStart + SCROLL_SECONDS / 2f) / rate)
        val after = TimelineEngine.placements(project, (scrollStart + SCROLL_SECONDS) / rate)
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
        val rate = TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val beforeLead = TimelineEngine.placements(project, (scrollStart + 2.05f) / rate)
            .first { it.cardIndex == 4 }
        assertFalse(beforeLead.badgeVisible)
        val duringLead = TimelineEngine.placements(project, (scrollStart + 2.07f) / rate)
            .first { it.cardIndex == 4 }
        assertTrue(duringLead.badgeVisible)
        assertTrue(duringLead.xInCards > 3f)
    }

    @Test
    fun malesExactReferenceUsesMeasuredContinuousConveyorAndAffineBadge() {
        assertEquals(16_741, TimelineEngine.MALES_REFERENCE_FRAMES)
        val project = CtsProject(model = VisualModel.Males, modelMode = ModelMode.ExactReference)
        val rate = TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE
        assertEquals(SCROLL_SECONDS / rate, TimelineEngine.secondsPerCard(project), 0.0001f)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val start = TimelineEngine.placements(project, (scrollStart + 2.0f) / rate).first { it.cardIndex == 1 }
        val quarter = TimelineEngine.placements(project, (scrollStart + 2.2f) / rate)
            .first { it.cardIndex == 1 }
        val half = TimelineEngine.placements(project, (scrollStart + 2.4f) / rate)
            .first { it.cardIndex == 1 }
        assertEquals(half.xInCards - start.xInCards, 2f * (quarter.xInCards - start.xInCards), 0.015f)

        val opening = TimelineEngine.placements(project, 0.8f / rate).first()
        assertTrue(opening.badgeAffine.m00 > 1f)
        assertTrue(opening.badgeAffine.m10 < 0f)
    }

    @Test
    fun malesCanonicalFullVideoUsesAll16741Frames() {
        val project = CtsProject(
            model = VisualModel.Males,
            cards = List(78) { CtsCard(title = "Age $it") },
        ).normalized()
        assertEquals(16_741f / 60f, TimelineEngine.duration(project), 0.0001f)
    }

    @Test
    fun exactReferenceRunsAtNativeSourceSpeed() {
        assertEquals(1f, TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE, 0f)
    }

    @Test
    fun malesMeasuredOutroScalesToProjectCardCount() {
        val project = CtsProject(
            model = VisualModel.Males,
            cards = List(5) { CtsCard(title = "Card $it") },
        ).normalized()
        val duration = TimelineEngine.duration(project)
        val endingSeconds = (25f + 23f + 273f + 48f) / 60f
        val wipeStart = duration - endingSeconds

        assertEquals(0f, TimelineEngine.outroCoverProgress(project, wipeStart - 1f / 60f), 0.001f)
        assertTrue(TimelineEngine.outroCoverProgress(project, wipeStart + 25f / 60f) > 0.99f)
        assertTrue(TimelineEngine.outroContentAlpha(project, wipeStart + 48f / 60f) > 0.99f)
        assertTrue(TimelineEngine.fadeAlpha(project, duration - 1f / 60f) < 0.1f)

        val finalPlacement = TimelineEngine.placements(project, wipeStart - 1f / 60f).last()
        assertEquals(4, finalPlacement.cardIndex)
        assertEquals(3f, finalPlacement.xInCards, 0.02f)
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
                TimelineEngine.RELATIONSHIPS_REFERENCE_FPS.toFloat() /
                TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE,
            TimelineEngine.duration(project),
            0.0001f,
        )
        assertEquals(185.5f, TimelineEngine.duration(project), 0.0001f)
    }

    @Test
    fun relationshipsPreludeHasSeparateInfinityAndDisclaimerPhases() {
        val project = CtsProject(model = VisualModel.Relationships)
        val rate = TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE
        assertTrue(TimelineEngine.relationshipsInfinityProgress(project, 187f / 60f / rate) in 0.49f..0.51f)
        assertTrue(TimelineEngine.relationshipsDisclaimerAlpha(project, 8.2f / rate) > 0.9f)
        assertTrue(TimelineEngine.placements(project, 6.1f / rate).isEmpty())
        assertTrue(TimelineEngine.placements(project, 6.3f / rate).isNotEmpty())
    }

    @Test
    fun relationshipsFrameElevenUsesMeasuredOctagonBounds() {
        val project = CtsProject(model = VisualModel.Relationships)
        val sourceFrame = 374 + 11
        val placement = TimelineEngine.placements(
            project,
            sourceFrame / 60f / TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE,
        ).first()
        val rect = placement.badgeRect!!
        assertEquals(208f / 480f, rect.x, 0.0002f)
        assertEquals(170.3f / 1080f, rect.y, 0.0002f)
        assertEquals(48f / 480f, rect.width, 0.0002f)
        assertEquals(47.4f / 1080f, rect.height, 0.0002f)
    }

    @Test
    fun relationshipsBadgeUsesCanonicalTextAndShineClock() {
        val project = CtsProject(model = VisualModel.Relationships)
        val rate = TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE
        val firstCardStart = TimelineEngine.RELATIONSHIPS_INTRO_FRAMES / 60f / rate
        val beforeText = TimelineEngine.placements(project, firstCardStart + 87f / 60f / rate).first()
        val duringShine = TimelineEngine.placements(project, firstCardStart + 107f / 60f / rate).first()
        val settled = TimelineEngine.placements(project, firstCardStart + 120f / 60f / rate).first()

        assertEquals(0.9f, beforeText.badgeAgeSeconds, 0.001f)
        assertTrue(duringShine.badgeAgeSeconds in 1.72f..2.24f)
        assertEquals(2.3f, settled.badgeAgeSeconds, 0.001f)
    }
}
