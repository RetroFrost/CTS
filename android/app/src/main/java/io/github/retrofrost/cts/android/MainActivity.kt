package io.github.retrofrost.cts.android

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CubicalCompareApp() }
    }
}

private val CubicalDarkColours = darkColorScheme(
    primary = Color(0xFFFF5964), onPrimary = Color(0xFF330408),
    primaryContainer = Color(0xFF5A1720), onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFD7C1C2), background = Color(0xFF0B0B0D), surface = Color(0xFF111114),
    surfaceVariant = Color(0xFF242329), onBackground = Color(0xFFF7EDEA), onSurface = Color(0xFFF7EDEA),
    outline = Color(0xFF514D54),
)

private val CubicalLightColours = lightColorScheme(
    primary = Color(0xFF8F1D2C), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADB), onPrimaryContainer = Color(0xFF3B0710),
    secondary = Color(0xFF765657), background = Color(0xFFFFF8F7), surface = Color(0xFFFFF8F7),
    surfaceVariant = Color(0xFFF4DDDE), onBackground = Color(0xFF251819), onSurface = Color(0xFF251819),
    outline = Color(0xFF857374),
)

private enum class StudioTab(val title: String, val icon: ImageVector) {
    CARDS("Cards", Icons.Rounded.List),
    TIMELINE("Timeline", Icons.Rounded.Timeline),
    SETTINGS("Settings", Icons.Rounded.Settings),
    TOOLS("Tools", Icons.Rounded.Build),
    FAQ("FAQ", Icons.Rounded.HelpOutline),
}

private data class AccuracyState(val label: String, val detail: String, val exact: Boolean)

private fun accuracyState(project: StudioProject, spec: RendererSpec = RendererRuntime.active): AccuracyState {
    if (spec.precisionMode != "frame-exact") {
        return AccuracyState("ADAPTIVE", "${spec.name} uses interpolated/adaptive rendering.", false)
    }
    val comparisonIssues = mutableListOf<String>()
    if (project.width != spec.referenceWidth || project.height != spec.referenceHeight) comparisonIssues += "resolution changed"
    if (project.fps != spec.referenceFps) comparisonIssues += "frame rate changed"
    if (spec.canonicalCardCount > 0 && project.cards.size != spec.canonicalCardCount) comparisonIssues += "card count changed"
    if (!project.autoLength) comparisonIssues += "custom duration"
    if (comparisonIssues.isNotEmpty()) {
        return AccuracyState("MODIFIED", comparisonIssues.joinToString(", "), false)
    }
    return when (project.introMode) {
        IntroMode.RENDERER -> AccuracyState("PIXEL EXACT ✓", "Canonical renderer settings are intact.", true)
        IntroMode.CUSTOM -> AccuracyState("COMPARISON EXACT", "Comparison frames stay exact; the renderer intro is replaced by your MP4.", true)
        IntroMode.DISABLED -> AccuracyState("COMPARISON EXACT", "Comparison frames stay exact; renderer intro frames are removed.", true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CubicalCompareApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var project by remember { mutableStateOf(ProjectAutosave.load(context) ?: StudioProject()) }
    var selectedTab by remember { mutableStateOf(StudioTab.CARDS) }
    var selectedCard by remember { mutableIntStateOf(0) }
    var metadata by remember { mutableStateOf(RenderMetadata(1, 0.0, 60)) }
    var metadataLoading by remember { mutableStateOf(true) }
    var autosaveLabel by remember { mutableStateOf("Loaded") }
    var undoStack by remember { mutableStateOf<List<StudioProject>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<StudioProject>>(emptyList()) }
    val exportProgress by ExportState.state.collectAsState()

    fun report(error: Throwable) {
        scope.launch { snackbar.showSnackbar(error.message ?: "Something went wrong.") }
    }

    fun applyProject(next: StudioProject, recordHistory: Boolean = true) {
        if (next == project) return
        if (recordHistory) {
            undoStack = (undoStack + project).takeLast(80)
            redoStack = emptyList()
        }
        project = next
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + project).takeLast(80)
        project = previous
        selectedCard = selectedCard.coerceAtMost(previous.cards.lastIndex)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + project).takeLast(80)
        project = next
        selectedCard = selectedCard.coerceAtMost(next.cards.lastIndex)
    }

    LaunchedEffect(project) {
        metadataLoading = true
        runCatching { withContext(Dispatchers.Default) { RendererBridge.metadata(project) } }
            .onSuccess { metadata = it }
            .onFailure(::report)
        metadataLoading = false
    }

    LaunchedEffect(project) {
        autosaveLabel = "Saving…"
        delay(500)
        runCatching { withContext(Dispatchers.IO) { ProjectAutosave.save(context, project) } }
            .onSuccess { autosaveLabel = "Autosaved" }
            .onFailure { autosaveLabel = "Autosave failed"; report(it) }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("The project could not be opened.")
                }
                StudioProject.fromJson(text)
            }.onSuccess { applyProject(it); selectedCard = 0 }.onFailure(::report)
        }
    }
    var pendingProjectSave by remember { mutableStateOf<StudioProject?>(null) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val value = pendingProjectSave
        if (uri != null && value != null) scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(value.toJson()) }
                    ?: error("The project could not be saved.")
            }.onFailure(::report)
        }
    }
    val importData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    RendererBridge.importData(project, RendererBridge.materialize(context, uri, "data").absolutePath)
                }
            }.onSuccess { applyProject(it); selectedCard = 0 }.onFailure(::report)
        }
    }
    val importMegaPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val pack = RendererBridge.materialize(context, uri, "megapack")
                    val assets = File(context.filesDir, "megapacks/${System.nanoTime()}")
                    RendererBridge.importMegaPack(pack.absolutePath, assets).copyUiSettingsFrom(project)
                }
            }.onSuccess { applyProject(it); selectedCard = 0 }.onFailure(::report)
        }
    }
    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedCard in project.cards.indices) scope.launch {
            runCatching { withContext(Dispatchers.IO) { RendererBridge.materialize(context, uri, "artwork") } }
                .onSuccess { file ->
                    val cards = project.cards.toMutableList()
                    cards[selectedCard] = cards[selectedCard].copy(image = file.absolutePath)
                    applyProject(project.copy(cards = cards))
                }.onFailure(::report)
        }
    }
    val chooseIntro = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { RendererBridge.materialize(context, uri, "intro") } }
                .onSuccess { file ->
                    IntroVideoSource.clear()
                    applyProject(project.copy(introMode = IntroMode.CUSTOM, introVideo = file.absolutePath))
                }.onFailure(::report)
        }
    }
    val chooseSoundtrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            applyProject(project.copy(soundtrack = uri.toString()))
        }
    }
    var pendingExport by remember { mutableStateOf<StudioProject?>(null) }
    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val value = pendingExport
        if (uri != null && value != null) ExportService.start(context, value, uri)
    }

    val darkTheme = isSystemInDarkTheme()
    val colourScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> CubicalDarkColours
        else -> CubicalLightColours
    }
    val accuracy = accuracyState(project)

    MaterialTheme(colorScheme = colourScheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Cubical Compare", fontWeight = FontWeight.SemiBold)
                                Text("2.0.7 • $autosaveLabel • ${accuracy.label}", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        actions = {
                            IconButton(onClick = ::undo, enabled = undoStack.isNotEmpty()) { Icon(Icons.Rounded.Undo, "Undo") }
                            IconButton(onClick = ::redo, enabled = redoStack.isNotEmpty()) { Icon(Icons.Rounded.Redo, "Redo") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                    ScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 8.dp) {
                        StudioTab.entries.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                text = { Text(tab.title) },
                                icon = { Icon(tab.icon, null) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            when (selectedTab) {
                StudioTab.CARDS -> CardsTab(
                    project, selectedCard, ::applyProject, { selectedCard = it },
                    { chooseImage.launch(arrayOf("image/*")) }, Modifier.padding(padding),
                )
                StudioTab.TIMELINE -> TimelineTab(
                    project, metadata, metadataLoading, accuracy, ::applyProject, Modifier.padding(padding),
                )
                StudioTab.SETTINGS -> SettingsTab(
                    project = project,
                    metadata = metadata,
                    accuracy = accuracy,
                    exportProgress = exportProgress,
                    onProjectChange = ::applyProject,
                    onChooseIntro = { chooseIntro.launch(arrayOf("video/mp4", "video/*")) },
                    onChooseSoundtrack = { chooseSoundtrack.launch(arrayOf("audio/*")) },
                    onExport = {
                        pendingExport = project
                        exportVideo.launch("Cubical-Compare-${safeName(project.name)}-2.0.7.mp4")
                    },
                    onCancelExport = { ExportService.cancel(context) },
                    modifier = Modifier.padding(padding),
                )
                StudioTab.TOOLS -> ToolsTab(
                    project = project,
                    onNew = { applyProject(StudioProject()); selectedCard = 0; selectedTab = StudioTab.CARDS },
                    onOpen = { openProject.launch(arrayOf("application/json", "text/json", "*/*")) },
                    onSave = {
                        pendingProjectSave = project
                        saveProject.launch("${safeName(project.name)}.ccproject.json")
                    },
                    onImportData = {
                        importData.launch(arrayOf("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel"))
                    },
                    onImportMegaPack = { importMegaPack.launch(arrayOf("application/zip", "*/*")) },
                    onImportRenderer = { context.startActivity(Intent(context, RendererImportActivity::class.java)) },
                    onRendererLibrary = { context.startActivity(Intent(context, RendererManagerActivity::class.java)) },
                    modifier = Modifier.padding(padding),
                )
                StudioTab.FAQ -> FaqTab(Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun CardsTab(
    project: StudioProject,
    selectedCard: Int,
    onProjectChange: (StudioProject) -> Unit,
    onSelectedCardChange: (Int) -> Unit,
    onChooseImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = project.cards.getOrNull(selectedCard)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedTextField(
                value = project.name,
                onValueChange = { onProjectChange(project.copy(name = it)) },
                label = { Text("Comparison name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Cards", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val next = project.cards + StudioCard(title = "Card ${project.cards.size + 1}")
                    onProjectChange(project.copy(cards = next)); onSelectedCardChange(next.lastIndex)
                }) { Icon(Icons.Rounded.Add, "Add card") }
                IconButton(enabled = card != null, onClick = {
                    if (card != null) {
                        val copy = card.copy(id = java.util.UUID.randomUUID().toString().replace("-", ""))
                        val next = project.cards.toMutableList().apply { add(selectedCard + 1, copy) }
                        onProjectChange(project.copy(cards = next)); onSelectedCardChange(selectedCard + 1)
                    }
                }) { Icon(Icons.Rounded.ContentCopy, "Duplicate card") }
                IconButton(enabled = project.cards.size > 1 && card != null, onClick = {
                    val next = project.cards.toMutableList().apply { removeAt(selectedCard) }
                    onProjectChange(project.copy(cards = next)); onSelectedCardChange(selectedCard.coerceAtMost(next.lastIndex))
                }) { Icon(Icons.Rounded.Delete, "Delete card") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(project.cards, key = { _, item -> item.id }) { index, item ->
                    FilterChip(
                        selected = selectedCard == index,
                        onClick = { onSelectedCardChange(index) },
                        label = { Text("${index + 1} · ${item.title.ifBlank { "Untitled" }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        if (card != null) {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Card ${selectedCard + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            IconButton(enabled = selectedCard > 0, onClick = {
                                val next = project.cards.toMutableList()
                                java.util.Collections.swap(next, selectedCard, selectedCard - 1)
                                onProjectChange(project.copy(cards = next)); onSelectedCardChange(selectedCard - 1)
                            }) { Icon(Icons.Rounded.KeyboardArrowLeft, "Move left") }
                            IconButton(enabled = selectedCard < project.cards.lastIndex, onClick = {
                                val next = project.cards.toMutableList()
                                java.util.Collections.swap(next, selectedCard, selectedCard + 1)
                                onProjectChange(project.copy(cards = next)); onSelectedCardChange(selectedCard + 1)
                            }) { Icon(Icons.Rounded.KeyboardArrowRight, "Move right") }
                        }
                        CardTextField("Title", card.title) { updateCard(project, selectedCard, card.copy(title = it), onProjectChange) }
                        CardTextField("Badge header", card.badgeHeader) { updateCard(project, selectedCard, card.copy(badgeHeader = it), onProjectChange) }
                        CardTextField("Badge value", card.value) { updateCard(project, selectedCard, card.copy(value = it), onProjectChange) }
                        CardTextField("Description", card.description, false) { updateCard(project, selectedCard, card.copy(description = it), onProjectChange) }
                        FilledTonalButton(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text(if (card.image.isBlank()) "Choose artwork" else "Change artwork")
                        }
                        if (card.image.isNotBlank()) {
                            ArtworkPreview(
                                card = card,
                                onTransform = { transformed ->
                                    updateCard(project, selectedCard, transformed, onProjectChange)
                                },
                            )
                            Text(card.image.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium)
                        }
                        Text("Artwork transform", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TransformSlider("Scale", card.imageScale.toFloat(), 0.10f..6f, "${"%.2f".format(card.imageScale)}×") {
                            updateCard(project, selectedCard, card.copy(imageScale = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Horizontal", card.imageX.toFloat(), -600f..600f, "${card.imageX.roundToInt()} px") {
                            updateCard(project, selectedCard, card.copy(imageX = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Vertical", card.imageY.toFloat(), -800f..800f, "${card.imageY.roundToInt()} px") {
                            updateCard(project, selectedCard, card.copy(imageY = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Rotation", card.imageRotation.toFloat(), -180f..180f, "${card.imageRotation.roundToInt()}°") {
                            updateCard(project, selectedCard, card.copy(imageRotation = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Crop left", card.imageCropLeft.toFloat(), 0f..0.45f, "${(card.imageCropLeft * 100).roundToInt()}%") {
                            updateCard(project, selectedCard, card.copy(imageCropLeft = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Crop right", card.imageCropRight.toFloat(), 0f..0.45f, "${(card.imageCropRight * 100).roundToInt()}%") {
                            updateCard(project, selectedCard, card.copy(imageCropRight = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Crop top", card.imageCropTop.toFloat(), 0f..0.45f, "${(card.imageCropTop * 100).roundToInt()}%") {
                            updateCard(project, selectedCard, card.copy(imageCropTop = it.toDouble()), onProjectChange)
                        }
                        TransformSlider("Crop bottom", card.imageCropBottom.toFloat(), 0f..0.45f, "${(card.imageCropBottom * 100).roundToInt()}%") {
                            updateCard(project, selectedCard, card.copy(imageCropBottom = it.toDouble()), onProjectChange)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = card.imageLayer == "behind", onClick = { updateCard(project, selectedCard, card.copy(imageLayer = "behind"), onProjectChange) }, label = { Text("Behind badge") })
                            FilterChip(selected = card.imageLayer == "front", onClick = { updateCard(project, selectedCard, card.copy(imageLayer = "front"), onProjectChange) }, label = { Text("In front") })
                        }
                        OutlinedButton(onClick = {
                            updateCard(project, selectedCard, card.copy(
                                imageX = 0.0, imageY = 0.0, imageScale = 1.0, imageRotation = 0.0,
                                imageCropLeft = 0.0, imageCropTop = 0.0, imageCropRight = 0.0, imageCropBottom = 0.0,
                            ), onProjectChange)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Reset artwork transform") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ArtworkPreview(
    card: StudioCard,
    onTransform: (StudioCard) -> Unit,
) {
    val bitmap = remember(card.image) { runCatching { BitmapFactory.decodeFile(card.image) }.getOrNull() }
    val currentCard by rememberUpdatedState(card)
    val spec = RendererRuntime.active
    val referenceWidth = spec.bodyWidth.coerceAtLeast(1f)
    val referenceHeight = spec.imageHeight.coerceAtLeast(1f)
    val previewAspect = (referenceWidth / referenceHeight).coerceIn(0.45f, 1.4f)
    var transformMode by remember(card.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(previewAspect),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(card.id, transformMode, referenceWidth, referenceHeight) {
                    if (!transformMode) return@pointerInput
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val latest = currentCard
                        val boxWidth = size.width.toFloat().coerceAtLeast(1f)
                        val boxHeight = size.height.toFloat().coerceAtLeast(1f)
                        var nextRotation = latest.imageRotation + rotation.toDouble()
                        nextRotation %= 360.0
                        if (nextRotation > 180.0) nextRotation -= 360.0
                        if (nextRotation < -180.0) nextRotation += 360.0
                        onTransform(
                            latest.copy(
                                imageX = (latest.imageX + (pan.x / boxWidth * referenceWidth).toDouble()).coerceIn(-1200.0, 1200.0),
                                imageY = (latest.imageY + (pan.y / boxHeight * referenceHeight).toDouble()).coerceIn(-1600.0, 1600.0),
                                imageScale = (latest.imageScale * zoom.toDouble()).coerceIn(0.10, 6.0),
                                imageRotation = nextRotation,
                            ),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Transform artwork",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = card.imageScale.toFloat()
                            scaleY = card.imageScale.toFloat()
                            translationX = card.imageX.toFloat() / referenceWidth * size.width
                            translationY = card.imageY.toFloat() / referenceHeight * size.height
                            rotationZ = card.imageRotation.toFloat()
                        },
                    contentScale = ContentScale.Crop,
                )
            } ?: Text("Artwork unavailable")

            if (!transformMode) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FilledTonalButton(onClick = { transformMode = true }) {
                        Text("Tap to transform")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { transformMode = false }) { Text("Done") }
                    OutlinedButton(onClick = {
                        onTransform(
                            currentCard.copy(
                                imageX = 0.0,
                                imageY = 0.0,
                                imageScale = 1.0,
                                imageRotation = 0.0,
                            ),
                        )
                    }) { Text("Reset") }
                }
                Text(
                    "Drag to move • pinch to zoom • twist to rotate",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun TransformSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Text("$label $display")
    androidx.compose.material3.Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}

@Composable
private fun TimelineTab(
    project: StudioProject,
    metadata: RenderMetadata,
    metadataLoading: Boolean,
    accuracy: AccuracyState,
    onProjectChange: (StudioProject) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var durationText by remember(project.customLengthSeconds) { mutableStateOf(DurationFormat.formatPrecise(project.customLengthSeconds)) }

    LaunchedEffect(project, frame) {
        runCatching { withContext(Dispatchers.Default) { RendererBridge.render(project, frame, 640, 360) } }
            .onSuccess { old -> bitmap?.takeIf { it !== old }?.recycle(); bitmap = old }
    }
    LaunchedEffect(playing, speed, metadata.frameCount, metadata.fps) {
        while (playing) {
            val delayMs = (1000.0 / (metadata.fps.coerceAtLeast(1) * speed)).toLong().coerceAtLeast(4L)
            delay(delayMs)
            if (frame + 1 >= metadata.frameCount) { playing = false; frame = 0 } else frame += 1
        }
    }
    LaunchedEffect(metadata.frameCount) { frame = frame.coerceIn(0, (metadata.frameCount - 1).coerceAtLeast(0)) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Preview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            Card(Modifier.fillMaxWidth().aspectRatio(16f / 9f), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), "Video preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: Text("Rendering preview…")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { frame = (frame - 10).coerceAtLeast(0) }, enabled = !metadataLoading) { Text("−10") }
                OutlinedButton(onClick = { frame = (frame - 1).coerceAtLeast(0) }, enabled = !metadataLoading) { Text("−1") }
                FilledTonalButton(onClick = { playing = !playing }, enabled = !metadataLoading) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                }
                OutlinedButton(onClick = { frame = (frame + 1).coerceAtMost((metadata.frameCount - 1).coerceAtLeast(0)) }, enabled = !metadataLoading) { Text("+1") }
                OutlinedButton(onClick = { frame = (frame + 10).coerceAtMost((metadata.frameCount - 1).coerceAtLeast(0)) }, enabled = !metadataLoading) { Text("+10") }
            }
        }
        item {
            Text("Frame ${frame + 1} / ${metadata.frameCount} • ${DurationFormat.formatPrecise(frame.toDouble() / metadata.fps.coerceAtLeast(1))} / ${DurationFormat.formatPrecise(metadata.duration)}", style = MaterialTheme.typography.labelLarge)
            androidx.compose.material3.Slider(
                value = frame.toFloat(), onValueChange = { frame = it.roundToInt() },
                valueRange = 0f..(metadata.frameCount - 1).coerceAtLeast(1).toFloat(), enabled = !metadataLoading,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf(0.25f, 0.5f, 1f, 2f)) { _, option ->
                    FilterChip(selected = speed == option, onClick = { speed = option }, label = { Text("${option}×") })
                }
            }
        }
        item {
            SettingsCard("Timeline") {
                SettingValueRow("Intro", when (project.introMode) {
                    IntroMode.RENDERER -> "Renderer default"
                    IntroMode.CUSTOM -> "Custom MP4"
                    IntroMode.DISABLED -> "Disabled"
                })
                HorizontalDivider()
                SettingValueRow("Accuracy", accuracy.label)
                Text(accuracy.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard("Video length") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = project.autoLength, onClick = { onProjectChange(project.copy(autoLength = true)) }, label = { Text("Automatic") })
                    FilterChip(selected = !project.autoLength, onClick = { onProjectChange(project.copy(autoLength = false)) }, label = { Text("Custom") })
                }
                if (!project.autoLength) {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { value ->
                            durationText = value
                            DurationFormat.parse(value)?.let { onProjectChange(project.copy(customLengthSeconds = it.coerceAtLeast(15.0))) }
                        },
                        label = { Text("Length (MM:SS.sss)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, isError = DurationFormat.parse(durationText) == null, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Only continuous scrolling speed changes. Entry, badge, shine and outro timings remain renderer-defined.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsTab(
    project: StudioProject,
    metadata: RenderMetadata,
    accuracy: AccuracyState,
    exportProgress: ExportProgress,
    onProjectChange: (StudioProject) -> Unit,
    onChooseIntro: () -> Unit,
    onChooseSoundtrack: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = RendererRuntime.active
    val codecDescription = remember(project.encoderPreference, project.width, project.height, project.fps) {
        HardwareCodecSelector.describe(project.encoderPreference, project.width, project.height, project.fps)
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            SettingsCard("Renderer") {
                Text(spec.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${spec.engine} • ${spec.precisionMode} • API ${spec.rendererApi}", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(accuracy.label, fontWeight = FontWeight.Bold, color = if (accuracy.exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                Text(accuracy.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard("Intro") {
                Text("Use the renderer intro, replace it with your own MP4, or remove intro frames entirely.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(IntroMode.entries) { _, mode ->
                        FilterChip(selected = project.introMode == mode, onClick = { onProjectChange(project.copy(introMode = mode)) }, label = { Text(mode.displayName) })
                    }
                }
                when (project.introMode) {
                    IntroMode.RENDERER -> Text("Uses the active renderer's canonical intro.")
                    IntroMode.DISABLED -> Text("Export starts at the first comparison frame. No black replacement frames are inserted.")
                    IntroMode.CUSTOM -> {
                        Text(if (project.introVideo.isBlank()) "No MP4 selected." else project.introVideo.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onChooseIntro, modifier = Modifier.fillMaxWidth()) { Text(if (project.introVideo.isBlank()) "Choose MP4 intro" else "Replace MP4 intro") }
                        if (project.introVideo.isNotBlank()) {
                            OutlinedButton(onClick = { onProjectChange(project.copy(introVideo = "", introMode = IntroMode.RENDERER)) }, modifier = Modifier.fillMaxWidth()) { Text("Remove custom intro") }
                        }
                    }
                }
            }
        }
        item {
            SettingsCard("Video") {
                SettingValueRow("Resolution", "${project.width}×${project.height}")
                HorizontalDivider(); SettingValueRow("Frame rate", "${project.fps} FPS")
                HorizontalDivider(); SettingValueRow("Frames", metadata.frameCount.toString())
                HorizontalDivider(); SettingValueRow("Duration", DurationFormat.formatPrecise(metadata.duration))
            }
        }
        item {
            SettingsCard("Rendering") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("GPU rendering", fontWeight = FontWeight.SemiBold); Text("OpenGL ES • Direct encoder surface", style = MaterialTheme.typography.bodySmall) }
                    Text("Enabled", color = Color(0xFF80DF8B), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            SettingsCard("Video encoder") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EncoderPreference.entries.forEach { encoder ->
                        FilterChip(selected = project.encoderPreference == encoder, onClick = { onProjectChange(project.copy(encoderPreference = encoder)) }, label = { Text(encoder.displayName) }, modifier = Modifier.weight(1f))
                    }
                }
                Text(codecDescription, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SettingsCard("Project") {
                ToggleRow(Icons.Rounded.Badge, "Show badges", project.showBadges) { onProjectChange(project.copy(showBadges = it)) }
                HorizontalDivider()
                ToggleRow(Icons.Rounded.CreditScore, "Credits", project.creditsEnabled) { onProjectChange(project.copy(creditsEnabled = it)) }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.MusicNote, null); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text("Soundtrack", fontWeight = FontWeight.Medium); Text(if (project.soundtrack.isBlank()) "None selected" else "Selected audio file", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = onChooseSoundtrack) { Text(if (project.soundtrack.isBlank()) "Choose" else "Change") }
                }
                if (project.soundtrack.isNotBlank()) {
                    Text("Volume ${(project.soundtrackVolume * 100).roundToInt()}%")
                    androidx.compose.material3.Slider(value = project.soundtrackVolume, onValueChange = { onProjectChange(project.copy(soundtrackVolume = it)) }, valueRange = 0f..1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Loop soundtrack", modifier = Modifier.weight(1f)); Switch(project.soundtrackLoop, { onProjectChange(project.copy(soundtrackLoop = it)) })
                    }
                }
            }
        }
        item {
            SettingsCard("Export summary") {
                SettingValueRow("Output", "${project.width}×${project.height} • ${project.fps} FPS")
                SettingValueRow("Renderer", spec.name)
                SettingValueRow("Accuracy", accuracy.label)
                SettingValueRow("Encoder", codecDescription)
                SettingValueRow("Length", "${DurationFormat.formatPrecise(metadata.duration)} • ${metadata.frameCount} frames")
                SettingValueRow("Intro", project.introMode.displayName)
            }
        }
        if (exportProgress.running || exportProgress.stage != "Ready") {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(exportProgress.stage, fontWeight = FontWeight.SemiBold); Text(exportProgress.detail, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { exportProgress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        if (exportProgress.running) OutlinedButton(onClick = onCancelExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Cancel export") }
                    }
                }
            }
        }
        item {
            Button(onClick = onExport, enabled = !exportProgress.running && !(project.introMode == IntroMode.CUSTOM && project.introVideo.isBlank()), modifier = Modifier.fillMaxWidth().height(58.dp)) {
                Icon(Icons.Rounded.VideoFile, null); Spacer(Modifier.width(10.dp)); Text("Export video", style = MaterialTheme.typography.titleMedium)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ToolsTab(
    project: StudioProject,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onImportData: () -> Unit,
    onImportMegaPack: () -> Unit,
    onImportRenderer: () -> Unit,
    onRendererLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            SettingsCard("Project") {
                Text(project.name.ifBlank { "Untitled" })
                ToolButton("New project", Icons.Rounded.Add, onNew)
                ToolButton("Open project", Icons.Rounded.FileOpen, onOpen)
                ToolButton("Save project", Icons.Rounded.Save, onSave)
            }
        }
        item {
            SettingsCard("Import") {
                ToolButton("Import spreadsheet", Icons.Rounded.TableChart, onImportData)
                ToolButton("Import MegaPack", Icons.Rounded.Inventory2, onImportMegaPack)
                ToolButton("Import .renderer", Icons.Rounded.Build, onImportRenderer)
            }
        }
        item {
            SettingsCard("Renderers") {
                Text(RendererRuntime.active.name, fontWeight = FontWeight.SemiBold)
                Text("${RendererRuntime.active.engine} • ${RendererRuntime.active.precisionMode}", style = MaterialTheme.typography.bodySmall)
                ToolButton("Renderer library", Icons.Rounded.Build, onRendererLibrary)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FaqTab(modifier: Modifier = Modifier) {
    val questions = listOf(
        "How do I import a renderer?" to "Open Tools → Import .renderer, or tap a .renderer file in Android Files and choose Cubical Compare. Import opens as a preflight dialog before activation.",
        "Can I use my own intro?" to "Yes. Open Settings → Intro and choose Custom MP4. You can also use the renderer default or disable the intro completely.",
        "What does Comparison Exact mean?" to "The comparison animation still matches the frame-exact renderer, but you replaced or disabled only its intro.",
        "Does Cubical Compare autosave?" to "Yes. Project edits are written to the app's recovery autosave automatically.",
        "Can I undo changes?" to "Yes. Use Undo and Redo in the top bar. Card text, transforms, reordering and project setting changes are tracked.",
        "How do I import a MegaPack?" to "Open Tools and choose Import MegaPack.",
        "Can export continue with the screen off?" to "Yes. GPU export runs in a foreground service and reports progress through a notification.",
        "Which encoder should I choose?" to "Auto prefers hardware H.265 and falls back to hardware H.264.",
    )
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Frequently asked questions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        itemsIndexed(questions) { _, entry ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(entry.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); content()
    } }
}

@Composable
private fun SettingValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(12.dp)); Text(label, modifier = Modifier.weight(1f)); Switch(checked, onChecked)
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

@Composable
private fun CardTextField(label: String, value: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, label = { Text(label) }, singleLine = singleLine, minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth())
}

private fun updateCard(project: StudioProject, index: Int, card: StudioCard, onProjectChange: (StudioProject) -> Unit) {
    val cards = project.cards.toMutableList(); if (index !in cards.indices) return; cards[index] = card; onProjectChange(project.copy(cards = cards))
}

private fun safeName(value: String): String = value.trim().ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(80)
