package io.github.retrofrost.cts.android

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CubicalCompareApp() }
    }
}

private val CubicalDarkColours = darkColorScheme(
    primary = Color(0xFFFF5964),
    onPrimary = Color(0xFF330408),
    primaryContainer = Color(0xFF5A1720),
    onPrimaryContainer = Color(0xFFFFDADB),
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF111114),
    surfaceVariant = Color(0xFF242329),
    onBackground = Color(0xFFF7EDEA),
    onSurface = Color(0xFFF7EDEA),
)

private val CubicalLightColours = lightColorScheme(
    primary = Color(0xFF8F1D2C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADB),
    onPrimaryContainer = Color(0xFF3B0710),
    background = Color(0xFFFFF8F7),
    surface = Color(0xFFFFF8F7),
    surfaceVariant = Color(0xFFF4DDDE),
    onBackground = Color(0xFF251819),
    onSurface = Color(0xFF251819),
)

private enum class StudioPage(val title: String, val icon: ImageVector) {
    CARDS("Cards", Icons.Rounded.List),
    PREVIEW("Preview", Icons.Rounded.PlayArrow),
    PROJECT("Project", Icons.Rounded.Tune),
    MORE("More", Icons.Rounded.MoreHoriz),
}

private data class AccuracyState(val label: String, val detail: String, val exact: Boolean)

private fun accuracyState(project: StudioProject, spec: RendererSpec = RendererRuntime.active): AccuracyState {
    if (spec.precisionMode != "frame-exact") {
        return AccuracyState("Adaptive", "${spec.name} uses adaptive rendering.", false)
    }
    val issues = mutableListOf<String>()
    if (project.width != spec.referenceWidth || project.height != spec.referenceHeight) issues += "resolution"
    if (project.fps != spec.referenceFps) issues += "frame rate"
    if (spec.canonicalCardCount > 0 && project.cards.size != spec.canonicalCardCount) issues += "card count"
    if (!project.autoLength) issues += "duration"
    if (issues.isNotEmpty()) return AccuracyState("Modified", "Changed: ${issues.joinToString()}.", false)
    return when (project.introMode) {
        IntroMode.RENDERER -> AccuracyState("Pixel exact", "Canonical renderer settings are intact.", true)
        IntroMode.CUSTOM -> AccuracyState("Comparison exact", "Only the renderer intro is replaced by your MP4.", true)
        IntroMode.DISABLED -> AccuracyState("Comparison exact", "Only the renderer intro is removed.", true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CubicalCompareApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var project by remember { mutableStateOf(ProjectAutosave.load(context) ?: StudioProject()) }
    var page by remember { mutableStateOf(StudioPage.CARDS) }
    var selectedCard by remember { mutableIntStateOf(0) }
    var transformRequestCardId by remember { mutableStateOf<String?>(null) }
    var metadata by remember { mutableStateOf(RenderMetadata(1, 0.0, 60)) }
    var metadataLoading by remember { mutableStateOf(true) }
    var saveState by remember { mutableStateOf("Saved") }
    val exportProgress by ExportState.state.collectAsState()

    fun report(error: Throwable) {
        scope.launch { snackbar.showSnackbar(error.message ?: "Something went wrong.") }
    }

    fun applyProject(next: StudioProject) {
        if (next == project) return
        project = next
        selectedCard = selectedCard.coerceIn(0, next.cards.lastIndex.coerceAtLeast(0))
    }

    LaunchedEffect(
        project.cards.size,
        project.autoLength,
        project.customLengthSeconds,
        project.introMode,
        project.introVideo,
        project.width,
        project.height,
        project.fps,
        RendererRuntime.active.id,
    ) {
        metadataLoading = true
        runCatching { withContext(Dispatchers.Default) { RendererBridge.metadata(project) } }
            .onSuccess { metadata = it }
            .onFailure(::report)
        metadataLoading = false
    }

    LaunchedEffect(project) {
        saveState = "Saving"
        delay(900)
        runCatching { withContext(Dispatchers.IO) { ProjectAutosave.save(context, project) } }
            .onSuccess { saveState = "Saved" }
            .onFailure { saveState = "Save failed"; report(it) }
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
            }.onSuccess { applyProject(it); selectedCard = 0; page = StudioPage.CARDS }.onFailure(::report)
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
            }.onSuccess { applyProject(it); selectedCard = 0; page = StudioPage.CARDS }.onFailure(::report)
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
            }.onSuccess { applyProject(it); selectedCard = 0; page = StudioPage.CARDS }.onFailure(::report)
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
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            applyProject(project.copy(soundtrack = uri.toString()))
        }
    }

    var pendingExport by remember { mutableStateOf<StudioProject?>(null) }
    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val value = pendingExport
        if (uri != null && value != null) ExportService.start(context, value, uri)
    }

    val darkTheme = isSystemInDarkTheme()
    val colours = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> CubicalDarkColours
        else -> CubicalLightColours
    }
    val accuracy = accuracyState(project)

    MaterialTheme(colorScheme = colours) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(project.name.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("${RendererRuntime.active.name} · $saveState", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            bottomBar = {
                NavigationBar {
                    StudioPage.entries.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { page = item },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.title) },
                        )
                    }
                }
            },
        ) { padding ->
            when (page) {
                StudioPage.CARDS -> CardsPage(
                    project = project,
                    selectedCard = selectedCard,
                    startTransformCardId = transformRequestCardId,
                    onTransformRequestConsumed = { transformRequestCardId = null },
                    onProjectChange = ::applyProject,
                    onSelectedCardChange = { selectedCard = it },
                    onChooseImage = { chooseImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.padding(padding),
                )
                StudioPage.PREVIEW -> DirectPreviewPage(
                    project = project,
                    metadata = metadata,
                    metadataLoading = metadataLoading,
                    accuracyLabel = accuracy.label,
                    accuracyDetail = accuracy.detail,
                    accuracyExact = accuracy.exact,
                    onProjectChange = ::applyProject,
                    onSelectedCardChange = { selectedCard = it },
                    modifier = Modifier.padding(padding),
                )
                StudioPage.PROJECT -> ProjectPage(
                    project = project,
                    metadata = metadata,
                    accuracy = accuracy,
                    onProjectChange = ::applyProject,
                    onChooseIntro = { chooseIntro.launch(arrayOf("video/mp4", "video/*")) },
                    onChooseSoundtrack = { chooseSoundtrack.launch(arrayOf("audio/*")) },
                    modifier = Modifier.padding(padding),
                )
                StudioPage.MORE -> MorePage(
                    project = project,
                    metadata = metadata,
                    exportProgress = exportProgress,
                    onNew = { applyProject(StudioProject()); selectedCard = 0; page = StudioPage.CARDS },
                    onOpen = { openProject.launch(arrayOf("application/json", "text/json", "*/*")) },
                    onSave = {
                        pendingProjectSave = project
                        saveProject.launch("${safeName(project.name)}.ccproject.json")
                    },
                    onImportData = { importData.launch(arrayOf("text/csv", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel")) },
                    onImportMegaPack = { importMegaPack.launch(arrayOf("application/zip", "*/*")) },
                    onImportRenderer = { context.startActivity(Intent(context, RendererImportActivity::class.java)) },
                    onRendererLibrary = { context.startActivity(Intent(context, RendererManagerActivity::class.java)) },
                    onExport = {
                        pendingExport = project
                        exportVideo.launch("Cubical-Compare-${safeName(project.name)}-2.0.7.mp4")
                    },
                    onCancelExport = { ExportService.cancel(context) },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun CardsPage(
    project: StudioProject,
    selectedCard: Int,
    startTransformCardId: String?,
    onTransformRequestConsumed: () -> Unit,
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Cards", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${project.cards.size} cards", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = {
                    val next = project.cards + StudioCard(title = "Card ${project.cards.size + 1}")
                    onProjectChange(project.copy(cards = next))
                    onSelectedCardChange(next.lastIndex)
                }) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
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
                SectionCard("Card ${selectedCard + 1}") {
                    CardTextField("Title", card.title) { updateCard(project, selectedCard, card.copy(title = it), onProjectChange) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = card.badgeHeader,
                            onValueChange = { updateCard(project, selectedCard, card.copy(badgeHeader = it), onProjectChange) },
                            label = { Text("Badge header") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = card.value,
                            onValueChange = { updateCard(project, selectedCard, card.copy(value = it), onProjectChange) },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CardTextField("Description", card.description, false) { updateCard(project, selectedCard, card.copy(description = it), onProjectChange) }
                }
            }
            item {
                SectionCard("Artwork") {
                    Button(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Image, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (card.image.isBlank()) "Choose artwork" else "Replace artwork")
                    }
                    if (card.image.isBlank()) {
                        Text("No artwork selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (startTransformCardId == card.id) onTransformRequestConsumed()
                    } else {
                        TransformArtworkEditor(
                            card = card,
                            startEditing = startTransformCardId == card.id,
                            onStartConsumed = onTransformRequestConsumed,
                            onCommit = { transformed ->
                                updateCard(project, selectedCard, transformed, onProjectChange)
                            },
                        )
                        Text(card.image.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val copy = card.copy(id = java.util.UUID.randomUUID().toString().replace("-", ""))
                            val next = project.cards.toMutableList().apply { add(selectedCard + 1, copy) }
                            onProjectChange(project.copy(cards = next))
                            onSelectedCardChange(selectedCard + 1)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Duplicate")
                    }
                    OutlinedButton(
                        enabled = project.cards.size > 1,
                        onClick = {
                            val next = project.cards.toMutableList().apply { removeAt(selectedCard) }
                            onProjectChange(project.copy(cards = next))
                            onSelectedCardChange(selectedCard.coerceAtMost(next.lastIndex))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Delete, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TransformArtworkEditor(
    card: StudioCard,
    startEditing: Boolean = false,
    onStartConsumed: () -> Unit = {},
    onCommit: (StudioCard) -> Unit,
) {
    val bitmap = remember(card.image) { decodePreviewBitmap(card.image) }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    var editing by remember(card.id, card.image) { mutableStateOf(false) }
    var fineTune by remember(card.id, card.image) { mutableStateOf(false) }
    var draft by remember(card.id, card.image) { mutableStateOf(card) }
    val spec = RendererRuntime.active
    val referenceWidth = spec.bodyWidth.coerceAtLeast(1f)
    val referenceHeight = spec.imageHeight.coerceAtLeast(1f)

    LaunchedEffect(card) {
        if (!editing) draft = card
    }
    LaunchedEffect(startEditing, card.id) {
        if (startEditing) {
            draft = card
            editing = true
            fineTune = false
            onStartConsumed()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(card.id, editing, referenceWidth, referenceHeight) {
                    if (!editing) return@pointerInput
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val boxWidth = size.width.toFloat().coerceAtLeast(1f)
                        val boxHeight = size.height.toFloat().coerceAtLeast(1f)
                        var angle = draft.imageRotation + rotation.toDouble()
                        angle %= 360.0
                        if (angle > 180.0) angle -= 360.0
                        if (angle < -180.0) angle += 360.0
                        draft = draft.copy(
                            imageX = (draft.imageX + pan.x / boxWidth * referenceWidth).coerceIn(-1200.0, 1200.0),
                            imageY = (draft.imageY + pan.y / boxHeight * referenceHeight).coerceIn(-1600.0, 1600.0),
                            imageScale = (draft.imageScale * zoom.toDouble()).coerceIn(0.10, 6.0),
                            imageRotation = angle,
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Artwork",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = draft.imageScale.toFloat()
                            scaleY = draft.imageScale.toFloat()
                            translationX = draft.imageX.toFloat() / referenceWidth * size.width
                            translationY = draft.imageY.toFloat() / referenceHeight * size.height
                            rotationZ = draft.imageRotation.toFloat()
                        },
                    contentScale = ContentScale.Crop,
                )
            } ?: Text("Artwork unavailable", color = Color.White)

            if (!editing) {
                FilledTonalButton(onClick = { draft = card; editing = true }) {
                    Text("Transform")
                }
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .border(2.dp, Color.White, RoundedCornerShape(10.dp)),
                )
                TransformHandle(Alignment.TopStart, -1f, -1f) { delta ->
                    draft = draft.copy(imageScale = (draft.imageScale * (1.0 + delta)).coerceIn(0.10, 6.0))
                }
                TransformHandle(Alignment.TopEnd, 1f, -1f) { delta ->
                    draft = draft.copy(imageScale = (draft.imageScale * (1.0 + delta)).coerceIn(0.10, 6.0))
                }
                TransformHandle(Alignment.BottomStart, -1f, 1f) { delta ->
                    draft = draft.copy(imageScale = (draft.imageScale * (1.0 + delta)).coerceIn(0.10, 6.0))
                }
                TransformHandle(Alignment.BottomEnd, 1f, 1f) { delta ->
                    draft = draft.copy(imageScale = (draft.imageScale * (1.0 + delta)).coerceIn(0.10, 6.0))
                }
                Row(
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { onCommit(draft); editing = false; fineTune = false }) { Text("Done") }
                    OutlinedButton(onClick = { draft = card; editing = false; fineTune = false }) { Text("Cancel") }
                }
                Text(
                    "Drag · pinch · rotate · drag corners to resize",
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.68f)).padding(horizontal = 12.dp, vertical = 7.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    if (editing) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = fineTune, onClick = { fineTune = !fineTune }, label = { Text("Fine tune") })
            FilterChip(
                selected = draft.imageLayer == "front",
                onClick = { draft = draft.copy(imageLayer = if (draft.imageLayer == "front") "behind" else "front") },
                label = { Text(if (draft.imageLayer == "front") "In front" else "Behind badge") },
            )
            OutlinedButton(onClick = {
                draft = draft.copy(
                    imageX = 0.0,
                    imageY = 0.0,
                    imageScale = 1.0,
                    imageRotation = 0.0,
                    imageCropLeft = 0.0,
                    imageCropTop = 0.0,
                    imageCropRight = 0.0,
                    imageCropBottom = 0.0,
                )
            }) { Text("Reset") }
        }
        if (fineTune) {
            TransformSlider("Scale", draft.imageScale.toFloat(), 0.10f..6f, "${"%.2f".format(draft.imageScale)}×") { draft = draft.copy(imageScale = it.toDouble()) }
            TransformSlider("Horizontal", draft.imageX.toFloat(), -600f..600f, "${draft.imageX.roundToInt()} px") { draft = draft.copy(imageX = it.toDouble()) }
            TransformSlider("Vertical", draft.imageY.toFloat(), -800f..800f, "${draft.imageY.roundToInt()} px") { draft = draft.copy(imageY = it.toDouble()) }
            TransformSlider("Rotation", draft.imageRotation.toFloat(), -180f..180f, "${draft.imageRotation.roundToInt()}°") { draft = draft.copy(imageRotation = it.toDouble()) }
            TransformSlider("Crop left", draft.imageCropLeft.toFloat(), 0f..0.45f, "${(draft.imageCropLeft * 100).roundToInt()}%") { draft = draft.copy(imageCropLeft = it.toDouble()) }
            TransformSlider("Crop right", draft.imageCropRight.toFloat(), 0f..0.45f, "${(draft.imageCropRight * 100).roundToInt()}%") { draft = draft.copy(imageCropRight = it.toDouble()) }
            TransformSlider("Crop top", draft.imageCropTop.toFloat(), 0f..0.45f, "${(draft.imageCropTop * 100).roundToInt()}%") { draft = draft.copy(imageCropTop = it.toDouble()) }
            TransformSlider("Crop bottom", draft.imageCropBottom.toFloat(), 0f..0.45f, "${(draft.imageCropBottom * 100).roundToInt()}%") { draft = draft.copy(imageCropBottom = it.toDouble()) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TransformHandle(
    alignment: Alignment,
    signX: Float,
    signY: Float,
    onScaleDelta: (Double) -> Unit,
) {
    Box(
        Modifier
            .align(alignment)
            .padding(3.dp)
            .size(30.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(signX, signY) {
                detectDragGestures { _, dragAmount ->
                    val signedPixels = dragAmount.x * signX + dragAmount.y * signY
                    onScaleDelta((signedPixels / 240f).toDouble().coerceIn(-0.35, 0.35))
                }
            },
    )
}

@Composable
private fun TransformSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Text("$label · $display", style = MaterialTheme.typography.bodySmall)
    androidx.compose.material3.Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}

@Composable
private fun PreviewPage(
    project: StudioProject,
    metadata: RenderMetadata,
    metadataLoading: Boolean,
    accuracy: AccuracyState,
    onEditArtwork: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(project, frame) {
        runCatching { withContext(Dispatchers.Default) { RendererBridge.render(project, frame, 640, 360) } }
            .onSuccess { next -> bitmap?.takeIf { it !== next && !it.isRecycled }?.recycle(); bitmap = next }
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    LaunchedEffect(metadata.frameCount) { frame = frame.coerceIn(0, (metadata.frameCount - 1).coerceAtLeast(0)) }
    LaunchedEffect(playing, speed, metadata.frameCount, metadata.fps) {
        while (playing) {
            delay((1000.0 / (metadata.fps.coerceAtLeast(1) * speed)).toLong().coerceAtLeast(4L))
            if (frame + 1 >= metadata.frameCount) { playing = false; frame = 0 } else frame += 1
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Preview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(accuracy.label, style = MaterialTheme.typography.bodySmall, color = if (accuracy.exact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                }
                FilledTonalButton(enabled = !metadataLoading, onClick = { playing = !playing }) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Pause" else "Play")
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(project.cards, frame, RendererRuntime.active.id) {
                            detectTapGestures { point ->
                                val fraction = if (size.width > 0) point.x / size.width.toFloat() else 0.5f
                                previewCardAt(project, frame, fraction)?.let { index ->
                                    playing = false
                                    onEditArtwork(index)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { Image(it.asImageBitmap(), "Video preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: Text("Rendering…", color = Color.White)
                }
            }
            Text(
                "Tap a card in the preview to transform its artwork",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Text("Frame ${frame + 1} of ${metadata.frameCount}", fontWeight = FontWeight.Medium)
            androidx.compose.material3.Slider(
                value = frame.toFloat(),
                onValueChange = { frame = it.roundToInt() },
                valueRange = 0f..(metadata.frameCount - 1).coerceAtLeast(1).toFloat(),
                enabled = !metadataLoading,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { frame = (frame - 1).coerceAtLeast(0) }, modifier = Modifier.weight(1f)) { Text("−1") }
                listOf(0.5f, 1f, 2f).forEach { option ->
                    FilterChip(selected = speed == option, onClick = { speed = option }, label = { Text("${option}×") })
                }
                OutlinedButton(onClick = { frame = (frame + 1).coerceAtMost((metadata.frameCount - 1).coerceAtLeast(0)) }, modifier = Modifier.weight(1f)) { Text("+1") }
            }
            Text(
                "${DurationFormat.formatPrecise(frame.toDouble() / metadata.fps.coerceAtLeast(1))} / ${DurationFormat.formatPrecise(metadata.duration)} · ${metadata.fps} FPS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard("Renderer") {
                SettingRow("Renderer", RendererRuntime.active.name)
                SettingRow("Accuracy", accuracy.label)
                Text(accuracy.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun previewCardAt(project: StudioProject, projectFrame: Int, xFraction: Float): Int? {
    if (project.cards.isEmpty()) return null
    val spec = RendererRuntime.active
    var frame = projectFrame.coerceAtLeast(0)
    when (project.introMode) {
        IntroMode.RENDERER -> Unit
        IntroMode.DISABLED -> frame += RendererBridge.rendererIntroFrames(spec)
        IntroMode.CUSTOM -> {
            if (project.introVideo.isBlank()) return null
            val customFrames = RendererBridge.customIntroFrames(project)
            if (frame < customFrames) return null
            frame = frame - customFrames + RendererBridge.rendererIntroFrames(spec)
        }
    }

    if (!RelationshipsTimeline.isRelationships(spec)) {
        return project.cards.indices.minByOrNull { index ->
            abs(RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec) - frame)
        }
    }

    val x = xFraction.coerceIn(0f, 1f) * spec.referenceWidth.coerceAtLeast(1)
    val positions = mutableListOf<Pair<Int, Float>>()
    if (frame < spec.continuousStartFrame) {
        val starts = spec.openingStarts
        for (index in 0 until min(4, project.cards.size)) {
            val start = starts.getOrElse(index) { starts.lastOrNull() ?: 384 + index * 140 }
            if (frame >= start) positions += index to index * spec.slotPitch
        }
    } else {
        val segment = (frame - spec.continuousStartFrame) / 4096
        val scroll = spec.track("relationships.scroll.$segment", frame)
            ?: ((frame - spec.continuousStartFrame) * 2f)
        project.cards.indices.forEach { index ->
            val slotX = index * spec.slotPitch - scroll
            if (slotX > -spec.slotPitch && slotX < spec.referenceWidth + spec.slotPitch) {
                positions += index to slotX
            }
        }
    }
    if (positions.isEmpty()) return null
    positions.firstOrNull { (_, slotX) ->
        val left = slotX + spec.bodyInset
        x in left..(left + spec.bodyWidth)
    }?.let { return it.first }
    return positions.minByOrNull { (_, slotX) ->
        abs((slotX + spec.bodyInset + spec.bodyWidth / 2f) - x)
    }?.first
}

@Composable
private fun ProjectPage(
    project: StudioProject,
    metadata: RenderMetadata,
    accuracy: AccuracyState,
    onProjectChange: (StudioProject) -> Unit,
    onChooseIntro: () -> Unit,
    onChooseSoundtrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var durationText by remember(project.customLengthSeconds) { mutableStateOf(DurationFormat.formatPrecise(project.customLengthSeconds)) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Project", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            SectionCard("Basics") {
                OutlinedTextField(
                    value = project.name,
                    onValueChange = { onProjectChange(project.copy(name = it)) },
                    label = { Text("Project name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingRow("Output", "${project.width}×${project.height} · ${project.fps} FPS")
                SettingRow("Length", "${DurationFormat.formatPrecise(metadata.duration)} · ${metadata.frameCount} frames")
                SettingRow("Accuracy", accuracy.label)
            }
        }
        item {
            SectionCard("Intro") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(IntroMode.entries) { _, mode ->
                        FilterChip(
                            selected = project.introMode == mode,
                            onClick = { onProjectChange(project.copy(introMode = mode)) },
                            label = { Text(mode.displayName) },
                        )
                    }
                }
                when (project.introMode) {
                    IntroMode.RENDERER -> Text("Uses the active renderer intro.", style = MaterialTheme.typography.bodySmall)
                    IntroMode.DISABLED -> Text("Starts directly at the comparison. No blank intro frames.", style = MaterialTheme.typography.bodySmall)
                    IntroMode.CUSTOM -> {
                        Text(if (project.introVideo.isBlank()) "No MP4 selected" else project.introVideo.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onChooseIntro, modifier = Modifier.fillMaxWidth()) { Text(if (project.introVideo.isBlank()) "Choose MP4" else "Replace MP4") }
                    }
                }
            }
        }
        item {
            SectionCard("Duration") {
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
                        label = { Text("MM:SS.sss") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = DurationFormat.parse(durationText) == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            SectionCard("Audio") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Soundtrack", fontWeight = FontWeight.Medium)
                        Text(if (project.soundtrack.isBlank()) "None" else "Selected", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onChooseSoundtrack) { Text(if (project.soundtrack.isBlank()) "Choose" else "Change") }
                }
                if (project.soundtrack.isNotBlank()) {
                    Text("Volume ${(project.soundtrackVolume * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.Slider(value = project.soundtrackVolume, onValueChange = { onProjectChange(project.copy(soundtrackVolume = it)) }, valueRange = 0f..1f)
                    ToggleRow("Loop soundtrack", project.soundtrackLoop) { onProjectChange(project.copy(soundtrackLoop = it)) }
                }
            }
        }
        item {
            SectionCard("Video") {
                ToggleRow("Show badges", project.showBadges) { onProjectChange(project.copy(showBadges = it)) }
                HorizontalDivider()
                ToggleRow("Credits", project.creditsEnabled) { onProjectChange(project.copy(creditsEnabled = it)) }
                HorizontalDivider()
                Text("Encoder", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EncoderPreference.entries.forEach { encoder ->
                        FilterChip(
                            selected = project.encoderPreference == encoder,
                            onClick = { onProjectChange(project.copy(encoderPreference = encoder)) },
                            label = { Text(encoder.displayName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MorePage(
    project: StudioProject,
    metadata: RenderMetadata,
    exportProgress: ExportProgress,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onImportData: () -> Unit,
    onImportMegaPack: () -> Unit,
    onImportRenderer: () -> Unit,
    onRendererLibrary: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val codec = remember(project.encoderPreference, project.width, project.height, project.fps) {
        HardwareCodecSelector.describe(project.encoderPreference, project.width, project.height, project.fps)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("More", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            SectionCard("Project files") {
                ToolButton("New project", Icons.Rounded.Add, onNew)
                ToolButton("Open project", Icons.Rounded.FileOpen, onOpen)
                ToolButton("Save project", Icons.Rounded.Save, onSave)
            }
        }
        item {
            SectionCard("Import") {
                ToolButton("Spreadsheet", Icons.Rounded.TableChart, onImportData)
                ToolButton("MegaPack", Icons.Rounded.Inventory2, onImportMegaPack)
                ToolButton("Renderer", Icons.Rounded.Build, onImportRenderer)
                OutlinedButton(onClick = onRendererLibrary, modifier = Modifier.fillMaxWidth()) { Text("Renderer library") }
            }
        }
        item {
            SectionCard("Export") {
                SettingRow("Output", "${project.width}×${project.height} · ${project.fps} FPS")
                SettingRow("Length", "${DurationFormat.formatPrecise(metadata.duration)} · ${metadata.frameCount} frames")
                SettingRow("Encoder", codec)
                if (exportProgress.running || exportProgress.stage != "Ready") {
                    Text(exportProgress.stage, fontWeight = FontWeight.Medium)
                    Text(exportProgress.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { exportProgress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Button(
                    onClick = onExport,
                    enabled = !exportProgress.running && !(project.introMode == IntroMode.CUSTOM && project.introVideo.isBlank()),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Icon(Icons.Rounded.VideoFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export video")
                }
                if (exportProgress.running) {
                    OutlinedButton(onClick = onCancelExport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel export")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ToolButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun CardTextField(label: String, value: String, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun updateCard(project: StudioProject, index: Int, card: StudioCard, onProjectChange: (StudioProject) -> Unit) {
    if (index !in project.cards.indices) return
    val cards = project.cards.toMutableList()
    cards[index] = card
    onProjectChange(project.copy(cards = cards))
}

private fun decodePreviewBitmap(path: String): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}.getOrNull()

private fun safeName(value: String): String = value.trim().ifBlank { "Untitled" }.replace(Regex("[^A-Za-z0-9._-]+"), "-").take(80)
