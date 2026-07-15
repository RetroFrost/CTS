package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlin.math.abs

private val CtsVideoSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

internal fun currentVideoSpeed(project: CtsProject): Float {
    val automatic = TimelineEngine.automaticDuration(project)
    val chosen = TimelineEngine.duration(project)
    return if (automatic > 0f && chosen > 0f) automatic / chosen else 1f
}

internal fun projectWithVideoSpeed(project: CtsProject, speed: Float): CtsProject {
    val safeSpeed = speed.coerceIn(0.25f, 4f)
    val automatic = TimelineEngine.automaticDuration(project)
    if (automatic <= 0f) return project
    return project.copy(
        customDurationSeconds = if (abs(safeSpeed - 1f) < 0.001f) {
            null
        } else {
            automatic / safeSpeed
        },
    )
}

/** Compact controller shared by preview timing and MP4 export timing. */
@Composable
fun VideoSpeedControl(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
) {
    val activeSpeed = currentVideoSpeed(project)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            item {
                Text(
                    text = "Video speed",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 9.dp, end = 4.dp),
                )
            }
            items(CtsVideoSpeeds.size) { index ->
                val speed = CtsVideoSpeeds[index]
                FilterChip(
                    selected = abs(activeSpeed - speed) < 0.03f,
                    onClick = { onProjectChanged(projectWithVideoSpeed(project, speed)) },
                    label = { Text("${speed}×") },
                )
            }
        }
    }
}
