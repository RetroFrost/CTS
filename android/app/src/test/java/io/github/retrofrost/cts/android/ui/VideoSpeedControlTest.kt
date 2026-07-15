package io.github.retrofrost.cts.android.ui

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSpeedControlTest {
    @Test
    fun twoTimesSpeedHalvesPreviewAndExportDuration() {
        val project = CtsProject().normalized()
        val automatic = TimelineEngine.automaticDuration(project)

        val faster = projectWithVideoSpeed(project, 2f)

        assertEquals(automatic / 2f, TimelineEngine.duration(faster), 0.001f)
        assertEquals(2f, currentVideoSpeed(faster), 0.001f)
    }

    @Test
    fun normalSpeedReturnsToAutomaticDuration() {
        val project = projectWithVideoSpeed(CtsProject().normalized(), 0.5f)

        val normal = projectWithVideoSpeed(project, 1f)

        assertEquals(null, normal.customDurationSeconds)
        assertEquals(1f, currentVideoSpeed(normal), 0.001f)
    }
}
