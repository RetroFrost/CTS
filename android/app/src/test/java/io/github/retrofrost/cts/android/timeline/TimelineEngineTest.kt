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
    fun androidExposesOnlyTheMalesModel() {
        assertEquals(listOf(VisualModel.Males), VisualModel.entries)
        assertEquals(VisualModel.Males, VisualModel.fromId("males"))
        assertEquals(4, VisualModel.Males.visibleCards)
    }

    @Test
    fun customMp4IntroPrecedesTheReferenceClock() {
        val base = CtsProject(model = VisualModel.Males)
        val withIntro = base.copy(
            introVideo = IntroVideoSettings(
                uri = "content://intro.mp4",
                displayName = "intro.mp4",
                durationSeconds = 7.5f,
            ),
        )
        assertEquals(TimelineEngine.automaticDuration(base) + 7.5f, TimelineEngine.automaticDuration(withIntro), 0.001f)
        assertTrue(TimelineEngine.customIntroVisible(withIntro, 7.49f))
        assertFalse(TimelineEngine.customIntroVisible(withIntro, 7.5f))
    }

    @Test
    fun referenceModelIgnoresCustomTimingRequests() {
        val project = CtsProject(
            model = VisualModel.Males,
            modelMode = ModelMode.Custom,
            customDurationSeconds = 42f,
        )
        assertEquals(TimelineEngine.automaticDuration(project), TimelineEngine.duration(project), 0.0001f)
    }

    @Test
    fun openingAndConveyorUseMeasuredFrameCoordinates() {
        val project = CtsProject(cards = List(57) { CtsCard(title = "Language $it") })
        val first = TimelineEngine.placements(project, 0f).first()
        assertEquals(-1f, first.xInCards, 0.001f)
        assertTrue(first.badgeVisible)

        val openingFrame = 119
        val settled = TimelineEngine.placements(project, openingFrame / 60f).first()
        assertEquals(ExactReferenceFrames.malesOpeningCardX(openingFrame, 0)!! / 480f, settled.xInCards, 0.001f)

        val conveyorFrame = 1_000
        val moving = TimelineEngine.placements(project, conveyorFrame / 60f).first { it.cardIndex == 2 }
        assertEquals(
            ExactReferenceFrames.malesConveyorCardX(conveyorFrame, 2)!! / 480f,
            moving.xInCards,
            0.001f,
        )
    }

    @Test
    fun languageReferenceUsesAll12267FramesAtNativeSpeed() {
        assertEquals(12_267, TimelineEngine.MALES_REFERENCE_FRAMES)
        assertEquals(1f, TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE, 0f)
        val project = CtsProject(cards = List(57) { CtsCard(title = "Language $it") }).normalized()
        assertEquals(12_267f / 60f, TimelineEngine.duration(project), 0.0001f)
    }

    @Test
    fun contactSheetMeasuredOutroUsesExactFrameAddresses() {
        val project = CtsProject(cards = List(57) { CtsCard(title = "Language $it") }).normalized()
        assertEquals(0f, TimelineEngine.outroCoverProgress(project, 11_867f / 60f), 0.001f)
        assertTrue(TimelineEngine.outroCoverProgress(project, 11_868f / 60f) > 0f)
        assertEquals(-210f, TimelineEngine.outroContentYOffsetPx(project, 11_901f / 60f)!!, 0.001f)
        assertEquals(0f, TimelineEngine.outroContentYOffsetPx(project, 11_911f / 60f)!!, 0.001f)
        assertEquals(1f, TimelineEngine.fadeAlpha(project, 12_179f / 60f), 0f)
        assertTrue(TimelineEngine.fadeAlpha(project, 12_180f / 60f) < 1f)
        assertEquals(0f, TimelineEngine.fadeAlpha(project, 12_258f / 60f), 0f)
    }
}
