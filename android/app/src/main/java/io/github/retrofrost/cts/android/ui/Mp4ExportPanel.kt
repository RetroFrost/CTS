package io.github.retrofrost.cts.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.export.CtsExportPreset
import io.github.retrofrost.cts.android.export.CtsMp4Exporter
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.launch

@Composable
fun Mp4ExportPanel(
    project: CtsProject,
    onSaveProject: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preset by remember { mutableStateOf(CtsExportPreset.HD) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }

    val createMp4 = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destination ->
        destination ?: return@rememberLauncherForActivityResult
        val snapshot = project.normalized()
        exporting = true
        progress = 0f
        status = if (snapshot.soundtrack == null) {
            "Preparing ${preset.label} video…"
        } else {
            "Preparing ${preset.label} video with soundtrack…"
        }
        scope.launch {
            runCatching {
                CtsMp4Exporter.export(
                    context = context,
                    project = snapshot,
                    destination = destination,
                    preset = preset,
                    onProgress = { value ->
                        progress = value
                        status = "Exporting ${(value * 100f).toInt()}%"
                    },
                )
            }.onSuccess { result ->
                val megabytes = result.bytes / 1_048_576f
                val audioLabel = if (result.hasAudio) " · soundtrack included" else " · silent"
                status = "MP4 saved · %.1f MB · %d frames%s".format(
                    megabytes,
                    result.frameCount,
                    audioLabel,
                )
            }.onFailure { error ->
                status = "Export failed: ${error.message ?: error.javaClass.simpleName}"
            }
            exporting = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Export",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${project.cards.size} cards · ${TimelineEngine.formatTime(TimelineEngine.duration(project))} · 30 FPS",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "Export MP4",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "H.264 video using the CTS timeline, parent cards, image transforms, reveal animation, and the selected soundtrack. Editor handles are never included.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.MusicNote, contentDescription = null)
                            Column {
                                Text(
                                    text = project.soundtrack?.displayName ?: "No soundtrack",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = if (project.soundtrack == null) {
                                        "This export will be silent"
                                    } else {
                                        "Included at ${(project.soundtrack.volume * 100f).toInt()}% volume"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CtsExportPreset.entries.forEach { option ->
                            FilterChip(
                                selected = preset == option,
                                onClick = { if (!exporting) preset = option },
                                enabled = !exporting,
                                label = { Text(option.label) },
                            )
                        }
                    }

                    if (exporting) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    Button(
                        onClick = {
                            val cleanName = project.name
                                .ifBlank { "CTS comparison" }
                                .replace(Regex("[^A-Za-z0-9._ -]"), "")
                                .trim()
                                .ifBlank { "CTS comparison" }
                            createMp4.launch("$cleanName-${preset.label}.mp4")
                        },
                        enabled = !exporting && project.cards.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Movie, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (exporting) "Exporting…" else "Choose location and export")
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Project file",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Save the editable .cts.json separately from the rendered MP4.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSaveProject, enabled = !exporting) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Save .cts.json")
                    }
                }
            }
        }
    }
}
