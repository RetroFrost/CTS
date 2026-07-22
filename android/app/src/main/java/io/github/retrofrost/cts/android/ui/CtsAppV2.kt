package io.github.retrofrost.cts.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.retrofrost.cts.android.export.CodecCatalog
import io.github.retrofrost.cts.android.export.EncoderChoice
import io.github.retrofrost.cts.android.export.ExportWorker
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.persistence.ProjectJson
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.launch
import java.util.UUID

private enum class WorkspaceSection(val label: String) {
    Data("Data"),
    Audio("Audio"),
    Export("Export"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CtsAndroidAppV2() {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(CtsProject().normalized()) }
    var selectedCardId by remember { mutableStateOf(project.cards.firstOrNull()?.id) }
    var positionSeconds by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(WorkspaceSection.Data) }
    var showInsertDialog by remember { mutableStateOf(false) }
    var pendingExportPermission by remember { mutableStateOf(false) }
    val duration = TimelineEngine.duration(project)

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun selectCard(cardId: String) {
        selectedCardId = cardId
        val index = project.cards.indexOfFirst { it.id == cardId }
        if (index >= 0) positionSeconds = TimelineEngine.editingTimeForCard(project, index)
        isPlaying = false
    }

    fun applyProject(updated: CtsProject) {
        project = updated.normalized()
        if (selectedCardId !in project.cards.map { it.id }) {
            selectedCardId = project.cards.firstOrNull()?.id
        }
        positionSeconds = positionSeconds.coerceAtMost(TimelineEngine.duration(project))
    }

    fun updateSelectedCard(update: (CtsCard) -> CtsCard) {
        val cardId = selectedCardId ?: return
        applyProject(project.updateCard(cardId, update))
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        updateSelectedCard { card ->
            card.copy(imageSubcard = card.imageSubcard.copy(source = uri.toString()))
        }
        message("Image attached")
    }

    val soundtrackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        project = project.copy(
            soundtrack = project.soundtrack.copy(
                uri = uri.toString(),
                displayName = queryDisplayName(context, uri),
            ),
        ).normalized()
        message("Soundtrack ready for AAC encoding")
    }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("The selected project could not be read.")
            project = ProjectJson.decode(text).normalized()
            selectedCardId = project.cards.firstOrNull()?.id
            positionSeconds = 0f
            isPlaying = false
        }.onSuccess {
            message("Project opened")
        }.onFailure { error ->
            message(error.message ?: "Could not open that CTS project")
        }
    }

    val saveProject = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)
                ?.bufferedWriter()
                ?.use { it.write(ProjectJson.encode(project.normalized())) }
                ?: error("The selected destination could not be written.")
        }.onSuccess {
            message("Project saved")
        }.onFailure { error ->
            message(error.message ?: "Could not save the project")
        }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = exportFileName(project)
        ExportWorker.enqueue(context, project, uri, name)
        message("Encoding started in the background. CTS will notify you when $name is ready.")
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (pendingExportPermission) {
            pendingExportPermission = false
            if (!granted) message("Notifications are off, but background encoding can still continue.")
            outputPicker.launch(exportFileName(project))
        }
    }

    fun requestExport() {
        if (project.cards.isEmpty()) {
            message("Add at least one card before exporting.")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            outputPicker.launch(exportFileName(project))
        }
    }

    LaunchedEffect(isPlaying, duration) {
        if (!isPlaying || duration <= 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isPlaying) {
            val now = withFrameNanos { it }
            val elapsed = (now - previous) / 1_000_000_000f
            previous = now
            positionSeconds = (positionSeconds + elapsed).coerceAtMost(duration)
            if (positionSeconds >= duration) isPlaying = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CTS", fontWeight = FontWeight.Black)
                        Text(
                            "Comparison Timeline Studio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { openProject.launch(arrayOf("application/json", "text/json", "*/*")) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Open project")
                    }
                    IconButton(onClick = { saveProject.launch("comparison-project.cts.json") }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save project")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ProgramMonitor(
                project = project,
                positionSeconds = positionSeconds,
                selectedCardId = selectedCardId,
                onSelectCard = ::selectCard,
                onImageTransformChanged = { cardId, transform ->
                    applyProject(
                        project.updateCard(cardId) { card ->
                            card.copy(imageSubcard = card.imageSubcard.copy(transform = transform.clamped()))
                        },
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )

            TimelineControlsV2(
                positionSeconds = positionSeconds,
                durationSeconds = duration,
                isPlaying = isPlaying,
                onPlayPause = {
                    if (positionSeconds >= duration) positionSeconds = 0f
                    isPlaying = !isPlaying
                },
                onPositionChanged = {
                    isPlaying = false
                    positionSeconds = it
                },
            )

            WorkspaceTabs(section = section, onSectionChanged = { section = it })
            HorizontalDivider()

            Box(modifier = Modifier.weight(1f)) {
                when (section) {
                    WorkspaceSection.Data -> DataWorkspace(
                        project = project,
                        selectedCardId = selectedCardId,
                        onSelectCard = ::selectCard,
                        onProjectChanged = ::applyProject,
                        onUpdateSelectedCard = ::updateSelectedCard,
                        onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
                        onInsertData = { showInsertDialog = true },
                    )
                    WorkspaceSection.Audio -> AudioWorkspace(
                        project = project,
                        onProjectChanged = ::applyProject,
                        onChooseSoundtrack = { soundtrackPicker.launch(arrayOf("audio/*")) },
                    )
                    WorkspaceSection.Export -> ExportWorkspace(
                        project = project,
                        onProjectChanged = ::applyProject,
                        onExport = ::requestExport,
                    )
                }
            }
        }
    }

    if (showInsertDialog) {
        InsertCardsDialogV2(
            existingCards = project.cards,
            onDismiss = { showInsertDialog = false },
            onApply = { cards ->
                applyProject(project.copy(cards = cards))
                selectedCardId = cards.firstOrNull()?.id
                positionSeconds = 0f
                isPlaying = false
                showInsertDialog = false
                message("Inserted ${cards.size} cards")
            },
        )
    }
}

@Composable
private fun TimelineControlsV2(
    positionSeconds: Float,
    durationSeconds: Float,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPositionChanged: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
            )
        }
        Slider(
            value = positionSeconds.coerceIn(0f, durationSeconds.coerceAtLeast(0.001f)),
            onValueChange = onPositionChanged,
            valueRange = 0f..durationSeconds.coerceAtLeast(0.001f),
            modifier = Modifier.weight(1f),
        )
        Text(
            "${TimelineEngine.formatTime(positionSeconds)} / ${TimelineEngine.formatTime(durationSeconds)}",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun WorkspaceTabs(
    section: WorkspaceSection,
    onSectionChanged: (WorkspaceSection) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkspaceSection.entries.forEach { item ->
            val icon = when (item) {
                WorkspaceSection.Data -> Icons.Filled.TableRows
                WorkspaceSection.Audio -> Icons.Filled.MusicNote
                WorkspaceSection.Export -> Icons.Filled.Movie
            }
            FilterChip(
                selected = section == item,
                onClick = { onSectionChanged(item) },
                label = { Text(item.label) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DataWorkspace(
    project: CtsProject,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onProjectChanged: (CtsProject) -> Unit,
    onUpdateSelectedCard: ((CtsCard) -> CtsCard) -> Unit,
    onChooseImage: () -> Unit,
    onInsertData: () -> Unit,
) {
    val selected = project.cards.firstOrNull { it.id == selectedCardId }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Button(
                onClick = onInsertData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.TableRows, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Insert or edit all cards", fontWeight = FontWeight.Black)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(project.cards, key = { it.id }) { card ->
                    FilterChip(
                        selected = card.id == selectedCardId,
                        onClick = { onSelectCard(card.id) },
                        label = {
                            Text(
                                card.title.ifBlank { "Untitled" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        val updated = project.addBlankCard()
                        onProjectChanged(updated)
                        updated.cards.lastOrNull()?.id?.let(onSelectCard)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add")
                }
                FilledTonalButton(
                    onClick = {
                        selectedCardId?.let { id ->
                            val updated = project.duplicateCard(id)
                            onProjectChanged(updated)
                            val index = updated.cards.indexOfFirst { it.id == id }
                            updated.cards.getOrNull(index + 1)?.id?.let(onSelectCard)
                        }
                    },
                    enabled = selected != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text("Duplicate")
                }
                FilledTonalButton(
                    onClick = {
                        selectedCardId?.let { onProjectChanged(project.removeCard(it)) }
                    },
                    enabled = selected != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Delete")
                }
            }
        }

        if (selected == null) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("Add or paste cards to begin.", modifier = Modifier.padding(18.dp))
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = selected.badgePrimary,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(badgePrimary = value) } },
                    label = { Text("Badge value") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = selected.badgeSecondary,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(badgeSecondary = value) } },
                    label = { Text("Badge label") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = selected.title,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(title = value) } },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = selected.description,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(description = value) } },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onChooseImage, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Text(if (selected.imageSubcard.source.isNullOrBlank()) "Choose image" else "Replace image")
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdateSelectedCard { card ->
                                card.copy(imageSubcard = card.imageSubcard.copy(transform = NormalizedRect.Full))
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                        Text("Reset frame")
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun AudioWorkspace(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
    onChooseSoundtrack: () -> Unit,
) {
    val encoders = remember { CodecCatalog.audioEncoders() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Soundtrack", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "CTS decodes the selected track, applies volume and looping, then encodes it as AAC inside the MP4.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onChooseSoundtrack, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null)
                    Text(if (project.soundtrack.uri == null) "Choose soundtrack" else "Replace soundtrack")
                }
                OutlinedButton(
                    onClick = {
                        onProjectChanged(project.copy(soundtrack = project.soundtrack.copy(uri = null, displayName = "")))
                    },
                    enabled = project.soundtrack.uri != null,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                    Text("Remove")
                }
            }
        }
        project.soundtrack.uri?.let {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(project.soundtrack.displayName.ifBlank { "Selected audio" }, fontWeight = FontWeight.Bold)
                        Text("Volume ${"%.0f".format(project.soundtrack.volume * 100)}%")
                        Slider(
                            value = project.soundtrack.volume,
                            onValueChange = { value ->
                                onProjectChanged(project.copy(soundtrack = project.soundtrack.copy(volume = value)))
                            },
                            valueRange = 0f..2f,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = project.soundtrack.loop,
                                onCheckedChange = { checked ->
                                    onProjectChanged(project.copy(soundtrack = project.soundtrack.copy(loop = checked)))
                                },
                            )
                            Text("Loop until the video ends")
                        }
                    }
                }
            }
        }
        item {
            EncoderDropdown(
                title = "Audio encoder",
                selectedName = project.export.audioEncoderName,
                automaticLabel = "Automatic AAC encoder",
                choices = encoders,
                onSelected = { choice ->
                    onProjectChanged(project.copy(export = project.export.copy(audioEncoderName = choice?.name)))
                },
            )
        }
        item {
            Text("AAC bitrate", fontWeight = FontWeight.Bold)
            ChoiceRow(
                options = listOf(128_000 to "128 kbps", 192_000 to "192 kbps", 256_000 to "256 kbps"),
                selected = project.export.audioBitrate,
                onSelected = { value -> onProjectChanged(project.copy(export = project.export.copy(audioBitrate = value))) },
            )
        }
    }
}

@Composable
private fun ExportWorkspace(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
    onExport: () -> Unit,
) {
    val encoders = remember { CodecCatalog.videoEncoders() }
    val duration = TimelineEngine.duration(project)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Encode MP4", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Encoding runs as a foreground background job. You may leave CTS; a notification reports progress and warns when the MP4 is ready.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text("Resolution", fontWeight = FontWeight.Bold)
            ChoiceRow(
                options = listOf(
                    (1280 to 720) to "720p",
                    (1920 to 1080) to "1080p",
                ),
                selected = project.export.width to project.export.height,
                onSelected = { (width, height) ->
                    onProjectChanged(project.copy(export = project.export.copy(width = width, height = height)))
                },
            )
        }
        item {
            Text("Frame rate", fontWeight = FontWeight.Bold)
            ChoiceRow(
                options = listOf(24 to "24 fps", 30 to "30 fps", 60 to "60 fps"),
                selected = project.export.fps,
                onSelected = { fps -> onProjectChanged(project.copy(export = project.export.copy(fps = fps))) },
            )
        }
        item {
            Text("Video bitrate", fontWeight = FontWeight.Bold)
            ChoiceRow(
                options = listOf(
                    4_000_000 to "4 Mbps",
                    6_000_000 to "6 Mbps",
                    10_000_000 to "10 Mbps",
                    16_000_000 to "16 Mbps",
                ),
                selected = project.export.videoBitrate,
                onSelected = { bitrate ->
                    onProjectChanged(project.copy(export = project.export.copy(videoBitrate = bitrate)))
                },
            )
        }
        item {
            EncoderDropdown(
                title = "Video encoder",
                selectedName = project.export.videoEncoderName,
                automaticLabel = "Automatic H.264 encoder",
                choices = encoders,
                onSelected = { choice ->
                    onProjectChanged(
                        project.copy(
                            export = project.export.copy(
                                videoEncoderName = choice?.name,
                                videoMime = choice?.mime ?: MediaFormat.MIMETYPE_VIDEO_AVC,
                            ),
                        ),
                    )
                },
            )
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("Ready", fontWeight = FontWeight.Black)
                    Text("${project.cards.size} cards · ${TimelineEngine.formatTime(duration)}")
                    Text("${project.export.width}×${project.export.height} · ${project.export.fps} fps · ${project.export.videoBitrate / 1_000_000} Mbps")
                    Text(
                        if (project.soundtrack.uri == null) "Silent MP4" else "AAC soundtrack · ${project.export.audioBitrate / 1000} kbps",
                    )
                }
            }
        }
        item {
            Button(
                onClick = onExport,
                enabled = project.cards.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Encode MP4 in background", fontWeight = FontWeight.Black)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun EncoderDropdown(
    title: String,
    selectedName: String?,
    automaticLabel: String,
    choices: List<EncoderChoice>,
    onSelected: (EncoderChoice?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.name == selectedName }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontWeight = FontWeight.Bold)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.label ?: automaticLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(automaticLabel) },
                    onClick = {
                        expanded = false
                        onSelected(null)
                    },
                )
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = {
                            expanded = false
                            onSelected(choice)
                        },
                    )
                }
            }
        }
        if (choices.isEmpty()) {
            Text(
                "No explicit encoder list was returned; Android will choose the default codec.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(options) { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun InsertCardsDialogV2(
    existingCards: List<CtsCard>,
    onDismiss: () -> Unit,
    onApply: (List<CtsCard>) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            "Badge\tLabel\tTitle\tDescription\tImage\n" +
                existingCards.joinToString("\n") { card ->
                    listOf(
                        card.badgePrimary,
                        card.badgeSecondary,
                        card.title,
                        card.description,
                        card.imageSubcard.source.orEmpty(),
                    ).joinToString("\t")
                },
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert or edit cards") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a table. Tabs are best; CSV, pipes and semicolons are also recognized.")
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    label = { Text("Badge · Label · Title · Description · Image") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { parseCardsV2(text, existingCards) }
                        .onSuccess(onApply)
                        .onFailure { error = it.message ?: "Could not parse this table." }
                },
            ) {
                Text("Apply cards")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseCardsV2(text: String, existingCards: List<CtsCard>): List<CtsCard> {
    val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
    require(lines.isNotEmpty()) { "Paste at least one row." }
    val delimiter = when {
        lines.first().contains('\t') -> '\t'
        lines.first().contains('|') -> '|'
        lines.first().contains(';') -> ';'
        else -> ','
    }
    val matrix = lines.map { parseDelimitedLineV2(it, delimiter) }
    val first = matrix.first().map { it.trim().lowercase() }
    val knownHeaders = setOf(
        "badge", "value", "date", "year", "title", "name", "description",
        "details", "image", "artwork", "label", "unit",
    )
    val hasHeader = first.any { it in knownHeaders }
    val headers = if (hasHeader) first else listOf("badge", "label", "title", "description", "image")
    val rows = if (hasHeader) matrix.drop(1) else matrix

    fun index(vararg names: String): Int = headers.indexOfFirst { it in names }
    val badgeIndex = index("badge", "value", "date", "year")
    val labelIndex = index("label", "unit")
    val titleIndex = index("title", "name")
    val descriptionIndex = index("description", "details")
    val imageIndex = index("image", "artwork")
    fun value(row: List<String>, column: Int): String = if (column in row.indices) row[column].trim() else ""

    val cards = rows.mapIndexedNotNull { rowIndex, row ->
        if (row.all { it.isBlank() }) return@mapIndexedNotNull null
        val old = existingCards.getOrNull(rowIndex)
        val cardId = old?.id ?: UUID.randomUUID().toString()
        val pastedImage = value(row, imageIndex)
        CtsCard(
            id = cardId,
            badgePrimary = value(row, badgeIndex),
            badgeSecondary = value(row, labelIndex),
            title = value(row, titleIndex),
            description = value(row, descriptionIndex),
            imageSubcard = ImageSubcard(
                id = old?.imageSubcard?.id ?: UUID.randomUUID().toString(),
                parentCardId = cardId,
                source = pastedImage.takeIf { it.isNotBlank() } ?: old?.imageSubcard?.source,
                transform = old?.imageSubcard?.transform ?: NormalizedRect.Full,
            ),
        )
    }
    require(cards.isNotEmpty()) { "The table contains no nonblank cards." }
    return cards
}

private fun parseDelimitedLineV2(line: String, delimiter: Char): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == delimiter && !quoted -> {
                cells += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    cells += current.toString()
    return cells
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment ?: "Soundtrack" }
}

private fun exportFileName(project: CtsProject): String {
    val safe = project.name.trim()
        .replace(Regex("[^A-Za-z0-9._ -]+"), "_")
        .trim(' ', '.', '_')
        .ifBlank { "CTS comparison" }
    return if (safe.endsWith(".mp4", ignoreCase = true)) safe else "$safe.mp4"
}
