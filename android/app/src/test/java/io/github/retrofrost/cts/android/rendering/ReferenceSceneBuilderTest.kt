package io.github.retrofrost.cts.android.rendering

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.IntroVideoSettings
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSceneBuilderTest {
    @Test
    fun sceneIsTheSingleMalesTimelineSampleUsedByPreviewAndExport() {
        val project = CtsProject(cards = List(57) { CtsCard(title = "Language $it") })
        val time = 17.25f
        val scene = ReferenceSceneBuilder.build(project, time)
        assertEquals(TimelineEngine.placements(project, time), scene.placements)
        assertEquals(TimelineEngine.fadeAlpha(project, time), scene.fadeAlpha, 0f)
    }

    @Test
    fun invalidAndNegativeTimesCannotReachTheDrawingPass() {
        val project = CtsProject()
        assertEquals(0f, ReferenceSceneBuilder.build(project, Float.NaN).outputTimeSeconds, 0f)
        assertEquals(0f, ReferenceSceneBuilder.build(project, Float.POSITIVE_INFINITY).outputTimeSeconds, 0f)
        assertEquals(0f, ReferenceSceneBuilder.build(project, -42f).outputTimeSeconds, 0f)
    }

    @Test
    fun customIntroClockDoesNotShiftReferenceSamplingTwice() {
        val introSeconds = 4f
        val project = CtsProject(
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
        assertEquals(-1f, firstReferenceFrame.placements.first().xInCards, 0.001f)
    }
}
