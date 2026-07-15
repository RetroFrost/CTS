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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.persistence.ProjectJson
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.launch
import java.util.UUID

private enum class EditorTab(val label: String) {
    Data("Data"),
    Models("Models"),
    Audio("Audio"),
    Export("Export"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CtsAndroidApp() {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var project by remember { mutableStateOf(CtsProject().normalized()) }
    var selectedCardId by remember { mutableStateOf(project.cards.firstOrNull()?.id) }
    var positionSeconds by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(EditorTab.Data) }
    var showInsertDialog by remember { mutableStateOf(false) }

    val duration = TimelineEngine.duration(project)

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun selectCard(cardId: String) {
        selectedCardId = cardId
        val index = project.cards.indexOfFirst { it.id == cardId }
        if (index >= 0) positionSeconds = TimelineEngine.editingTimeForCard(project, index)
        isPlaying = false
    }

    fun updateSelectedCard(update: (CtsCard) -> CtsCard) {
        val id = selectedCardId ?: return
        project = project.updateCard(id, update)
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
            card.copy(imageSubcard = card.imageSubcard.copy(source = uri.toString()))
        }
        showMessage("Image attached without resetting its subcard transform")
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
            showMessage(error.message ?: "Could not open that CTS project")
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
            showMessage("Project saved in desktop-compatible .cts.json format")
        }.onFailure { error ->
            showMessage(error.message ?: "Could not save the project")
        }
    }

    LaunchedEffect(isPlaying, duration) {
        if (!isPlaying || duration <= 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isPlaying) {
            val now = withFrameNanos { it }
            val elapsed = (now - previous) / 1_000_000_000f
            previous = now
            val next = (positionSeconds + elapsed).coerceAtMost(duration)
            positionSeconds = next
            if (next >= duration) {
                isPlaying = false
                break
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("CTS Android", fontWeight = FontWeight.Black)
                        Text(
                            "StarterFreaks · native alpha",
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ProgramMonitor(
                project = project,
                positionSeconds = positionSeconds,
                selectedCardId = selectedCardId,
                onSelectCard = ::selectCard,
                onImageTransformChanged = { cardId, transform ->
                    project = project.updateCard(cardId) { card ->
                        card.copy(
                            imageSubcard = card.imageSubcard.copy(
                                transform = transform.clamped(),
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )

            TimelineControls(
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

            NavigationBar {
                EditorTab.entries.forEach { tab ->
                    val icon = when (tab) {
                        EditorTab.Data -> Icons.Filled.TableRows
                        EditorTab.Models -> Icons.Filled.Tune
                        EditorTab.Audio -> Icons.Filled.MusicNote
                        EditorTab.Export -> Icons.Filled.Movie
                    }
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (selectedTab) {
                    EditorTab.Data -> DataPanel(
                        project = project,
                        selectedCardId = selectedCardId,
                        onSelectCard = ::selectCard,
                        onProjectChanged = { updated ->
                            project = updated.normalized()
                            if (selectedCardId !in project.cards.map { it.id }) {
                                selectedCardId = project.cards.firstOrNull()?.id
                            }
                            positionSeconds = positionSeconds.coerceAtMost(TimelineEngine.duration(project))
                        },
                        onUpdateSelectedCard = ::updateSelectedCard,
                        onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
                        onInsertData = { showInsertDialog = true },
                    )

                    EditorTab.Models -> ModelsPanel(
                        project = project,
                        onProjectChanged = { updated ->
                            project = updated
                            positionSeconds = 0f
                            isPlaying = false
                        },
                    )

                    EditorTab.Audio -> AudioPanel()
                    EditorTab.Export -> ExportPanel(
                        project = project,
                        onOpen = { openProject.launch(arrayOf("application/json", "text/json", "*/*")) },
                        onSave = { saveProject.launch("comparison-project.cts.json") },
                    )
                }
            }
        }
    }

    if (showInsertDialog) {
        InsertDataDialog(
            existingCards = project.cards,
            onDismiss = { showInsertDialog = false },
            onApply = { cards ->
                project = project.copy(cards = cards).normalized()
                selectedCardId = project.cards.firstOrNull()?.id
                positionSeconds = 0f
                isPlaying = false
                showInsertDialog = false
                showMessage("Inserted ${cards.size} cards")
            },
        )
    }
}

@Composable
private fun TimelineControls(
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
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
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
private fun DataPanel(
    project: CtsProject,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onProjectChanged: (CtsProject) -> Unit,
    onUpdateSelectedCard: ((CtsCard) -> CtsCard) -> Unit,
    onChooseImage: () -> Unit,
    onInsertData: () -> Unit,
) {
    val selectedCard = project.cards.firstOrNull { it.id == selectedCardId }

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
                    .height(54.dp),
            ) {
                Icon(Icons.Filled.TableRows, contentDescription = null)
                Spacer(Modifier.size(9.dp))
                Text("Click to Insert Data", fontWeight = FontWeight.Black)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        selectedCardId?.let { onProjectChanged(project.duplicateCard(it)) }
                    },
                    enabled = selectedCard != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Text("Duplicate")
                }
                FilledTonalButton(
                    onClick = {
                        selectedCardId?.let { onProjectChanged(project.removeCard(it)) }
                    },
                    enabled = selectedCard != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("Delete")
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(project.cards, key = { _, card -> card.id }) { index, card ->
                    FilterChip(
                        selected = card.id == selectedCardId,
                        onClick = { onSelectCard(card.id) },
                        label = {
                            Text(
                                "${index + 1}. ${card.title.ifBlank { "Untitled" }}",
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
                HorizontalDivider()
                Text(
                    "Parent card",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                OutlinedTextField(
                    value = selectedCard.badgePrimary,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(badgePrimary = value) } },
                    label = { Text("Badge value / date") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = selectedCard.badgeSecondary,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(badgeSecondary = value) } },
                    label = { Text("Badge label / unit") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item {
                OutlinedTextField(
                    value = selectedCard.title,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(title = value) } },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                OutlinedTextField(
                    value = selectedCard.description,
                    onValueChange = { value -> onUpdateSelectedCard { it.copy(description = value) } },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }

            item {
                Text(
                    "Image subcard · child of this parent only",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Drag the image in the Program Monitor to move it. Drag a purple corner to resize it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onChooseImage,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Text(if (selectedCard.imageSubcard.source.isNullOrBlank()) "Choose image" else "Replace image")
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
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                        Text("Reset frame")
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ModelsPanel(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Visual model", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Switching models keeps the card data and image-child transforms.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(VisualModel.entries.size) { index ->
            val model = VisualModel.entries[index]
            ElevatedCard(
                onClick = { onProjectChanged(project.copy(model = model)) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (project.model == model) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = project.model == model,
                        onClick = { onProjectChanged(project.copy(model = model)) },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(model.label, fontWeight = FontWeight.Bold)
                        Text(
                            "${model.visibleCards} cards on screen · each parent owns one image subcard",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show hexagons", fontWeight = FontWeight.Bold)
                        Text(
                            "The image subcard remains attached to its parent either way.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = project.showHexagons,
                        onCheckedChange = { onProjectChanged(project.copy(showHexagons = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPanel() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(52.dp))
            Text("Soundtrack editor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "The first Android alpha concentrates on the renderer, data flow, and parent → child image architecture. Multi-track audio is the next editor module.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExportPanel(
    project: CtsProject,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Project & export", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Projects are saved as desktop-compatible .cts.json files.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Text("Save project")
                }
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Text("Open project")
                }
            }
        }
        item {
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text("Native MP4 renderer", fontWeight = FontWeight.Bold)
                    Text(
                        "Scene graph: ready\nTimeline preview: ready\nParent/image-subcard transforms: ready\nMediaCodec frame encoder: next milestone",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = {}, enabled = false) {
                        Icon(Icons.Filled.Movie, contentDescription = null)
                        Text("Export MP4 · coming next")
                    }
                }
            }
        }
        item {
            Text(
                "${project.cards.size} cards · ${TimelineEngine.formatTime(TimelineEngine.duration(project))} automatic duration",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun InsertDataDialog(
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
        title = { Text("Click to Insert Data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a complete table. Tabs are best; pipes, semicolons, and CSV are also recognized. Existing image transforms are preserved by row.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    label = { Text("Badge · Title · Description · Image") },
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { parseCards(text, existingCards) }
                        .onSuccess(onApply)
                        .onFailure { error = it.message ?: "Could not parse this table." }
                },
            ) {
                Text("Insert cards")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun parseCards(text: String, existingCards: List<CtsCard>): List<CtsCard> {
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
    val matrix = lines.map { parseDelimitedLine(it, delimiter) }
    val first = matrix.first().map { it.trim().lowercase() }
    val knownHeaders = setOf(
        "badge", "value", "date", "year", "title", "name", "description",
        "details", "image", "artwork", "label", "unit",
    )
    val hasHeader = first.any { it in knownHeaders }
    val headers = if (hasHeader) first else listOf("badge", "title", "description", "image", "label")
    val rows = if (hasHeader) matrix.drop(1) else matrix

    fun index(vararg names: String): Int = headers.indexOfFirst { it in names }
    val badgeIndex = index("badge", "value", "date", "year")
    val labelIndex = index("label", "unit")
    val titleIndex = index("title", "name")
    val descriptionIndex = index("description", "details")
    val imageIndex = index("image", "artwork")

    fun value(row: List<String>, column: Int): String =
        if (column in row.indices) row[column].trim() else ""

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
                source = pastedImage.ifBlank { old?.imageSubcard?.source },
                transform = old?.imageSubcard?.transform ?: NormalizedRect.Full,
            ),
        )
    }

    require(cards.isNotEmpty()) { "The table contains no nonblank cards." }
    return cards
}

private fun parseDelimitedLine(line: String, delimiter: Char): List<String> {
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
