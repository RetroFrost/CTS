package io.github.retrofrost.cts.android.rendering

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.IntroVideoSettings
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReferenceSceneBuilderTest {
    @Before
    fun resetDurationChoice() = DurationRuntime.resetForTests()

    @Test
    fun sceneIsTheSingleTimelineSampleUsedByPreviewAndExport() {
        val project = CtsProject(
            model = VisualModel.Relationships,
            cards = List(8) { CtsCard(title = "Relationship $it") },
        )
        val time = 17.25f
        val scene = ReferenceSceneBuilder.build(project, time)

        assertEquals(TimelineEngine.placements(project, time), scene.placements)
        assertEquals(TimelineEngine.relationshipsSourceFrame(project, time), scene.relationshipsSourceFrame)
        assertEquals(TimelineEngine.relationshipsOutroLocalFrame(project, time), scene.relationshipsOutroLocalFrame)
        assertEquals(TimelineEngine.fadeAlpha(project, time), scene.fadeAlpha, 0f)
        assertTrue(scene.relationships)
    }

    @Test
    fun invalidAndNegativeTimesCannotReachTheDrawingPass() {
        val project = CtsProject(model = VisualModel.Males)

        assertEquals(0f, ReferenceSceneBuilder.build(project, Float.NaN).outputTimeSeconds, 0f)
        assertEquals(0f, ReferenceSceneBuilder.build(project, Float.POSITIVE_INFINITY).outputTimeSeconds, 0f)
        assertEquals(0f, ReferenceSceneBuilder.build(project, -42f).outputTimeSeconds, 0f)
    }

    @Test
    fun customIntroClockDoesNotShiftReferenceSamplingTwice() {
        val introSeconds = 4f
        val project = CtsProject(
            model = VisualModel.Relationships,
            introVideo = IntroVideoSettings(
                uri = "content://intro.mp4",
                displayName = "intro.mp4",
                durationSeconds = introSeconds,
            ),
        )

        val duringIntro = ReferenceSceneBuilder.build(project, introSeconds - 1f / 60f)
        val firstReferenceFrame = ReferenceSceneBuilder.build(project, introSeconds)

        assertTrue(duringIntro.customIntroVisible)
        assertFalse(firstReferenceFrame.customIntroVisible)
        assertEquals(0, firstReferenceFrame.relationshipsSourceFrame)
    }
}
