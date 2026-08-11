package io.github.retrofrost.cts.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.retrofrost.cts.android.export.CodecCatalog
import io.github.retrofrost.cts.android.export.EncoderChoice
import io.github.retrofrost.cts.android.export.ExportWorker
import io.github.retrofrost.cts.android.importer.CardStripImporter
import io.github.retrofrost.cts.android.importer.CardStripGeometry
import io.github.retrofrost.cts.android.importer.CardStripLayout
import io.github.retrofrost.cts.android.importer.CardStripRecognition
import io.github.retrofrost.cts.android.importer.CardImageAnalysis
import io.github.retrofrost.cts.android.importer.DetectedCardPreview
import io.github.retrofrost.cts.android.importer.MegaPackImporter
import io.github.retrofrost.cts.android.importer.StripAxis
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.ModelMode
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.persistence.ProjectJson
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class WorkspaceSection(val label: String) {
    Data("Cards"),
    Audio("Sound & pre-roll"),
    Export("Export"),
}

private enum class StripAxisChoice(val label: String, val axis: StripAxis?) {
    Auto("Auto", null),
    Horizontal("Horizontal", StripAxis.Horizontal),
    Vertical("Vertical", StripAxis.Vertical),
}

private data class CardStripReviewState(
    val source: Uri,
    val imageWidth: Int,
    val imageHeight: Int,
    val model: VisualModel,
    val recognition: CardStripRecognition,
    val separatorOverridePx: Int? = null,
    val axisChoice: StripAxisChoice = StripAxisChoice.Auto,
    val reverseOrder: Boolean = false,
    val manualDividerFractions: List<Float>? = null,
    val imageAnalyses: List<CardImageAnalysis> = emptyList(),
    val cropAdjustments: Map<Int, ImageCropAdjustment> = emptyMap(),
)

private data class ImageCropAdjustment(
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
    val zoom: Float = 1f,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CtsAndroidAppV2(initialModel: VisualModel = VisualModel.Males) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val workManager = remember(context) { WorkManager.getInstance(context) }
    var requestedExportId by remember { mutableStateOf<UUID?>(null) }
    val exportWorkInfos by produceState(initialValue = emptyList<WorkInfo>(), workManager) {
        workManager.getWorkInfosByTagFlow(ExportWorker.TAG).collect { value = it }
    }
    val activeExport = requestedExportId
        ?.let { id -> exportWorkInfos.firstOrNull { it.id == id && !it.state.isFinished } }
        ?: exportWorkInfos.lastOrNull { !it.state.isFinished }
    var project by remember(initialModel) { mutableStateOf(CtsProject(model = initialModel).normalized()) }
    var selectedCardId by remember { mutableStateOf(project.cards.firstOrNull()?.id) }
    var positionSeconds by remember { mutableFloatStateOf(0f) }
    var isPlaying by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(WorkspaceSection.Data) }
    var showInsertDialog by remember { mutableStateOf(false) }
    var isImportingCardStrip by remember { mutableStateOf(false) }
    var cardStripReview by remember { mutableStateOf<CardStripReviewState?>(null) }
    var cardStripReviewError by remember { mutableStateOf<String?>(null) }
    var isImportingMegaPack by remember { mutableStateOf(false) }
    var megaPackWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
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

    val cardStripPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        if (project.cards.isEmpty()) {
            message("Add or paste cards before importing their image strip.")
            return@rememberLauncherForActivityResult
        }
        isImportingCardStrip = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    CardStripImporter.inspect(
                        context = context,
                        source = uri,
                        cardCount = project.cards.size,
                        model = project.model,
                    )
                }
            }.onSuccess { inspection ->
                cardStripReviewError = null
                cardStripReview = CardStripReviewState(
                    source = uri,
                    imageWidth = inspection.imageWidth,
                    imageHeight = inspection.imageHeight,
                    model = project.model,
                    recognition = inspection.recognition,
                )
            }.onFailure { error ->
                message(error.message ?: "Could not inspect that card strip")
            }
            isImportingCardStrip = false
        }
    }

    val megaPackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        DurationRuntime.useProjectSetting()
        isImportingMegaPack = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { MegaPackImporter.importPack(context, uri) }
            }.onSuccess { result ->
                applyProject(result.project)
                megaPackWarnings = result.warnings
                selectedCardId = result.project.cards.firstOrNull()?.id
                positionSeconds = 0f
                isPlaying = false
                section = WorkspaceSection.Data
                message(
                    "MegaPack '${result.packName}' loaded: ${result.project.cards.size} cards and " +
                        "${result.extractedFiles} media files",
                )
            }.onFailure { error ->
                message(error.message ?: "Could not import that MegaPack")
            }
            isImportingMegaPack = false
        }
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

    val introVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val durationSeconds = withContext(Dispatchers.IO) {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(context, uri)
                            val videoWidth = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                ?.toIntOrNull()
                                ?: 0
                            require(videoWidth > 0) { "The selected file does not contain playable video." }
                            val durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull()
                                ?: error("The selected file has no readable duration.")
                            require(durationMs > 0L) { "The selected MP4 contains no playable video." }
                            durationMs / 1_000f
                        } finally {
                            release()
                        }
                    }
                }
                project.copy(
                    introVideo = project.introVideo.copy(
                        uri = uri.toString(),
                        displayName = queryDisplayName(context, uri),
                        durationSeconds = durationSeconds,
                    ),
                )
            }.onSuccess(::applyProject).onSuccess {
                positionSeconds = 0f
                isPlaying = false
                message("Pre-roll ready")
            }.onFailure { error ->
                message(error.message ?: "Could not use that MP4 as the pre-roll")
            }
        }
    }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("The selected project could not be read.")
            DurationRuntime.useProjectSetting()
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
        requestedExportId = ExportWorker.enqueue(context, project, uri, name)
        section = WorkspaceSection.Export
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
        if (activeExport != null) {
            section = WorkspaceSection.Export
            message("An export is already running.")
            return
        }
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

    val activeCardStripReview = cardStripReview
    if (activeCardStripReview != null) {
        CardStripReviewScreen(
            state = activeCardStripReview,
            cards = project.cards,
            isImporting = isImportingCardStrip,
            error = cardStripReviewError,
            onStateChanged = {
                cardStripReview = it
                cardStripReviewError = null
            },
            onCancel = {
                cardStripReview = null
                cardStripReviewError = null
            },
            onConfirm = { layout ->
                isImportingCardStrip = true
                cardStripReviewError = null
                scope.launch {
                    runCatching {
                        val result = withContext(Dispatchers.IO) {
                            CardStripImporter.importStrip(
                                context = context,
                                source = activeCardStripReview.source,
                                layout = layout,
                                reverseOrder = activeCardStripReview.reverseOrder,
                            )
                        }
                        check(result.sources.size == project.cards.size) {
                            "The detected card count changed before import."
                        }
                        result
                    }.onSuccess { result ->
                        applyProject(
                            project.copy(
                                cards = project.cards.mapIndexed { index, card ->
                                    val sourceIndex = if (activeCardStripReview.reverseOrder) {
                                        project.cards.lastIndex - index
                                    } else {
                                        index
                                    }
                                    val crop = activeCardStripReview.cropAdjustments[sourceIndex]
                                        ?: activeCardStripReview.imageAnalyses.getOrNull(sourceIndex)?.let { analysis ->
                                            ImageCropAdjustment(
                                                focusX = analysis.suggestedFocusX,
                                                focusY = analysis.suggestedFocusY,
                                                zoom = analysis.suggestedZoom,
                                            )
                                        }
                                        ?: ImageCropAdjustment()
                                    card.copy(
                                        imageSubcard = card.imageSubcard.copy(
                                            source = result.sources[index],
                                            transform = NormalizedRect.Full,
                                            cropFocusX = crop.focusX,
                                            cropFocusY = crop.focusY,
                                            cropZoom = crop.zoom,
                                        ),
                                    )
                                },
                            ),
                        )
                        cardStripReview = null
                        val order = if (result.axis == StripAxis.Horizontal) "left to right" else "top to bottom"
                        message("Imported ${result.sources.size} card images $order")
                    }.onFailure { error ->
                        cardStripReviewError = error.message ?: "Could not divide that image into cards"
                    }
                    isImportingCardStrip = false
                }
            },
        )
        return
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
            CenterAlignedTopAppBar(
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
        bottomBar = {
            WorkspaceTabs(section = section, onSectionChanged = { section = it })
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

            AnimatedContent(
                targetState = section,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    (fadeIn(tween(220, 70, FastOutSlowInEasing)) +
                        scaleIn(tween(220, 70, FastOutSlowInEasing), initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(110, easing = FastOutSlowInEasing)) +
                            scaleOut(tween(110, easing = FastOutSlowInEasing), targetScale = 0.98f))
                },
                label = "workspace-section",
            ) { activeSection ->
                when (activeSection) {
                    WorkspaceSection.Data -> CardsWorkspace2(
                        project = project,
                        selectedCardId = selectedCardId,
                        onSelectCard = ::selectCard,
                        onProjectChanged = ::applyProject,
                        onUpdateSelectedCard = ::updateSelectedCard,
                        onChooseImage = { imagePicker.launch(arrayOf("image/*")) },
                        onImportCardStrip = { cardStripPicker.launch(arrayOf("image/*")) },
                        isImportingCardStrip = isImportingCardStrip,
                        onImportMegaPack = {
                            megaPackPicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                            )
                        },
                        isImportingMegaPack = isImportingMegaPack,
                        onInsertData = { showInsertDialog = true },
                    )
                    WorkspaceSection.Audio -> AudioWorkspace(
                        project = project,
                        onProjectChanged = ::applyProject,
                        onChooseSoundtrack = { soundtrackPicker.launch(arrayOf("audio/*")) },
                        onChooseIntroVideo = { introVideoPicker.launch(arrayOf("video/mp4", "video/*")) },
                    )
                    WorkspaceSection.Export -> ExportWorkspace(
                        project = project,
                        onProjectChanged = ::applyProject,
                        onExport = ::requestExport,
                        exportWork = activeExport,
                        onCancelExport = {
                            activeExport?.let { work ->
                                workManager.cancelWorkById(work.id)
                                message("Canceling export…")
                            }
                        },
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

    if (megaPackWarnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { megaPackWarnings = emptyList() },
            title = { Text("MegaPack image check") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    megaPackWarnings.take(8).forEach { warning -> Text("• $warning") }
                    if (megaPackWarnings.size > 8) {
                        Text("…and ${megaPackWarnings.size - 8} more warnings.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { megaPackWarnings = emptyList() }) { Text("Review project") }
            },
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardStripReviewScreen(
    state: CardStripReviewState,
    cards: List<CtsCard>,
    isImporting: Boolean,
    error: String?,
    onStateChanged: (CardStripReviewState) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (CardStripLayout) -> Unit,
) {
    val selectedAxis = state.axisChoice.axis ?: state.recognition.selectedAxis
    val candidate = state.recognition.candidate(selectedAxis)
    val primaryLength = if (selectedAxis == StripAxis.Horizontal) state.imageWidth else state.imageHeight
    val safeSeparatorLimit = CardStripGeometry.maximumSafeSeparator(primaryLength, candidate.dividerFractions)
    val detectedSeparatorPx = (candidate.separatorFraction * primaryLength).toInt()
        .coerceIn(
            0,
            minOf(maxOf(12, primaryLength / cards.size.coerceAtLeast(1) / 10), safeSeparatorLimit),
        )
    val separatorPx = state.separatorOverridePx ?: detectedSeparatorPx
    val dividerFractions = state.manualDividerFractions ?: candidate.dividerFractions
    val layoutResult = remember(
        state.imageWidth,
        state.imageHeight,
        selectedAxis,
        separatorPx,
        dividerFractions,
    ) {
        runCatching {
            CardStripGeometry.fromDividers(
                imageWidth = state.imageWidth,
                imageHeight = state.imageHeight,
                axis = selectedAxis,
                dividerFractions = dividerFractions,
                separatorPx = separatorPx,
            )
        }
    }
    val layout = layoutResult.getOrNull()
    val slot = when (state.model) {
        VisualModel.Males -> "471×872"
        VisualModel.Relationships -> "475×788"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review detected cards", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !isImporting) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel card-strip import")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "${state.imageWidth}×${state.imageHeight} source · ${cards.size} cards",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${state.model.label} artwork uses $slot px at the 1920×1080 reference size.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("Strip direction", fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = StripAxisChoice.entries.map { it to it.label },
                    selected = state.axisChoice,
                    onSelected = {
                        onStateChanged(
                            state.copy(
                                axisChoice = it,
                                separatorOverridePx = null,
                                manualDividerFractions = null,
                                imageAnalyses = emptyList(),
                                cropAdjustments = emptyMap(),
                            ),
                        )
                    },
                    enabled = !isImporting,
                )
                Text(
                    "${selectedAxis.name} confidence: ${(candidate.confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidate.confidence < 0.58f) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            item {
                Text("Separator width", fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = listOf<Pair<Int?, String>>(
                        null to "Auto ($detectedSeparatorPx px)",
                        0 to "0 px",
                        1 to "1 px",
                        2 to "2 px",
                        4 to "4 px",
                        6 to "6 px",
                    ),
                    selected = state.separatorOverridePx,
                    onSelected = {
                        onStateChanged(
                            state.copy(
                                separatorOverridePx = it,
                                imageAnalyses = emptyList(),
                                cropAdjustments = emptyMap(),
                            ),
                        )
                    },
                    enabled = !isImporting,
                )
            }
            item {
                Text("Card boundaries", fontWeight = FontWeight.Bold)
                Text(
                    "Drag the yellow dividers if recognition missed an edge. Uneven card widths are supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CardStripBoundaryEditor(
                    source = state.source,
                    sourceWidth = state.imageWidth,
                    sourceHeight = state.imageHeight,
                    axis = selectedAxis,
                    dividerFractions = dividerFractions,
                    separatorPx = separatorPx,
                    onDividersChanged = {
                        onStateChanged(
                            state.copy(
                                manualDividerFractions = it,
                                imageAnalyses = emptyList(),
                                cropAdjustments = emptyMap(),
                            ),
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            onStateChanged(
                                state.copy(
                                    manualDividerFractions = null,
                                    imageAnalyses = emptyList(),
                                    cropAdjustments = emptyMap(),
                                ),
                            )
                        },
                        enabled = state.manualDividerFractions != null && !isImporting,
                    ) { Text("Use detected") }
                    TextButton(
                        onClick = {
                            val equal = (1 until cards.size).map { it / cards.size.toFloat() }
                            onStateChanged(
                                state.copy(
                                    manualDividerFractions = equal,
                                    imageAnalyses = emptyList(),
                                    cropAdjustments = emptyMap(),
                                ),
                            )
                        },
                        enabled = !isImporting,
                    ) { Text("Equal spacing") }
                }
                if (candidate.dividerConfidences.any { it < 0.5f }) {
                    Text(
                        "Some boundaries have low confidence; check their yellow handles.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Text("Assignment order", fontWeight = FontWeight.Bold)
                ChoiceRow(
                    options = listOf(false to "Detected order", true to "Reverse order"),
                    selected = state.reverseOrder,
                    onSelected = { onStateChanged(state.copy(reverseOrder = it)) },
                    enabled = !isImporting,
                )
            }
            item {
                Text("Detected card preview", fontWeight = FontWeight.Black)
                Text(
                    "Blank and duplicate tiles are flagged. Adjust each card's focus and zoom before importing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (layout != null) {
                item {
                    CardStripPreviewRow(
                        source = state.source,
                        layout = layout,
                        cards = cards,
                        model = state.model,
                        reverseOrder = state.reverseOrder,
                        cropAdjustments = state.cropAdjustments,
                        onAnalysesChanged = { analyses ->
                            if (analyses != state.imageAnalyses) {
                                val additions = analyses.mapIndexed { index, analysis ->
                                    index to (state.cropAdjustments[index] ?: ImageCropAdjustment(
                                        focusX = analysis.suggestedFocusX,
                                        focusY = analysis.suggestedFocusY,
                                        zoom = analysis.suggestedZoom,
                                    ))
                                }.toMap()
                                onStateChanged(
                                    state.copy(
                                        imageAnalyses = analyses,
                                        cropAdjustments = additions,
                                    ),
                                )
                            }
                        },
                        onCropChanged = { sourceIndex, crop ->
                            onStateChanged(
                                state.copy(cropAdjustments = state.cropAdjustments + (sourceIndex to crop)),
                            )
                        },
                    )
                }
            }
            layoutResult.exceptionOrNull()?.let { problem ->
                item { Text(problem.message ?: "These split settings are invalid.", color = MaterialTheme.colorScheme.error) }
            }
            error?.let { problem ->
                item { Text(problem, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onCancel, enabled = !isImporting, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { layout?.let(onConfirm) },
                        enabled = layout != null && !isImporting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Import ${cards.size} cards")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardStripPreviewRow(
    source: Uri,
    layout: CardStripLayout,
    cards: List<CtsCard>,
    model: VisualModel,
    reverseOrder: Boolean,
    cropAdjustments: Map<Int, ImageCropAdjustment>,
    onAnalysesChanged: (List<CardImageAnalysis>) -> Unit,
    onCropChanged: (Int, ImageCropAdjustment) -> Unit,
) {
    val context = LocalContext.current
    val previewResult by produceState<Result<List<DetectedCardPreview>>?>(
        null,
        source,
        layout,
    ) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                CardStripImporter.decodePreviews(context, source, layout)
            }
        }
    }
    val previews = previewResult?.getOrNull()
    DisposableEffect(previews) {
        onDispose {
            previews?.forEach { preview ->
                if (!preview.bitmap.isRecycled) preview.bitmap.recycle()
            }
        }
    }
    LaunchedEffect(previews) {
        previews?.let { onAnalysesChanged(it.map { preview -> preview.analysis }) }
    }

    when {
        previewResult == null -> CircularProgressIndicator()
        previews != null -> {
            val ordered = if (reverseOrder) previews.reversed() else previews
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(ordered) { destinationIndex, preview ->
                    val sourceIndex = if (reverseOrder) previews.lastIndex - destinationIndex else destinationIndex
                    val suggested = preview.analysis
                    val crop = cropAdjustments[sourceIndex] ?: ImageCropAdjustment(
                        suggested.suggestedFocusX,
                        suggested.suggestedFocusY,
                        suggested.suggestedZoom,
                    )
                    Card(modifier = Modifier.width(224.dp)) {
                        Column {
                            CardCropPreview(
                                bitmap = preview.bitmap,
                                crop = crop,
                                aspect = CardContentLayout.artworkAspect(model),
                            )
                            Text(
                                "Tile ${sourceIndex + 1} → ${destinationIndex + 1}. " +
                                    cards.getOrNull(destinationIndex)?.title.orEmpty().ifBlank { "Untitled" },
                                modifier = Modifier.padding(8.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            when {
                                suggested.blank -> Text(
                                    "Possible blank tile · ${(suggested.confidence * 100).toInt()}% confidence",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                suggested.duplicateOf != null -> Text(
                                    "Looks like tile ${suggested.duplicateOf + 1}",
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                else -> Text(
                                    "Image confidence ${(suggested.confidence * 100).toInt()}%",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                            CropSlider("Horizontal focus", crop.focusX, 0f..1f) {
                                onCropChanged(sourceIndex, crop.copy(focusX = it))
                            }
                            CropSlider("Vertical focus", crop.focusY, 0f..1f) {
                                onCropChanged(sourceIndex, crop.copy(focusY = it))
                            }
                            CropSlider("Zoom", crop.zoom, 1f..3f) {
                                onCropChanged(sourceIndex, crop.copy(zoom = it))
                            }
                            TextButton(
                                onClick = {
                                    onCropChanged(
                                        sourceIndex,
                                        ImageCropAdjustment(
                                            suggested.suggestedFocusX,
                                            suggested.suggestedFocusY,
                                            suggested.suggestedZoom,
                                        ),
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 4.dp),
                            ) { Text("Use automatic focus") }
                        }
                    }
                }
            }
        }
        else -> Text(
            previewResult?.exceptionOrNull()?.message ?: "Could not preview the detected cards.",
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun CardStripBoundaryEditor(
    source: Uri,
    sourceWidth: Int,
    sourceHeight: Int,
    axis: StripAxis,
    dividerFractions: List<Float>,
    separatorPx: Int,
    onDividersChanged: (List<Float>) -> Unit,
) {
    val context = LocalContext.current
    val latestDividers by rememberUpdatedState(dividerFractions)
    val latestCallback by rememberUpdatedState(onDividersChanged)
    var editorDividers by remember(source, axis, dividerFractions) { mutableStateOf(dividerFractions) }
    val bitmapResult by produceState<Result<Bitmap>?>(null, source) {
        value = runCatching {
            withContext(Dispatchers.IO) { CardStripImporter.decodeSheetPreview(context, source) }
        }
    }
    val bitmap = bitmapResult?.getOrNull()
    DisposableEffect(bitmap) {
        onDispose { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }

    if (bitmap == null) {
        if (bitmapResult == null) CircularProgressIndicator()
        else Text(
            bitmapResult?.exceptionOrNull()?.message ?: "Could not show the source image.",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }

    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(source, axis, dividerFractions.size) {
                var activeDivider = -1
                var workingDividers = latestDividers

                fun displayedBounds(): FloatArray {
                    val scale = minOf(size.width / sourceWidth.toFloat(), size.height / sourceHeight.toFloat())
                    val width = sourceWidth * scale
                    val height = sourceHeight * scale
                    return floatArrayOf((size.width - width) / 2f, (size.height - height) / 2f, width, height)
                }

                fun fractionAt(position: Offset): Float {
                    val bounds = displayedBounds()
                    return if (axis == StripAxis.Horizontal) {
                        ((position.x - bounds[0]) / bounds[2]).coerceIn(0f, 1f)
                    } else {
                        ((position.y - bounds[1]) / bounds[3]).coerceIn(0f, 1f)
                    }
                }

                detectDragGestures(
                    onDragStart = { position ->
                        workingDividers = latestDividers
                        val fraction = fractionAt(position)
                        val nearest = workingDividers.indices.minByOrNull { index ->
                            kotlin.math.abs(workingDividers[index] - fraction)
                        } ?: -1
                        val bounds = displayedBounds()
                        val primaryPx = if (axis == StripAxis.Horizontal) bounds[2] else bounds[3]
                        activeDivider = nearest.takeIf {
                            it >= 0 && kotlin.math.abs(workingDividers[it] - fraction) * primaryPx <= 36.dp.toPx()
                        } ?: -1
                    },
                    onDragCancel = {
                        editorDividers = latestDividers
                        activeDivider = -1
                    },
                    onDragEnd = {
                        if (activeDivider >= 0) latestCallback(workingDividers)
                        activeDivider = -1
                    },
                    onDrag = { change, _ ->
                        val index = activeDivider
                        if (index < 0) return@detectDragGestures
                        change.consume()
                        val minimum = if (index == 0) 0.02f else workingDividers[index - 1] + 0.02f
                        val maximum = if (index == workingDividers.lastIndex) {
                            0.98f
                        } else {
                            workingDividers[index + 1] - 0.02f
                        }
                        val updated = workingDividers.toMutableList().apply {
                            this[index] = fractionAt(change.position).coerceIn(minimum, maximum)
                        }
                        workingDividers = updated
                        editorDividers = updated
                    },
                )
            },
    ) {
        drawRect(Color(0xFF161616))
        val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
        val displayWidth = sourceWidth * scale
        val displayHeight = sourceHeight * scale
        val left = (size.width - displayWidth) / 2f
        val top = (size.height - displayHeight) / 2f
        drawImage(
            image = image,
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(displayWidth.toInt().coerceAtLeast(1), displayHeight.toInt().coerceAtLeast(1)),
        )
        editorDividers.forEach { fraction ->
            val separatorDisplayPx = separatorPx * scale
            if (axis == StripAxis.Horizontal) {
                val x = left + displayWidth * fraction
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(x - separatorDisplayPx / 2f, top),
                    size = androidx.compose.ui.geometry.Size(separatorDisplayPx.coerceAtLeast(1f), displayHeight),
                )
                drawLine(
                    Color(0xFFFFC928),
                    Offset(x, top),
                    Offset(x, top + displayHeight),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(Color(0xFFFFC928), 7.dp.toPx(), Offset(x, top + displayHeight / 2f))
            } else {
                val y = top + displayHeight * fraction
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(left, y - separatorDisplayPx / 2f),
                    size = androidx.compose.ui.geometry.Size(displayWidth, separatorDisplayPx.coerceAtLeast(1f)),
                )
                drawLine(
                    Color(0xFFFFC928),
                    Offset(left, y),
                    Offset(left + displayWidth, y),
                    strokeWidth = 2.dp.toPx(),
                )
                drawCircle(Color(0xFFFFC928), 7.dp.toPx(), Offset(left + displayWidth / 2f, y))
            }
        }
    }
}

@Composable
private fun CardCropPreview(
    bitmap: Bitmap,
    crop: ImageCropAdjustment,
    aspect: Float,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceAtLeast(0.1f)),
    ) {
        val destinationAspect = size.width / size.height.coerceAtLeast(1f)
        val sourceAspect = bitmap.width / bitmap.height.toFloat().coerceAtLeast(1f)
        val baseCropWidth: Float
        val baseCropHeight: Float
        if (sourceAspect >= destinationAspect) {
            baseCropHeight = bitmap.height.toFloat()
            baseCropWidth = baseCropHeight * destinationAspect
        } else {
            baseCropWidth = bitmap.width.toFloat()
            baseCropHeight = baseCropWidth / destinationAspect.coerceAtLeast(0.0001f)
        }
        val cropWidth = (baseCropWidth / crop.zoom.coerceIn(1f, 3f)).coerceAtLeast(1f)
        val cropHeight = (baseCropHeight / crop.zoom.coerceIn(1f, 3f)).coerceAtLeast(1f)
        val left = (bitmap.width * crop.focusX.coerceIn(0f, 1f) - cropWidth / 2f)
            .coerceIn(0f, kotlin.math.max(0f, bitmap.width - cropWidth))
        val top = (bitmap.height * crop.focusY.coerceIn(0f, 1f) - cropHeight / 2f)
            .coerceIn(0f, kotlin.math.max(0f, bitmap.height - cropHeight))
        drawImage(
            image = image,
            srcOffset = IntOffset(left.toInt(), top.toInt()),
            srcSize = IntSize(cropWidth.toInt().coerceAtLeast(1), cropHeight.toInt().coerceAtLeast(1)),
            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        )
    }
}

@Composable
private fun CropSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChanged: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(
            "$label ${"%.2f".format(value)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(value = value.coerceIn(valueRange), onValueChange = onValueChanged, valueRange = valueRange)
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
    NavigationBar(tonalElevation = 3.dp) {
        WorkspaceSection.entries.forEach { item ->
            val icon = when (item) {
                WorkspaceSection.Data -> Icons.Filled.TableRows
                WorkspaceSection.Audio -> Icons.Filled.MusicNote
                WorkspaceSection.Export -> Icons.Filled.Movie
            }
            NavigationBarItem(
                selected = section == item,
                onClick = { onSectionChanged(item) },
                label = { Text(item.label) },
                icon = { Icon(icon, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun readableOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    cursorColor = Color.Black,
    focusedLabelColor = Color.Black,
    unfocusedLabelColor = Color.DarkGray,
    focusedBorderColor = Color.Black,
    unfocusedBorderColor = Color(0xFF737373),
)

@Composable
private fun ReferenceOption(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}

@Composable
private fun AudioWorkspace(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
    onChooseSoundtrack: () -> Unit,
    onChooseIntroVideo: () -> Unit,
) {
    val encoders = remember { CodecCatalog.audioEncoders() }
    var showAdvanced by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Sound & pre-roll", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Add an optional pre-roll, edit text content, and choose music. The reference model itself stays untouched.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Optional pre-roll", fontWeight = FontWeight.Black)
                    Text(
                        project.introVideo.displayName.ifBlank { "Choose an MP4 to play before the model starts." },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onChooseIntroVideo, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Movie, contentDescription = null)
                            Text(if (project.introVideo.uri == null) "Choose MP4" else "Replace MP4")
                        }
                        OutlinedButton(
                            onClick = {
                                onProjectChanged(
                                    project.copy(
                                        introVideo = project.introVideo.copy(
                                            uri = null,
                                            displayName = "",
                                            durationSeconds = 0f,
                                        ),
                                    ),
                                )
                            },
                            enabled = project.introVideo.uri != null,
                        ) {
                            Text("Remove")
                        }
                    }
                    if (project.introVideo.durationSeconds > 0f) {
                        Text(
                            "${TimelineEngine.formatTime(project.introVideo.durationSeconds)} · plays before the fixed reference intro",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text("Credits", fontWeight = FontWeight.Black)
                    OutlinedTextField(
                        value = project.credits.heading,
                        onValueChange = { value ->
                            onProjectChanged(project.copy(credits = project.credits.copy(heading = value)))
                        },
                        label = { Text("Credits heading") },
                        modifier = Modifier.fillMaxWidth(),
                        )
                    OutlinedTextField(
                        value = project.credits.lines,
                        onValueChange = { value ->
                            onProjectChanged(project.copy(credits = project.credits.copy(lines = value)))
                        },
                        label = { Text("Names or roles · one per line") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                        )
                    OutlinedTextField(
                        value = project.credits.footer,
                        onValueChange = { value ->
                            onProjectChanged(project.copy(credits = project.credits.copy(footer = value)))
                        },
                        label = { Text("Credits footer") },
                        modifier = Modifier.fillMaxWidth(),
                        )
                    OutlinedTextField(
                        value = project.credits.endingHeading,
                        onValueChange = { value ->
                            onProjectChanged(project.copy(credits = project.credits.copy(endingHeading = value)))
                        },
                        label = { Text("Ending credit heading") },
                        modifier = Modifier.fillMaxWidth(),
                        )
                    OutlinedTextField(
                        value = project.credits.endingDetails,
                        onValueChange = { value ->
                            onProjectChanged(project.copy(credits = project.credits.copy(endingDetails = value)))
                        },
                        label = { Text("Ending credit details") },
                        modifier = Modifier.fillMaxWidth(),
                        )
                }
            }
        }
        item {
            Text("Soundtrack", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
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
                        if (project.introVideo.uri != null) {
                            Text(
                                "The soundtrack replaces the intro video's original audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvanced) "Hide audio settings" else "Advanced audio settings")
            }
        }
        if (showAdvanced) {
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
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun ExportWorkspace(
    project: CtsProject,
    onProjectChanged: (CtsProject) -> Unit,
    onExport: () -> Unit,
    exportWork: WorkInfo?,
    onCancelExport: () -> Unit,
) {
    val encoders = remember { CodecCatalog.videoEncoders() }
    var showAdvanced by remember { mutableStateOf(false) }
    val duration = TimelineEngine.duration(project)
    val progressPercent = exportWork
        ?.progress
        ?.getInt(ExportWorker.KEY_PROGRESS, 0)
        ?.coerceIn(0, 100)
        ?: 0
    val animatedProgress by animateFloatAsState(
        targetValue = progressPercent / 100f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "export-progress",
    )
    val exportStage = exportWork
        ?.progress
        ?.getString(ExportWorker.KEY_STAGE)
        .orEmpty()
        .ifBlank {
            when (exportWork?.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting to export"
                WorkInfo.State.RUNNING -> "Preparing export"
                else -> "Exporting"
            }
        }
    val exportDetail = exportWork
        ?.progress
        ?.getString(ExportWorker.KEY_DETAIL)
        .orEmpty()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Export video", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Review the reference output, then export. You can leave CTS while encoding continues.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exportWork != null) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(exportStage, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                                if (exportDetail.isNotBlank()) {
                                    Text(
                                        exportDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text("$progressPercent%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        }
                        LinearWavyProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(14.dp),
                        )
                        FilledTonalButton(
                            onClick = onCancelExport,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text("Cancel export", fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Reference output", fontWeight = FontWeight.Black)
                    Text("${project.model.label} · fixed model timing")
                    Text(
                        "1920×1080 · 60 fps · model colors, geometry and animation locked",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvanced) "Hide export settings" else "Advanced export settings")
            }
        }
        if (showAdvanced) {
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
                    automaticLabel = "Auto · fastest efficient H.264 hardware",
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
                        if (project.export.videoEncoderName == null) {
                            "Auto selects the fastest compatible hardware encoder"
                        } else {
                            encoders.firstOrNull { it.name == project.export.videoEncoderName }?.label
                                ?: "Selected device encoder"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when {
                            project.soundtrack.uri != null ->
                                "AAC soundtrack · ${project.export.audioBitrate / 1000} kbps"
                            project.introVideo.uri != null ->
                                "Pre-roll audio is kept when available"
                            else -> "Silent MP4"
                        },
                    )
                }
            }
        }
        item {
            Button(
                onClick = onExport,
                enabled = project.cards.isNotEmpty() && exportWork == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (exportWork == null) "Encode MP4 in background" else "Export in progress",
                    fontWeight = FontWeight.Black,
                )
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
    enabled: Boolean = true,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(options) { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                enabled = enabled,
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
