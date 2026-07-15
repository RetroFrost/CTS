package io.github.retrofrost.cts.android.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
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
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.persistence.ProjectJson
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.launch
import java.util.UUID

private enum class GoogleEditorDestination(
    val label: String,
) {
    Edit("Edit"),
    Style("Style"),
    Audio("Audio"),
    Export("Export"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleCtsApp() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var project by remember { mutableStateOf(CtsProject().normalized()) }
    var selectedCardId by remember { mutableStateOf(project.cards.firstOrNull()?.id) }
    var positionSeconds by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(GoogleEditorDestination.Edit) }
    var showInsertDialog by remember { mutableStateOf(false) }

    val durationSeconds = TimelineEngine.duration(project)

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun selectCard(cardId: String) {
        selectedCardId = cardId
        val index = project.cards.indexOfFirst { it.id == cardId }
        if (index >= 0) {
            positionSeconds = TimelineEngine.editingTimeForCard(project, index)
        }
        isPlaying = false
    }

    fun updateSelectedCard(update: (CtsCard) -> CtsCard) {
        val cardId = selectedCardId ?: return
        project = project.updateCard(cardId, update)
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        updateSelectedCard { card ->
            card.copy(
                imageSubcard = card.imageSubcard.copy(source = uri.toString()),
            )
        }
        showMessage("Image updated")
    }

    val openProject = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("The selected project could not be read.")
            project = ProjectJson.decode(text)
            selectedCardId = project.cards.firstOrNull()?.id
            positionSeconds = 0f
            isPlaying = false
        }.onSuccess {
            showMessage("Project opened")
        }.onFailure { error ->
            showMessage(error.message ?: "Could not open this project")
        }
    }

    val saveProject = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)
                ?.bufferedWriter()
                ?.use { it.write(ProjectJson.encode(project)) }
                ?: error("The selected destination could not be written.")
        }.onSuccess {
            showMessage("Project saved")
        }.onFailure { error ->
            showMessage(error.message ?: "Could not save this project")
        }
    }

    LaunchedEffect(isPlaying, durationSeconds) {
        if (!isPlaying || durationSeconds <= 0f) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (isPlaying) {
            val currentFrame = withFrameNanos { it }
            val elapsed = (currentFrame - previousFrame) / 1_000_000_000f
            previousFrame = currentFrame
            val next = (positionSeconds + elapsed).coerceAtMost(durationSeconds)
            positionSeconds = next
            if (next >= durationSeconds) {
                isPlaying = false
                break
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "CTS",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            openProject.launch(arrayOf("application/json", "text/json", "*/*"))
                        },
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Open project")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { saveProject.launch("comparison-project.cts.json") },
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save project")
                    }
                    IconButton(onClick = { showMessage("CTS Android alpha") }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                GoogleEditorDestination.entries.forEach { item ->
                    val icon = when (item) {
                        GoogleEditorDestination.Edit -> Icons.Filled.Edit
                        GoogleEditorDestination.Style -> Icons.Filled.Palette
                        GoogleEditorDestination.Audio -> Icons.Filled.MusicNote
                        GoogleEditorDestination.Export -> Icons.Filled.Movie
                    }
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (destination == GoogleEditorDestination.Edit) {
                ExtendedFloatingActionButton(
                    onClick = { showInsertDialog = true },
                    icon = { Icon(Icons.Filled.TableRows, contentDescription = null) },
                    text = { Text("Paste data") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PreviewSurface(
                project = project,
                positionSeconds = positionSeconds,
                durationSeconds = durationSeconds,
                isPlaying = isPlaying,
                selectedCardId = selectedCardId,
                onSelectCard = ::selectCard,
                onTransformChanged = { cardId, transform ->
                    project = project.updateCard(cardId) { card ->
                        card.copy(
                            imageSubcard = card.imageSubcard.copy(
                                transform = transform.clamped(),
                            ),
                        )
                    }
                },
                onPlayPause = {
                    if (positionSeconds >= durationSeconds) positionSeconds = 0f
                    isPlaying = !isPlaying
                },
                onSeek = {
                    isPlaying = false
                    positionSeconds = it
                },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (destination) {
                    GoogleEditorDestination.Edit -> GoogleDataEditor(
                        project = project,
                        selectedCardId = selectedCardId,
                        onSelectCard = ::selectCard,
                        onProjectChanged = { updated ->
                            project = updated.normalized()
                            if (selectedCardId !in project.cards.map { it.id }) {
                                selectedCardId = project.cards.firstOrNull()?.id
                            }
                            positionSeconds = positionSeconds.coerceAtMost(
                                TimelineEngine.duration(project),
                            )
                        },
                        onUpdateSelectedCard = ::updateSelectedCard,
                        onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
                    )

                    GoogleEditorDestination.Style -> GoogleStyleEditor(
                        project = project,
                        onProjectChanged = { updated ->
                            project = updated
                            positionSeconds = 0f
                            isPlaying = false
                        },
                    )

                    GoogleEditorDestination.Audio -> GoogleEmptyState(
                        icon = Icons.Filled.MusicNote,
                        title = "Add a soundtrack",
                        body = "Audio mixing comes after the Android renderer and export pipeline.",
                    )

                    GoogleEditorDestination.Export -> GoogleExportEditor(
                        project = project,
                        onSave = { saveProject.launch("comparison-project.cts.json") },
                    )
                }
            }
        }
    }

    if (showInsertDialog) {
        GoogleInsertDataDialog(
            existingCards = project.cards,
            onDismiss = { showInsertDialog = false },
            onApply = { cards ->
                project = project.copy(cards = cards).normalized()
                selectedCardId = project.cards.firstOrNull()?.id
                positionSeconds = 0f
                isPlaying = false
                showInsertDialog = false
                showMessage("${cards.size} cards inserted")
            },
        )
    }
}

@Composable
private fun PreviewSurface(
    project: CtsProject,
    positionSeconds: Float,
    durationSeconds: Float,
    isPlaying: Boolean,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onTransformChanged: (String, NormalizedRect) -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
    ) {
        Column {
            ProgramMonitor(
                project = project,
                positionSeconds = positionSeconds,
                selectedCardId = selectedCardId,
                onSelectCard = onSelectCard,
                onImageTransformChanged = onTransformChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
                Slider(
                    value = positionSeconds.coerceIn(
                        0f,
                        durationSeconds.coerceAtLeast(0.001f),
                    ),
                    onValueChange = onSeek,
                    valueRange = 0f..durationSeconds.coerceAtLeast(0.001f),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${TimelineEngine.formatTime(positionSeconds)} / ${TimelineEngine.formatTime(durationSeconds)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GoogleDataEditor(
    project: CtsProject,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onProjectChanged: (CtsProject) -> Unit,
    onUpdateSelectedCard: ((CtsCard) -> CtsCard) -> Unit,
    onChooseImage: () -> Unit,
) {
    val selectedCard = project.cards.firstOrNull { it.id == selectedCardId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cards",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${project.cards.size} items in this comparison",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(
                    onClick = {
                        val updated = project.addBlankCard()
                        onProjectChanged(updated)
                        updated.cards.lastOrNull()?.id?.let(onSelectCard)
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add card")
                }
                Spacer(Modifier.size(6.dp))
                FilledTonalIconButton(
                    onClick = {
                        selectedCardId?.let { onProjectChanged(project.duplicateCard(it)) }
                    },
                    enabled = selectedCard != null,
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate card")
                }
                Spacer(Modifier.size(6.dp))
                FilledTonalIconButton(
                    onClick = {
                        selectedCardId?.let { onProjectChanged(project.removeCard(it)) }
                    },
                    enabled = selectedCard != null,
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete card")
                }
            }
        }

        item {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(project.cards, key = { _, card -> card.id }) { index, card ->
                    FilterChip(
                        selected = card.id == selectedCardId,
                        onClick = { onSelectCard(card.id) },
                        label = {
                            Text(
                                text = "${index + 1}  ${card.title.ifBlank { "Untitled" }}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        if (selectedCard != null) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider()
                    Text(
                        text = "Card details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextField(
                        value = selectedCard.title,
                        onValueChange = { value ->
                            onUpdateSelectedCard { it.copy(title = value) }
                        },
                        label = { Text("Title") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    )
                    TextField(
                        value = selectedCard.description,
                        onValueChange = { value ->
                            onUpdateSelectedCard { it.copy(description = value) }
                        },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        shape = RoundedCornerShape(16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            value = selectedCard.badgePrimary,
                            onValueChange = { value ->
                                onUpdateSelectedCard { it.copy(badgePrimary = value) }
                            },
                            label = { Text("Value") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                        TextField(
                            value = selectedCard.badgeSecondary,
                            onValueChange = { value ->
                                onUpdateSelectedCard { it.copy(badgeSecondary = value) }
                            },
                            label = { Text("Label") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Image subcard",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Moves and appears with this parent card only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onChooseImage,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (selectedCard.imageSubcard.source.isNullOrBlank()) {
                                        "Choose image"
                                    } else {
                                        "Replace image"
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    onUpdateSelectedCard { card ->
                                        card.copy(
                                            imageSubcard = card.imageSubcard.copy(
                                                transform = NormalizedRect.Full,
                                            ),
                                        )
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Reset")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }
    }
}

@Composable
private fun GoogleStyleEditor(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = "Style",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }

        items(VisualModel.entries) { model ->
            Surface(
                onClick = { onProjectChanged(project.copy(model = model)) },
                shape = RoundedCornerShape(20.dp),
                color = if (project.model == model) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            ) {
                ListItem(
                    headlineContent = {
                        Text(model.label, fontWeight = FontWeight.Medium)
                    },
                    supportingContent = {
                        Text("${model.visibleCards} cards visible at once")
                    },
                    leadingContent = {
                        RadioButton(
                            selected = project.model == model,
                            onClick = { onProjectChanged(project.copy(model = model)) },
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                ListItem(
                    headlineContent = { Text("Show hexagons") },
                    supportingContent = { Text("Keep the CTS badge shape in the preview") },
                    trailingContent = {
                        Switch(
                            checked = project.showHexagons,
                            onCheckedChange = {
                                onProjectChanged(project.copy(showHexagons = it))
                            },
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun GoogleExportEditor(
    project: CtsProject,
    onSave: () -> Unit,
) {
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
                text = "${project.cards.size} cards · ${TimelineEngine.formatTime(TimelineEngine.duration(project))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Save project",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Keep editing on Android or open the same .cts.json project on desktop CTS.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSave) {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Save .cts.json")
                    }
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("MP4 export", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Native MediaCodec export is the next renderer milestone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoogleEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(20.dp).size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GoogleInsertDataDialog(
    existingCards: List<CtsCard>,
    onDismiss: () -> Unit,
    onApply: (List<CtsCard>) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            "Badge\tTitle\tDescription\tImage\n" +
                existingCards.joinToString("\n") { card ->
                    listOf(
                        card.badgePrimary,
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
        icon = { Icon(Icons.Filled.TableRows, contentDescription = null) },
        title = { Text("Paste comparison data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste tab-separated rows from Sheets, or use pipes, semicolons, or CSV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    label = { Text("Badge · Title · Description · Image") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    shape = RoundedCornerShape(18.dp),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { parseGoogleCards(text, existingCards) }
                        .onSuccess(onApply)
                        .onFailure { error = it.message ?: "Could not parse this table" }
                },
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun parseGoogleCards(
    text: String,
    existingCards: List<CtsCard>,
): List<CtsCard> {
    val lines = text.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
    require(lines.isNotEmpty()) { "Paste at least one row." }

    val delimiter = when {
        lines.first().contains('\t') -> '\t'
        lines.first().contains('|') -> '|'
        lines.first().contains(';') -> ';'
        else -> ','
    }
    val matrix = lines.map { parseGoogleDelimitedLine(it, delimiter) }
    val first = matrix.first().map { it.trim().lowercase() }
    val knownHeaders = setOf(
        "badge", "value", "date", "year", "title", "name", "description",
        "details", "image", "artwork", "label", "unit",
    )
    val hasHeader = first.any { it in knownHeaders }
    val headers = if (hasHeader) {
        first
    } else {
        listOf("badge", "title", "description", "image", "label")
    }
    val rows = if (hasHeader) matrix.drop(1) else matrix

    fun column(vararg names: String): Int = headers.indexOfFirst { it in names }
    val badgeColumn = column("badge", "value", "date", "year")
    val labelColumn = column("label", "unit")
    val titleColumn = column("title", "name")
    val descriptionColumn = column("description", "details")
    val imageColumn = column("image", "artwork")

    fun cell(row: List<String>, index: Int): String =
        if (index in row.indices) row[index].trim() else ""

    val cards = rows.mapIndexedNotNull { rowIndex, row ->
        if (row.all { it.isBlank() }) return@mapIndexedNotNull null
        val previous = existingCards.getOrNull(rowIndex)
        val cardId = previous?.id ?: UUID.randomUUID().toString()
        val image = cell(row, imageColumn)

        CtsCard(
            id = cardId,
            badgePrimary = cell(row, badgeColumn),
            badgeSecondary = cell(row, labelColumn),
            title = cell(row, titleColumn),
            description = cell(row, descriptionColumn),
            imageSubcard = ImageSubcard(
                id = previous?.imageSubcard?.id ?: UUID.randomUUID().toString(),
                parentCardId = cardId,
                source = image.ifBlank { previous?.imageSubcard?.source },
                transform = previous?.imageSubcard?.transform ?: NormalizedRect.Full,
            ),
        )
    }

    require(cards.isNotEmpty()) { "The table contains no cards." }
    return cards
}

private fun parseGoogleDelimitedLine(
    line: String,
    delimiter: Char,
): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0

    while (index < line.length) {
        val character = line[index]
        when {
            character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            character == '"' -> quoted = !quoted
            character == delimiter && !quoted -> {
                cells += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
        index++
    }
    cells += current.toString()
    return cells
}
