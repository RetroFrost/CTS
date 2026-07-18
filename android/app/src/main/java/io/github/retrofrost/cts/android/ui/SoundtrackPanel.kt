package io.github.retrofrost.cts.android.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.CtsSoundtrack

@Composable
fun SoundtrackPanel(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
) {
    val context = LocalContext.current
    val soundtrack = project.soundtrack

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "Soundtrack" }

        onProjectChanged(
            project.copy(
                soundtrack = CtsSoundtrack(
                    source = uri.toString(),
                    displayName = name,
                    volume = soundtrack?.volume ?: 1f,
                    loop = soundtrack?.loop ?: true,
                ),
            ).normalized(),
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "Audio",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "One soundtrack follows the complete comparison timeline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = if (soundtrack == null) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = if (soundtrack == null) {
                            Icons.Filled.LibraryMusic
                        } else {
                            Icons.Filled.MusicNote
                        },
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )

                    Text(
                        text = soundtrack?.displayName ?: "No soundtrack selected",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (soundtrack == null) {
                            "Choose MP3, M4A, AAC, WAV, FLAC, or another format Android can decode."
                        } else {
                            "Playback starts from the current timeline position and is included in MP4 export."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = { picker.launch(arrayOf("audio/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(if (soundtrack == null) "Choose soundtrack" else "Replace soundtrack")
                    }
                }
            }
        }

        if (soundtrack != null) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Volume", fontWeight = FontWeight.SemiBold)
                        Slider(
                            value = soundtrack.volume,
                            onValueChange = { value ->
                                onProjectChanged(
                                    project.copy(
                                        soundtrack = soundtrack.copy(volume = value),
                                    ).normalized(),
                                )
                            },
                            valueRange = 0f..1f,
                        )
                        Text(
                            text = "${(soundtrack.volume * 100f).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Loop soundtrack", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Repeat it until the video ends",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = soundtrack.loop,
                                onCheckedChange = { enabled ->
                                    onProjectChanged(
                                        project.copy(
                                            soundtrack = soundtrack.copy(loop = enabled),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { onProjectChanged(project.copy(soundtrack = null)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Remove soundtrack")
                }
            }
        }
    }
}

/** Keeps the selected soundtrack synchronized with the CTS preview timeline. */
@Composable
fun SoundtrackPlaybackEffect(
    soundtrack: CtsSoundtrack?,
    isPlaying: Boolean,
    positionSeconds: Float,
) {
    val context = LocalContext.current
    val source = soundtrack?.source
    val player = remember(source) {
        if (source.isNullOrBlank()) {
            null
        } else {
            runCatching { MediaPlayer.create(context, Uri.parse(source)) }.getOrNull()
        }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }

    LaunchedEffect(player, soundtrack?.volume, soundtrack?.loop) {
        player ?: return@LaunchedEffect
        player.isLooping = soundtrack?.loop ?: true
        val volume = soundtrack?.volume?.coerceIn(0f, 1f) ?: 1f
        player.setVolume(volume, volume)
    }

    LaunchedEffect(player, isPlaying, source) {
        player ?: return@LaunchedEffect
        if (isPlaying) {
            val duration = player.duration.coerceAtLeast(1)
            val desired = ((positionSeconds * 1000f).toInt() % duration).coerceAtLeast(0)
            runCatching { player.seekTo(desired) }
            runCatching { player.start() }
        } else {
            runCatching { if (player.isPlaying) player.pause() }
        }
    }

    if (!isPlaying) {
        LaunchedEffect(player, positionSeconds, source) {
            player ?: return@LaunchedEffect
            val duration = player.duration.coerceAtLeast(1)
            val desired = ((positionSeconds * 1000f).toInt() % duration).coerceAtLeast(0)
            runCatching { player.seekTo(desired) }
        }
    }
}
