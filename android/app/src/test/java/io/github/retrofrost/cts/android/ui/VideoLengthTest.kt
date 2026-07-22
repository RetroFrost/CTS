package io.github.retrofrost.cts.android.ui

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoLengthTest {
    @After
    fun resetDurationState() {
        DurationRuntime.resetForTests()
    }

    @Test
    fun parsesPreviousCtsDurationFormats() {
        assertEquals(45f, parseVideoLength("00:45") ?: 0f, 0.001f)
        assertEquals(90f, parseVideoLength("01:30") ?: 0f, 0.001f)
        assertEquals(600f, parseVideoLength("10:00") ?: 0f, 0.001f)
        assertEquals(3723f, parseVideoLength("1:02:03") ?: 0f, 0.001f)
        assertEquals(90f, parseVideoLength("90") ?: 0f, 0.001f)
        assertNull(parseVideoLength("1:75"))
        assertNull(parseVideoLength("00:00"))
        assertNull(parseVideoLength("not a time"))
    }

    @Test
    fun customLengthScalesTheWholeTimelineAndPersists() {
        val project = CtsProject(cards = List(7) { CtsCard(title = "Card $it") })
        val automatic = TimelineEngine.automaticDuration(project)

        DurationRuntime.useAutomatic()
        assertEquals(automatic, TimelineEngine.duration(project), 0.001f)

        DurationRuntime.useCustom(90f)
        assertEquals(90f, TimelineEngine.duration(project), 0.001f)
        assertEquals(90f, project.normalized().customDurationSeconds ?: 0f, 0.001f)
    }

    @Test
    fun automaticLengthClearsAnOlderCustomProjectValue() {
        val project = CtsProject(customDurationSeconds = 75f)
        DurationRuntime.useAutomatic()
        assertNull(project.normalized().customDurationSeconds)
        assertEquals(
            TimelineEngine.automaticDuration(project),
            TimelineEngine.duration(project),
            0.001f,
        )
    }
}
