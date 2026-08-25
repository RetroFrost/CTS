package dev.infinitycomparison.cc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CreditScore
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.UploadFile
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
        CrashJournal.record("Main activity created")
        val crashLogCopied = CrashJournal.copyPendingReportToClipboard(this)
        enableEdgeToEdge()
        setContent { CubicalCompareApp(crashLogCopied) }
    }
}

private val CubicalDarkColours = darkColorScheme(
    primary = Color(0xFFFF5964),
    onPrimary = Color(0xFF330408),
    primaryContainer = Color(0xFF5A1720),
    onPrimaryContainer = Color(0xFFFFDADB),
    secondary = Color(0xFFD7C1C2),
    background = Color(0xFF0B0B0D),
    surface = Color(0xFF111114),
    surfaceVariant = Color(0xFF242329),
    onBackground = Color(0xFFF7EDEA),
    onSurface = Color(0xFFF7EDEA),
    outline = Color(0xFF514D54),
)

private val CubicalLightColours = lightColorScheme(
    primary = Color(0xFF8F1D2C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDADB),
    onPrimaryContainer = Color(0xFF3B0710),
    secondary = Color(0xFF765657),
    background = Color(0xFFFFF8F7),
    surface = Color(0xFFFFF8F7),
    surfaceVariant = Color(0xFFF4DDDE),
    onBackground = Color(0xFF251819),
    onSurface = Color(0xFF251819),
    outline = Color(0xFF857374),
)

private enum class StudioTab(val title: String, val icon: ImageVector) {
    CARDS("Cards", Icons.Rounded.List),
    TIMELINE("Timeline", Icons.Rounded.Timeline),
    SETTINGS("Settings", Icons.Rounded.Settings),
    TOOLS("Tools", Icons.Rounded.Build),
    FAQ("FAQ", Icons.Rounded.HelpOutline),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CubicalCompareApp(crashLogCopied: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var project by remember { mutableStateOf(StudioProject()) }
    var selectedTab by remember { mutableStateOf(StudioTab.CARDS) }
    var selectedCard by remember { mutableIntStateOf(0) }
    var metadata by remember { mutableStateOf(RenderMetadata(1, 0.0, 60)) }
    var metadataLoading by remember { mutableStateOf(true) }
    val exportProgress by ExportState.state.collectAsState()

    fun report(error: Throwable) {
        CrashJournal.record("Handled error: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        scope.launch { snackbar.showSnackbar(error.message ?: "Something went wrong.") }
    }

    LaunchedEffect(crashLogCopied) {
        if (crashLogCopied) {
            snackbar.showSnackbar("The previous run ended unexpectedly. Its diagnostic log was copied to the clipboard.")
        }
    }

    LaunchedEffect(project) {
        metadataLoading = true
        runCatching { withContext(Dispatchers.Default) { RendererBridge.metadata(project) } }
            .onSuccess { metadata = it }
            .onFailure(::report)
        metadataLoading = false
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
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
            }.onSuccess {
                project = it
                selectedCard = 0
            }.onFailure(::report)
        }
    }
    var pendingProjectSave by remember { mutableStateOf<StudioProject?>(null) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val value = pendingProjectSave
        if (uri != null && value != null) scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                    it.write(value.toJson())
                } ?: error("The project could not be saved.")
            }.onFailure(::report)
        }
    }
    val importData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val file = RendererBridge.materialize(context, uri, "data")
                    RendererBridge.importData(project, file.absolutePath)
                }
            }.onSuccess {
                project = it
                selectedCard = 0
            }.onFailure(::report)
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
            }.onSuccess {
                project = it
                selectedCard = 0
            }.onFailure(::report)
        }
    }
    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selectedCard in project.cards.indices) scope.launch {
            runCatching { withContext(Dispatchers.IO) { RendererBridge.materialize(context, uri, "artwork") } }
                .onSuccess { file ->
                    val cards = project.cards.toMutableList()
                    cards[selectedCard] = cards[selectedCard].copy(image = file.absolutePath)
                    project = project.copy(cards = cards)
                }.onFailure(::report)
        }
    }
    val chooseSoundtrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            project = project.copy(soundtrack = uri.toString())
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

    MaterialTheme(colorScheme = colourScheme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Cubical Compare", fontWeight = FontWeight.SemiBold)
                                Text("2.0.7", style = MaterialTheme.typography.labelSmall)
                            }
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
                    project = project,
                    selectedCard = selectedCard,
                    onProjectChange = { project = it },
                    onSelectedCardChange = { selectedCard = it },
                    onChooseImage = { chooseImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.padding(padding),
                )
                StudioTab.TIMELINE -> TimelineTab(
                    project = project,
                    metadata = metadata,
                    metadataLoading = metadataLoading,
                    onProjectChange = { project = it },
                    modifier = Modifier.padding(padding),
                )
                StudioTab.SETTINGS -> SettingsTab(
                    project = project,
                    exportProgress = exportProgress,
                    onProjectChange = { project = it },
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
                    onNew = { project = StudioProject(); selectedCard = 0; selectedTab = StudioTab.CARDS },
                    onOpen = { openProject.launch(arrayOf("application/json", "text/json", "*/*")) },
                    onSave = {
                        pendingProjectSave = project
                        saveProject.launch("${safeName(project.name)}.ccproject.json")
                    },
                    onImportData = {
                        importData.launch(
                            arrayOf(
                                "text/csv",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-excel",
                            ),
                        )
                    },
                    onImportMegaPack = { importMegaPack.launch(arrayOf("application/zip", "*/*")) },
                    modifier = Modifier.padding(padding),
                )
                StudioTab.FAQ -> FaqTab(modifier = Modifier.padding(padding))
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
                label = { Text("Comparison name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = project.outroPrompt,
                onValueChange = { onProjectChange(project.copy(outroPrompt = it)) },
                label = { Text("Outro question") },
                placeholder = { Text("What do you think? Comment below!") },
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Cards", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val next = project.cards + StudioCard(title = "Card ${project.cards.size + 1}")
                    onProjectChange(project.copy(cards = next))
                    onSelectedCardChange(next.lastIndex)
                }) { Icon(Icons.Rounded.Add, "Add card") }
                IconButton(
                    enabled = project.cards.size > 1 && card != null,
                    onClick = {
                        val next = project.cards.toMutableList().apply { removeAt(selectedCard) }
                        onProjectChange(project.copy(cards = next))
                        onSelectedCardChange(selectedCard.coerceAtMost(next.lastIndex))
                    },
                ) { Icon(Icons.Rounded.Delete, "Delete card") }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(project.cards, key = { _, item -> item.id }) { index, item ->
                    FilterChip(
                        selected = selectedCard == index,
                        onClick = { onSelectedCardChange(index) },
                        label = {
                            Text(
                                "${index + 1} · ${item.title.ifBlank { "Untitled" }}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
        if (card != null) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Card ${selectedCard + 1}", style = MaterialTheme.typography.titleMedium)
                        CardTextField("Title", card.title) { value ->
                            updateCard(project, selectedCard, card.copy(title = value), onProjectChange)
                        }
                        CardTextField("Badge header", card.badgeHeader) { value ->
                            updateCard(project, selectedCard, card.copy(badgeHeader = value), onProjectChange)
                        }
                        CardTextField("Badge value", card.value) { value ->
                            updateCard(project, selectedCard, card.copy(value = value), onProjectChange)
                        }
                        CardTextField("Description", card.description, singleLine = false) { value ->
                            updateCard(project, selectedCard, card.copy(description = value), onProjectChange)
                        }
                        FilledTonalButton(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Image, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (card.image.isBlank()) "Choose artwork" else "Change artwork")
                        }
                        if (card.image.isNotBlank()) {
                            Text(card.image.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium)
                        }
                        Text("Artwork scale ${"%.2f".format(card.imageScale)}×")
                        androidx.compose.material3.Slider(
                            value = card.imageScale.toFloat().coerceIn(0.25f, 3f),
                            onValueChange = {
                                updateCard(project, selectedCard, card.copy(imageScale = it.toDouble()), onProjectChange)
                            },
                            valueRange = 0.25f..3f,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = card.imageLayer == "behind",
                                onClick = {
                                    updateCard(project, selectedCard, card.copy(imageLayer = "behind"), onProjectChange)
                                },
                                label = { Text("Behind badge") },
                            )
                            FilterChip(
                                selected = card.imageLayer == "front",
                                onClick = {
                                    updateCard(project, selectedCard, card.copy(imageLayer = "front"), onProjectChange)
                                },
                                label = { Text("In front") },
                            )
                        }
                    }
                }
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        item {
            SettingsCard("Project") {
                Text(project.name.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyMedium)
                ToolButton("New project", Icons.Rounded.Add, onNew)
                ToolButton("Open project", Icons.Rounded.FileOpen, onOpen)
                ToolButton("Save project", Icons.Rounded.Save, onSave)
            }
        }
        item {
            SettingsCard("Import") {
                Text(
                    "Bring in a spreadsheet or a complete MegaPack without crowding the editor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ToolButton("Import spreadsheet", Icons.Rounded.TableChart, onImportData)
                ToolButton("Import MegaPack", Icons.Rounded.Inventory2, onImportMegaPack)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FaqTab(modifier: Modifier = Modifier) {
    val questions = listOf(
        "Where did Open, Save and Import go?" to "They are grouped in the Tools tab so the Cards editor and preview stay uncluttered.",
        "How do I import a MegaPack?" to "Open Tools, choose Import MegaPack, then select the .megapack.zip file.",
        "Does export create thumbnails?" to "No. Cubical Compare 2.0.7 saves only the finished MP4.",
        "How do I change the video length?" to "Open Timeline, choose Custom under Video length, then enter MM:SS. Only continuous scrolling speed changes.",
        "Can export continue with the screen off?" to "Yes. GPU export runs in a foreground service and reports progress through a notification.",
        "Which encoder should I choose?" to "Auto prefers hardware H.265 and falls back to hardware H.264. You can force either encoder in Settings.",
        "Why do the colours match my wallpaper?" to "On Android 12 and newer, Cubical Compare uses Material You dynamic colours. Older versions use the built-in light or dark palette.",
        "How do badge lines work?" to "Badge header is the small top line. Badge value contains the main number and its optional unit.",
        "What happens if the app crashes?" to "On the next launch, Cubical Compare automatically copies a private diagnostic report to the clipboard and tells you. The report contains the crash, device, memory and recent renderer stages—not your project data.",
    )
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Frequently asked questions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold) }
        itemsIndexed(questions) { _, entry ->
            val (question, answer) = entry
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
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
private fun TimelineTab(
    project: StudioProject,
    metadata: RenderMetadata,
    metadataLoading: Boolean,
    onProjectChange: (StudioProject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var frame by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var durationText by remember(project.customLengthSeconds) {
        mutableStateOf(DurationFormat.format(project.customLengthSeconds))
    }

    LaunchedEffect(project, frame) {
        runCatching {
            withContext(Dispatchers.Default) { RendererBridge.render(context, project, frame, 640, 360) }
        }.onSuccess { bitmap = it }
    }
    LaunchedEffect(playing, metadata.frameCount) {
        while (playing) {
            delay(33)
            frame = if (frame + 2 >= metadata.frameCount) {
                playing = false
                0
            } else frame + 2
        }
    }
    LaunchedEffect(metadata.frameCount) {
        frame = frame.coerceIn(0, (metadata.frameCount - 1).coerceAtLeast(0))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("Preview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Video preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } ?: Text("Rendering preview…")
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { playing = !playing }, enabled = !metadataLoading) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Pause" else "Play")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${DurationFormat.format(frame.toDouble() / metadata.fps)} / ${DurationFormat.format(metadata.duration)}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        item {
            androidx.compose.material3.Slider(
                value = frame.toFloat(),
                onValueChange = { frame = it.roundToInt() },
                valueRange = 0f..(metadata.frameCount - 1).coerceAtLeast(1).toFloat(),
                enabled = !metadataLoading,
            )
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Video length", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = project.autoLength,
                            onClick = { onProjectChange(project.copy(autoLength = true)) },
                            label = { Text("Automatic") },
                        )
                        FilterChip(
                            selected = !project.autoLength,
                            onClick = { onProjectChange(project.copy(autoLength = false)) },
                            label = { Text("Custom") },
                        )
                    }
                    if (!project.autoLength) {
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { value ->
                                durationText = value
                                DurationFormat.parse(value)?.let {
                                    onProjectChange(project.copy(customLengthSeconds = it.coerceAtLeast(15.0)))
                                }
                            },
                            label = { Text("Length (MM:SS)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = DurationFormat.parse(durationText) == null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "Only the continuous scrolling speed changes. Card animation, vertical badge fall, badge shine and outro timing remain unchanged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsTab(
    project: StudioProject,
    exportProgress: ExportProgress,
    onProjectChange: (StudioProject) -> Unit,
    onChooseSoundtrack: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val codecDescription = remember(project.encoderPreference, project.width, project.height, project.fps) {
        HardwareCodecSelector.describe(project.encoderPreference, project.width, project.height, project.fps)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            SettingsCard("Video") {
                SettingValueRow("Resolution", "1080p")
                HorizontalDivider()
                SettingValueRow("Frame rate", "60 FPS")
            }
        }
        item {
            SettingsCard("Rendering") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("GPU rendering", fontWeight = FontWeight.SemiBold)
                        Text("OpenGL ES • Direct encoder surface", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Enabled", color = Color(0xFF80DF8B), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            SettingsCard("Video encoder") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EncoderPreference.entries.forEach { encoder ->
                        FilterChip(
                            selected = project.encoderPreference == encoder,
                            onClick = { onProjectChange(project.copy(encoderPreference = encoder)) },
                            label = { Text(encoder.displayName) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(codecDescription, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (project.encoderPreference == EncoderPreference.AUTO) {
                        "Auto prefers hardware H.265 and falls back to hardware H.264."
                    } else {
                        "The selected hardware encoder will be used for export."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("Existing project settings") {
                ToggleRow(Icons.Rounded.Badge, "Show badges", project.showBadges) {
                    onProjectChange(project.copy(showBadges = it))
                }
                if (project.showBadges) {
                    HorizontalDivider()
                    ToggleRow(
                        Icons.Rounded.Timeline,
                        "Badges already placed while scrolling",
                        project.settledScrollingBadges,
                    ) {
                        onProjectChange(project.copy(settledScrollingBadges = it))
                    }
                }
                HorizontalDivider()
                ToggleRow(Icons.Rounded.CreditScore, "Credits", project.creditsEnabled) {
                    onProjectChange(project.copy(creditsEnabled = it))
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Icon(Icons.Rounded.MusicNote, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Soundtrack", fontWeight = FontWeight.Medium)
                        Text(
                            if (project.soundtrack.isBlank()) "None selected" else "Selected audio file",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onChooseSoundtrack) {
                        Text(if (project.soundtrack.isBlank()) "Choose" else "Change")
                    }
                }
                if (project.soundtrack.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Volume ${(project.soundtrackVolume * 100).roundToInt()}%")
                    androidx.compose.material3.Slider(
                        value = project.soundtrackVolume,
                        onValueChange = { onProjectChange(project.copy(soundtrackVolume = it)) },
                        valueRange = 0f..1f,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Loop soundtrack", modifier = Modifier.weight(1f))
                        Switch(
                            checked = project.soundtrackLoop,
                            onCheckedChange = { onProjectChange(project.copy(soundtrackLoop = it)) },
                        )
                    }
                }
            }
        }
        if (exportProgress.running || exportProgress.stage != "Ready") {
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(exportProgress.stage, fontWeight = FontWeight.SemiBold)
                        Text(exportProgress.detail, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { exportProgress.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
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
        item {
            Button(
                onClick = onExport,
                enabled = !exportProgress.running,
                modifier = Modifier.fillMaxWidth().height(58.dp),
            ) {
                Icon(Icons.Rounded.VideoFile, null)
                Spacer(Modifier.width(10.dp))
                Text("Export video", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Text(
                "GPU surface • ${if (project.encoderPreference == EncoderPreference.H264) "Hardware AVC" else "Hardware HEVC/AVC"}",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SettingValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun CardTextField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun updateCard(
    project: StudioProject,
    index: Int,
    card: StudioCard,
    onProjectChange: (StudioProject) -> Unit,
) {
    val cards = project.cards.toMutableList()
    if (index !in cards.indices) return
    cards[index] = card
    onProjectChange(project.copy(cards = cards))
}

private fun safeName(value: String): String = value.trim()
    .ifBlank { "Untitled" }
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .take(80)
