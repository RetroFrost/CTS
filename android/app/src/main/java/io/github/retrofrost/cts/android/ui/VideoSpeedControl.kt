package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exact output-duration controller.
 *
 * Reveal animations retain their normal timing. Only horizontal scrolling expands or
 * contracts to fill the selected duration between the fixed reveal and ending sections.
 */
@Composable
fun VideoLengthControl(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
) {
    val automatic = TimelineEngine.automaticDuration(project)
    val minimum = TimelineEngine.minimumDuration(project)
    val effective = TimelineEngine.duration(project)
    val maximum = max(max(automatic * 2f, minimum + 30f), effective)
    var input by remember(project.customDurationSeconds, effective) {
        mutableStateOf(formatDurationInput(effective))
    }

    fun applyDuration(seconds: Float) {
        val safe = seconds.coerceIn(minimum, maximum)
        input = formatDurationInput(safe)
        onProjectChanged(project.copy(customDurationSeconds = safe))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Video length",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Scrolling adjusts; card animations stay unchanged",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = project.customDurationSeconds == null,
                    onClick = {
                        input = formatDurationInput(automatic)
                        onProjectChanged(project.copy(customDurationSeconds = null))
                    },
                    label = { Text("Auto") },
                )
            }

            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    input = value
                    parseDurationInput(value)?.let { parsed ->
                        if (parsed >= minimum) {
                            onProjectChanged(project.copy(customDurationSeconds = parsed))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Length (m:ss)") },
                supportingText = {
                    Text("Minimum ${formatDurationInput(minimum)} · Auto ${formatDurationInput(automatic)}")
                },
            )

            Slider(
                value = effective.coerceIn(minimum, maximum),
                onValueChange =(::applyDuration),
                valueRange = minimum..maximum,
                steps = 0,
            )
        }
    }
}

internal fun parseDurationInput(value: String): Float? {
    val cleaned = value.trim()
    if (cleaned.isBlank()) return null
    val pieces = cleaned.split(':')
    return when (pieces.size) {
        1 -> pieces[0].toFloatOrNull()?.takeIf { it > 0f }
        2 -> {
            val minutes = pieces[0].toIntOrNull() ?: return null
            val seconds = pieces[1].toFloatOrNull() ?: return null
            if (minutes < 0 || seconds < 0f || seconds >= 60f) null else minutes * 60f + seconds
        }
        else -> null
    }
}

internal fun formatDurationInput(seconds: Float): String {
    val total = seconds.coerceAtLeast(0f).roundToInt()
    return "%d:%02d".format(total / 60, total % 60)
}
