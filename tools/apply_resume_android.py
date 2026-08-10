from pathlib import Path
import re


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}: {old[:140]!r}")
    target.write_text(text.replace(old, new, 1))


importer = "android/app/src/main/java/io/github/retrofrost/cts/android/importer/VideoComparisonImporter.kt"
replace_once(
    importer,
    '''data class VideoReconstructionProgress(
    val phase: VideoReconstructionPhase,
    val completed: Int,
    val total: Int,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else completed.toFloat().div(total).coerceIn(0f, 1f)
}''',
    '''data class VideoReconstructionProgress(
    val phase: VideoReconstructionPhase,
    val completed: Int,
    val total: Int,
    val detail: String = "",
) {
    private val phaseFraction: Float
        get() = if (total <= 0) 0f else completed.toFloat().div(total).coerceIn(0f, 1f)

    // One continuous progress value: phases no longer reset the visible bar to 0%.
    val fraction: Float
        get() = when (phase) {
            VideoReconstructionPhase.Reading -> phaseFraction * 0.03f
            VideoReconstructionPhase.FindingCards -> 0.03f + phaseFraction * 0.55f
            VideoReconstructionPhase.ReadingText -> 0.58f + phaseFraction * 0.40f
            VideoReconstructionPhase.SavingArtwork -> 0.98f + phaseFraction * 0.02f
        }.coerceIn(0f, 1f)

    val percent: Int
        get() = (fraction * 100f).roundToInt().coerceIn(0, 100)
}''',
)
replace_once(
    importer,
    'onProgress(VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1))',
    'onProgress(VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1, "Opening video metadata"))',
)
replace_once(
    importer,
    '''            val durationSeconds = durationMs / 1_000f
            val sampleTimes = sampleTimes(durationSeconds)
            val observations = mutableListOf<PanelObservation>()''',
    '''            val durationSeconds = durationMs / 1_000f
            val sampleTimes = sampleTimes(durationSeconds)
            onProgress(
                VideoReconstructionProgress(
                    VideoReconstructionPhase.Reading,
                    1,
                    1,
                    "${sourceWidth}×${sourceHeight} · ${"%.1f".format(durationSeconds)} s · ${sampleTimes.size} sample frames",
                ),
            )
            val observations = mutableListOf<PanelObservation>()''',
)
replace_once(
    importer,
    '''                    VideoReconstructionProgress(
                        VideoReconstructionPhase.FindingCards,
                        index,
                        sampleTimes.size,
                    ),''',
    '''                    VideoReconstructionProgress(
                        VideoReconstructionPhase.FindingCards,
                        index,
                        sampleTimes.size,
                        "Scanning frame ${index + 1} of ${sampleTimes.size} at ${"%.1f".format(seconds)} s",
                    ),''',
)
replace_once(
    importer,
    '''                VideoReconstructionProgress(
                    VideoReconstructionPhase.FindingCards,
                    sampleTimes.size,
                    sampleTimes.size,
                ),''',
    '''                VideoReconstructionProgress(
                    VideoReconstructionPhase.FindingCards,
                    sampleTimes.size,
                    sampleTimes.size,
                    "Grouping ${observations.size} visible panel samples into cards",
                ),''',
)
replace_once(
    importer,
    '''                        VideoReconstructionProgress(
                            VideoReconstructionPhase.ReadingText,
                            index,
                            clusters.size,
                        ),''',
    '''                        VideoReconstructionProgress(
                            VideoReconstructionPhase.ReadingText,
                            index,
                            clusters.size,
                            "Card ${index + 1} of ${clusters.size} · OCR + artwork recovery",
                        ),''',
)
replace_once(
    importer,
    '''                VideoReconstructionProgress(
                    VideoReconstructionPhase.SavingArtwork,
                    reconstructed.size,
                    reconstructed.size,
                ),''',
    '''                VideoReconstructionProgress(
                    VideoReconstructionPhase.SavingArtwork,
                    reconstructed.size,
                    reconstructed.size,
                    "Finalising ${reconstructed.size} editable cards",
                ),''',
)

review = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ui/VideoReconstructionReview.kt")
review_text = review.read_text()
marker = "@Composable\ninternal fun VideoReconstructionProgressDialog(progress: VideoReconstructionProgress) {"
start = review_text.find(marker)
if start < 0:
    raise SystemExit("VideoReconstructionProgressDialog marker not found")
new_dialog = '''@Composable
internal fun VideoReconstructionProgressDialog(
    progress: VideoReconstructionProgress,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
        title = { Text("Reconstructing comparison") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedContent(
                    targetState = progress.phase,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 2 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 2 })
                    },
                    label = "reconstruction-phase",
                ) { phase ->
                    Text(phase.label, fontWeight = FontWeight.Black)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        progress.detail.ifBlank { "Preparing the next reconstruction step" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${progress.percent}%", fontWeight = FontWeight.Black)
                }
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (progress.total > 1) {
                    Text(
                        "${progress.completed.coerceIn(0, progress.total)} / ${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "This runs as a foreground background job, so the screen can be off and you can leave CTS while it continues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Cancel reconstruction") }
        },
        shape = RoundedCornerShape(28.dp),
    )
}
'''
review.write_text(review_text[:start] + new_dialog)

# Reuse the already-prepared worker/test payloads from the one-shot workflow,
# stripping only the YAML run-block indentation.
prepared = Path(".github/workflows/resume-android-reconstruction.yml").read_text()


def extract_payload(name: str, next_name: str) -> str:
    pattern = rf"{re.escape(name)} = r'''(.*?)'''\n\s*{re.escape(next_name)}"
    match = re.search(pattern, prepared, re.S)
    if not match:
        raise SystemExit(f"Could not extract {name} payload")
    body = match.group(1)
    lines = body.splitlines()
    cleaned = []
    for index, line in enumerate(lines):
        if index == 0:
            cleaned.append(line)
        elif line.startswith("          "):
            cleaned.append(line[10:])
        else:
            cleaned.append(line)
    return "\n".join(cleaned).strip() + "\n"


worker_payload = extract_payload("worker", "worker_path =")
worker_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/importer/VideoReconstructionWorker.kt")
worker_path.write_text(worker_payload)

test_payload = extract_payload("test", "test_path =")
test_path = Path("android/app/src/test/java/io/github/retrofrost/cts/android/importer/VideoReconstructionProgressTest.kt")
test_path.write_text(test_payload)

app = "android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppV2.kt"
replace_once(
    app,
    'import io.github.retrofrost.cts.android.importer.VideoReconstructionResult\n',
    'import io.github.retrofrost.cts.android.importer.VideoReconstructionResult\nimport io.github.retrofrost.cts.android.importer.VideoReconstructionWorker\n',
)
replace_once(
    app,
    '''    val activeExport = requestedExportId
        ?.let { id -> exportWorkInfos.firstOrNull { it.id == id && !it.state.isFinished } }
        ?: exportWorkInfos.lastOrNull { !it.state.isFinished }
    var project by remember { mutableStateOf(CtsProject().normalized()) }''',
    '''    val activeExport = requestedExportId
        ?.let { id -> exportWorkInfos.firstOrNull { it.id == id && !it.state.isFinished } }
        ?: exportWorkInfos.lastOrNull { !it.state.isFinished }
    var requestedReconstructionId by remember { mutableStateOf<UUID?>(null) }
    val reconstructionWorkInfos by produceState(initialValue = emptyList<WorkInfo>(), workManager) {
        workManager.getWorkInfosByTagFlow(VideoReconstructionWorker.TAG).collect { value = it }
    }
    val reconstructionWork = requestedReconstructionId
        ?.let { id -> reconstructionWorkInfos.firstOrNull { it.id == id } }
        ?: reconstructionWorkInfos.lastOrNull { !it.state.isFinished }
    var project by remember { mutableStateOf(CtsProject().normalized()) }''',
)
replace_once(
    app,
    '''    var isReconstructingVideo by remember { mutableStateOf(false) }
    var videoReconstructionProgress by remember {
        mutableStateOf(VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1))
    }
    var videoReconstruction by remember { mutableStateOf<VideoReconstructionResult?>(null) }''',
    '''    var isReconstructingVideo by remember { mutableStateOf(false) }
    var videoReconstructionProgress by remember {
        mutableStateOf(VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1, "Waiting to start"))
    }
    var videoReconstruction by remember { mutableStateOf<VideoReconstructionResult?>(null) }
    var handledReconstructionId by remember { mutableStateOf<UUID?>(null) }''',
)
replace_once(
    app,
    '''    fun updateSelectedCard(update: (CtsCard) -> CtsCard) {
        val cardId = selectedCardId ?: return
        applyProject(project.updateCard(cardId, update))
    }

    val imagePicker''',
    '''    fun updateSelectedCard(update: (CtsCard) -> CtsCard) {
        val cardId = selectedCardId ?: return
        applyProject(project.updateCard(cardId, update))
    }

    LaunchedEffect(Unit) {
        VideoReconstructionWorker.peekPendingResult(context)?.let { pending ->
            videoReconstruction = pending
            isReconstructingVideo = false
        }
    }

    LaunchedEffect(reconstructionWork) {
        val work = reconstructionWork ?: return@LaunchedEffect
        if (requestedReconstructionId == null && !work.state.isFinished) {
            requestedReconstructionId = work.id
        }
        if (!work.state.isFinished) {
            isReconstructingVideo = true
            videoReconstructionProgress = VideoReconstructionWorker.progressFrom(work.progress)
        }
        if (work.state.isFinished && handledReconstructionId != work.id) {
            handledReconstructionId = work.id
            isReconstructingVideo = false
            when (work.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val path = work.outputData.getString(VideoReconstructionWorker.KEY_RESULT_PATH)
                    runCatching {
                        require(!path.isNullOrBlank()) { "Reconstruction finished without a result file." }
                        VideoReconstructionWorker.readResult(path)
                    }.onSuccess { result ->
                        videoReconstruction = result
                        message("Recovered ${result.cards.size} cards · ready to review")
                    }.onFailure { error ->
                        message(error.message ?: "Could not open the reconstructed comparison")
                    }
                }
                WorkInfo.State.FAILED -> {
                    message(
                        work.outputData.getString(VideoReconstructionWorker.KEY_DETAIL)
                            ?: "Comparison reconstruction failed.",
                    )
                }
                WorkInfo.State.CANCELLED -> message("Comparison reconstruction canceled")
                else -> Unit
            }
        }
    }

    val imagePicker''',
)
old_picker = '''    val comparisonVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        isReconstructingVideo = true
        videoReconstructionProgress = VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    VideoComparisonImporter.reconstruct(
                        context = context,
                        source = uri,
                        sourceName = queryDisplayName(context, uri),
                        onProgress = { progress ->
                            scope.launch { videoReconstructionProgress = progress }
                        },
                    )
                }
            }.onSuccess { result ->
                videoReconstruction = result
            }.onFailure { error ->
                message(error.message ?: "Could not reconstruct that comparison video")
            }
            isReconstructingVideo = false
        }
    }'''
new_picker = '''    val comparisonVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val sourceName = queryDisplayName(context, uri)
        handledReconstructionId = null
        videoReconstruction = null
        isReconstructingVideo = true
        videoReconstructionProgress = VideoReconstructionProgress(
            VideoReconstructionPhase.Reading,
            0,
            1,
            "Queued in Android background work",
        )
        requestedReconstructionId = VideoReconstructionWorker.enqueue(context, uri, sourceName)
        message("Reconstruction started in the background · the screen can be off")
    }'''
replace_once(app, old_picker, new_picker)
replace_once(
    app,
    '            onCancel = { videoReconstruction = null },',
    '''            onCancel = {
                VideoReconstructionWorker.clearPendingResult(context)
                videoReconstruction = null
            },''',
)
replace_once(
    app,
    '''                section = WorkspaceSection.Data
                videoReconstruction = null
                message("Imported ${importedCards.size} reconstructed cards")''',
    '''                section = WorkspaceSection.Data
                VideoReconstructionWorker.clearPendingResult(context)
                videoReconstruction = null
                message("Imported ${importedCards.size} reconstructed cards")''',
)
replace_once(
    app,
    '''    if (isReconstructingVideo) {
        VideoReconstructionProgressDialog(videoReconstructionProgress)
    }''',
    '''    if (isReconstructingVideo) {
        VideoReconstructionProgressDialog(
            progress = videoReconstructionProgress,
            onCancel = {
                requestedReconstructionId?.let { workManager.cancelWorkById(it) }
            },
        )
    }''',
)
replace_once(app, 'import io.github.retrofrost.cts.android.importer.VideoComparisonImporter\n', '')

gradle = "android/app/build.gradle.kts"
replace_once(
    gradle,
    'versionCode = 6\n        versionName = "0.3.0-alpha6"',
    'versionCode = 7\n        versionName = "0.3.0-alpha7"',
)

# Clean the temporary patch drivers. The workflow restores android.yml itself.
Path(".github/workflows/resume-android-reconstruction.yml").unlink(missing_ok=True)
Path("tools/apply_resume_android.py").unlink(missing_ok=True)
