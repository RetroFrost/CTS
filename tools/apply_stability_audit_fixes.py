from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:220]!r}")
    p.write_text(text.replace(old, new, 1))

# ---------------------------------------------------------------------------
# RendererBridge: one resolved output policy, frozen spec rendering, exact FPS
# for custom intros, and a runtime revision signal for Compose.
# ---------------------------------------------------------------------------
Path("android/app/src/main/java/io/github/retrofrost/cts/android/RendererBridge.kt").write_text(r'''package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Native 2.0.7 renderer bridge. No Python, Chaquopy, Pillow or openpyxl. */
data class RenderMetadata(
    val frameCount: Int,
    val duration: Double,
    val fps: Int,
)

object RendererBridge {
    private val lock = Any()
    private val nativeRenderer = NativeFrameRenderer()
    private val ribbonRenderer = RibbonFrameRenderer()
    private val relationshipsRenderer = RelationshipsFrameRenderer()
    private val relationshipsPrecisionRenderer = RelationshipsPrecisionFrameRenderer()
    private val runtimeRevisionMutable = MutableStateFlow(0L)
    val runtimeRevision = runtimeRevisionMutable.asStateFlow()

    fun setRuntimeActive(spec: RendererSpec) = synchronized(lock) {
        if (RendererRuntime.active != spec) {
            RendererRuntime.active = spec
            runtimeRevisionMutable.value = runtimeRevisionMutable.value + 1L
        } else {
            RendererRuntime.active = spec
        }
    }

    private fun engine(spec: RendererSpec = RendererRuntime.active): String = when {
        RelationshipsTimeline.isRelationships(spec) -> "relationships-exact"
        RibbonTimeline.isRibbon(spec) -> "ribbon-exact"
        else -> "native-standard"
    }

    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int = when (engine(spec)) {
        "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)
        "ribbon-exact" -> RibbonTimeline.totalFrameCount(project, spec)
        else -> NativeTimeline.totalFrameCount(project, spec)
    }.coerceAtLeast(1)

    /** Frame-exact means one canonical raster and cadence. Preview/export use this same rule. */
    fun resolveOutputProject(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ): StudioProject = if (spec.precisionMode == "frame-exact") {
        project.copy(
            width = spec.referenceWidth.coerceAtLeast(2),
            height = spec.referenceHeight.coerceAtLeast(2),
            fps = spec.referenceFps.coerceIn(1, 240),
        )
    } else {
        project.copy(
            width = project.width.coerceAtLeast(2),
            height = project.height.coerceAtLeast(2),
            fps = project.fps.coerceIn(1, 120),
        )
    }

    fun projectCompatibility(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ): RendererProjectCompatibility = RendererProjectGuard.check(project, spec)

    fun requireProjectCompatibility(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ) = RendererProjectGuard.requireCompatible(project, spec)

    fun rendererIntroFrames(spec: RendererSpec = RendererRuntime.active): Int =
        spec.openingStarts.firstOrNull()?.coerceAtLeast(0) ?: 0

    fun customIntroFrames(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ): Int {
        if (project.introMode != IntroMode.CUSTOM || project.introVideo.isBlank()) return 0
        val fps = resolveOutputProject(project, spec).fps
        return IntroVideoSource.frameCount(project.introVideo, fps)
    }

    fun metadata(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ): RenderMetadata = synchronized(lock) {
        val outputProject = resolveOutputProject(project, spec)
        val fps = outputProject.fps
        val base = baseFrameCount(outputProject, spec)
        val rendererIntro = rendererIntroFrames(spec).coerceAtMost(base - 1)
        val frameCount = when (outputProject.introMode) {
            IntroMode.RENDERER -> base
            IntroMode.DISABLED -> base - rendererIntro
            IntroMode.CUSTOM -> {
                require(outputProject.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                IntroVideoSource.frameCount(outputProject.introVideo, fps) + (base - rendererIntro)
            }
        }.coerceAtLeast(1)
        RenderMetadata(frameCount, frameCount.toDouble() / fps, fps)
    }

    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray =
        renderRgbaWithSpec(project, RendererRuntime.active, frame, width, height)

    fun renderRgbaWithSpec(
        project: StudioProject,
        spec: RendererSpec,
        frame: Int,
        width: Int,
        height: Int,
    ): ByteArray = synchronized(lock) {
        val previous = RendererRuntime.active
        try {
            RendererRuntime.active = spec
            renderTimelineRgba(resolveOutputProject(project, spec), spec, frame, width, height)
        } finally {
            RendererRuntime.active = previous
        }
    }

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap =
        renderWithSpecTimeline(project, RendererRuntime.active, frame, width, height)

    /** Render a real project against a frozen renderer spec, preserving its intro mode. */
    fun renderWithSpecTimeline(
        project: StudioProject,
        spec: RendererSpec,
        frame: Int,
        width: Int,
        height: Int,
    ): Bitmap = synchronized(lock) {
        val previous = RendererRuntime.active
        try {
            RendererRuntime.active = spec
            renderTimeline(resolveOutputProject(project, spec), spec, frame, width, height)
        } finally {
            RendererRuntime.active = previous
        }
    }

    /** Preflight helper: renderer-owned intro only, no project custom-intro substitution. */
    fun renderWithSpec(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap = synchronized(lock) {
        val previous = RendererRuntime.active
        try {
            RendererRuntime.active = spec
            val value = resolveOutputProject(project.copy(introMode = IntroMode.RENDERER, introVideo = ""), spec)
            renderEngine(value, spec, frame, width, height)
        } finally {
            RendererRuntime.active = previous
        }
    }

    private fun renderTimeline(
        project: StudioProject,
        spec: RendererSpec,
        frame: Int,
        width: Int,
        height: Int,
    ): Bitmap {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val safeFrame = frame.coerceAtLeast(0)
        return when (project.introMode) {
            IntroMode.RENDERER -> renderEngine(project, spec, safeFrame, safeWidth, safeHeight)
            IntroMode.DISABLED -> renderEngine(
                project,
                spec,
                safeFrame + rendererIntroFrames(spec),
                safeWidth,
                safeHeight,
            )
            IntroMode.CUSTOM -> {
                require(project.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                val fps = project.fps.coerceAtLeast(1)
                val customFrames = IntroVideoSource.frameCount(project.introVideo, fps)
                if (safeFrame < customFrames) {
                    IntroVideoSource.render(project.introVideo, safeFrame, fps, safeWidth, safeHeight)
                } else {
                    renderEngine(
                        project,
                        spec,
                        safeFrame - customFrames + rendererIntroFrames(spec),
                        safeWidth,
                        safeHeight,
                    )
                }
            }
        }
    }

    private fun renderTimelineRgba(
        project: StudioProject,
        spec: RendererSpec,
        frame: Int,
        width: Int,
        height: Int,
    ): ByteArray {
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        val safeFrame = frame.coerceAtLeast(0)
        return when (project.introMode) {
            IntroMode.RENDERER -> renderEngineRgba(project, spec, safeFrame, safeWidth, safeHeight)
            IntroMode.DISABLED -> renderEngineRgba(project, spec, safeFrame + rendererIntroFrames(spec), safeWidth, safeHeight)
            IntroMode.CUSTOM -> {
                require(project.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                val fps = project.fps.coerceAtLeast(1)
                val customFrames = IntroVideoSource.frameCount(project.introVideo, fps)
                if (safeFrame < customFrames) {
                    IntroVideoSource.renderRgba(project.introVideo, safeFrame, fps, safeWidth, safeHeight)
                } else {
                    renderEngineRgba(
                        project,
                        spec,
                        safeFrame - customFrames + rendererIntroFrames(spec),
                        safeWidth,
                        safeHeight,
                    )
                }
            }
        }
    }

    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap =
        when (engine(spec)) {
            "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
                relationshipsPrecisionRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            } else {
                relationshipsRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            }
            "ribbon-exact" -> ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            else -> nativeRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }

    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray =
        when (engine(spec)) {
            "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
                relationshipsPrecisionRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            } else {
                relationshipsRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            }
            "ribbon-exact" -> ribbonRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            else -> nativeRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }

    fun importData(project: StudioProject, path: String): StudioProject = synchronized(lock) {
        NativeImporters.importData(project, File(path))
    }

    fun importMegaPack(path: String, assets: File): StudioProject = synchronized(lock) {
        NativeImporters.importMegaPack(File(path), assets)
    }

    fun materialize(context: Context, uri: Uri, prefix: String): File {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty()
        val extension = displayName.substringAfterLast('.', "")
            .takeIf { it.length in 1..12 && it.all { char -> char.isLetterOrDigit() } }
            ?.let { ".$it" }
            ?: when (context.contentResolver.getType(uri)) {
                "video/mp4" -> ".mp4"
                "audio/mpeg" -> ".mp3"
                "audio/mp4", "audio/aac" -> ".m4a"
                "text/csv" -> ".csv"
                "text/tab-separated-values" -> ".tsv"
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx"
                "application/vnd.ms-excel.sheet.macroEnabled.12" -> ".xlsm"
                "application/zip" -> ".zip"
                else -> ".bin"
            }
        val destination = File(imports, "$prefix-${System.nanoTime()}$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }
}
''')

# ---------------------------------------------------------------------------
# ExportService: file-backed immutable snapshot (no Binder-sized project JSON),
# renderer frozen for entire export, per-export wake lock, race-safe ownership.
# ---------------------------------------------------------------------------
Path("android/app/src/main/java/io/github/retrofrost/cts/android/ExportService.kt").write_text(r'''package io.github.retrofrost.cts.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var activeToken: String? = null
    @Volatile private var cancelRequestedToken: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Video exports", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            val token = activeToken
            if (token != null) {
                cancelRequestedToken = token
                job?.cancel()
                val cancelling = ExportState.state.value.copy(stage = "Cancelling", detail = "Stopping safely")
                ExportState.update(cancelling)
                updateNotification(cancelling)
            }
            return START_NOT_STICKY
        }

        val snapshotPath = intent?.getStringExtra(EXTRA_SNAPSHOT)
        val destinationText = intent?.getStringExtra(EXTRA_URI)
        if (snapshotPath.isNullOrBlank() || destinationText.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val snapshotDir = File(snapshotPath)
        val token = snapshotDir.name
        activeToken = token
        cancelRequestedToken = null
        job?.cancel()

        val initial = ExportProgress(true, 0, "Preparing", "Starting the GPU renderer")
        ExportState.update(initial)
        startForeground(NOTIFICATION_ID, notification(initial))

        val localWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CubicalCompare:GpuExport:$token")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        job = scope.launch {
            fun ownsExport(): Boolean = activeToken == token
            fun cancelled(): Boolean = !ownsExport() || cancelRequestedToken == token
            fun publish(value: ExportProgress) {
                if (!ownsExport()) return
                ExportState.update(value)
                updateNotification(value)
            }

            try {
                val projectFile = File(snapshotDir, PROJECT_FILE)
                val rendererFile = File(snapshotDir, RENDERER_FILE)
                require(projectFile.isFile && rendererFile.isFile) { "The export snapshot is incomplete." }
                val project = StudioProject.fromJson(projectFile.readText())
                val spec = rendererFile.inputStream().use(RendererBundle::read)
                val exportProject = RendererBridge.resolveOutputProject(project, spec)
                HardwareVideoExporter(
                    context = applicationContext,
                    sourceProject = exportProject,
                    rendererSpec = spec,
                    shouldCancel = ::cancelled,
                    onProgress = { percent, stage, detail ->
                        publish(ExportProgress(true, percent.coerceIn(0, 100), stage, detail))
                    },
                ).export(Uri.parse(destinationText))
                publish(ExportProgress(false, 100, "Finished", "The MP4 is ready"))
            } catch (_: CancellationException) {
                publish(ExportProgress(false, 0, "Cancelled", "Export cancelled"))
            } catch (error: Throwable) {
                publish(
                    ExportProgress(
                        false,
                        0,
                        "Export failed",
                        error.message ?: error::class.java.simpleName,
                    ),
                )
            } finally {
                if (localWakeLock.isHeld) localWakeLock.release()
                snapshotDir.deleteRecursively()
                if (activeToken == token) {
                    activeToken = null
                    cancelRequestedToken = null
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(startId)
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeToken?.let { cancelRequestedToken = it }
        job?.cancel()
        val timedOut = ExportProgress(false, 0, "Export stopped", "Android ended the media-processing time window")
        ExportState.update(timedOut)
        updateNotification(timedOut)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf(startId)
    }

    private fun updateNotification(progress: ExportProgress) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(progress))
    }

    private fun notification(progress: ExportProgress): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Cubical Compare")
            .setContentText("${progress.stage} • ${progress.detail}")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(progress.running)
            .setProgress(100, progress.percent, progress.running && progress.percent == 0)
        if (progress.running) {
            val cancel = PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        }
        return builder.build()
    }

    override fun onDestroy() {
        activeToken?.let { cancelRequestedToken = it }
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "cubical_compare_export"
        private const val NOTIFICATION_ID = 207
        private const val ACTION_START = "io.github.retrofrost.cts.android.EXPORT"
        private const val ACTION_CANCEL = "io.github.retrofrost.cts.android.CANCEL_EXPORT"
        private const val EXTRA_SNAPSHOT = "snapshot"
        private const val EXTRA_URI = "uri"
        private const val PROJECT_FILE = "project.json"
        private const val RENDERER_FILE = "renderer.renderer"

        fun start(context: Context, project: StudioProject, destination: Uri) {
            val queue = File(context.filesDir, "export-queue").apply { mkdirs() }
            val snapshot = File(queue, UUID.randomUUID().toString()).apply { mkdirs() }
            try {
                File(snapshot, PROJECT_FILE).writeText(project.toJson())
                File(snapshot, RENDERER_FILE).outputStream().use { RendererBundle.write(RendererRuntime.active, it) }
                val intent = Intent(context, ExportService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SNAPSHOT, snapshot.absolutePath)
                    .putExtra(EXTRA_URI, destination.toString())
                context.startForegroundService(intent)
            } catch (error: Throwable) {
                snapshot.deleteRecursively()
                throw error
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
''')

# ExportProgress/ExportState are still needed; prepend their declarations to the new service.
service = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ExportService.kt")
text = service.read_text()
marker = "class ExportService : Service() {"
prefix = '''data class ExportProgress(\n    val running: Boolean = false,\n    val percent: Int = 0,\n    val stage: String = "Ready",\n    val detail: String = "",\n)\n\nobject ExportState {\n    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(ExportProgress())\n    val state = mutable.asStateFlow()\n    fun update(progress: ExportProgress) { mutable.value = progress }\n}\n\n'''
service.write_text(text.replace(marker, prefix + marker, 1))

# ---------------------------------------------------------------------------
# Hardware exporter: captured renderer, resolved output, safe codec capability
# handling, supported bitrate mode, overflow-safe bitrate calculation.
# ---------------------------------------------------------------------------
exporter = "android/app/src/main/java/io/github/retrofrost/cts/android/HardwareVideoExporter.kt"
replace_once(
    exporter,
    '''data class SelectedVideoCodec(\n    val name: String,\n    val mime: String,\n    val label: String,\n)'''.replace('\\n','\n'),
    '''data class SelectedVideoCodec(\n    val name: String,\n    val mime: String,\n    val label: String,\n    val bitrateMode: Int?,\n)'''.replace('\\n','\n'),
)
replace_once(
    exporter,
    '''                val supported = runCatching {\n                    capabilities.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())\n                }.getOrDefault(false)\n                if (!supported) continue\n                return SelectedVideoCodec(\n                    name = info.name,\n                    mime = mime,\n                    label = if (mime == HEVC) "H.265 (HEVC)" else "H.264 (AVC)",\n                )'''.replace('\\n','\n'),
    '''                val videoCapabilities = capabilities.videoCapabilities ?: continue\n                val supported = runCatching {\n                    videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())\n                }.getOrDefault(false)\n                if (!supported) continue\n                val encoderCapabilities = capabilities.encoderCapabilities\n                val bitrateMode = when {\n                    runCatching { encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) }.getOrDefault(false) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR\n                    runCatching { encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) }.getOrDefault(false) -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR\n                    else -> null\n                }\n                return SelectedVideoCodec(\n                    name = info.name,\n                    mime = mime,\n                    label = if (mime == HEVC) "H.265 (HEVC)" else "H.264 (AVC)",\n                    bitrateMode = bitrateMode,\n                )'''.replace('\\n','\n'),
)
replace_once(
    exporter,
    '''class HardwareVideoExporter(\n    private val context: Context,\n    private val project: StudioProject,\n    private val shouldCancel: () -> Boolean,\n    private val onProgress: (Int, String, String) -> Unit,\n) {'''.replace('\\n','\n'),
    '''class HardwareVideoExporter(\n    private val context: Context,\n    sourceProject: StudioProject,\n    private val rendererSpec: RendererSpec,\n    private val shouldCancel: () -> Boolean,\n    private val onProgress: (Int, String, String) -> Unit,\n) {\n    private val project = RendererBridge.resolveOutputProject(sourceProject, rendererSpec)'''.replace('\\n','\n'),
)
replace_once(exporter, "val metadata = RendererBridge.metadata(project)", "val metadata = RendererBridge.metadata(project, rendererSpec)")
replace_once(
    exporter,
    '''        val bitrate = if (selected.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {\n            (width * height * fps * 0.075).roundToInt().coerceIn(3_000_000, 32_000_000)\n        } else {\n            (width * height * fps * 0.11).roundToInt().coerceIn(4_000_000, 45_000_000)\n        }'''.replace('\\n','\n'),
    '''        val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()\n        val bitrate = if (selected.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {\n            (pixelsPerSecond * 0.075).toLong().coerceIn(3_000_000L, 32_000_000L).toInt()\n        } else {\n            (pixelsPerSecond * 0.11).toLong().coerceIn(4_000_000L, 45_000_000L).toInt()\n        }'''.replace('\\n','\n'),
)
replace_once(exporter, "if (RelationshipsPrecisionFrameRenderer.enabled(RendererRuntime.active) && Build.VERSION.SDK_INT >= 24)", "if (RelationshipsPrecisionFrameRenderer.enabled(rendererSpec) && Build.VERSION.SDK_INT >= 24)")
replace_once(
    exporter,
    '''            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)\n            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)'''.replace('\\n','\n'),
    '''            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)\n            selected.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }'''.replace('\\n','\n'),
)
replace_once(exporter, "val bitmap = RendererBridge.render(project, frame, width, height)", "val bitmap = RendererBridge.renderWithSpecTimeline(project, rendererSpec, frame, width, height)")
# roundToInt is still used elsewhere for progress etc.; leave import.

# ---------------------------------------------------------------------------
# Reject unsupported multichannel soundtrack rather than corrupting/downmixing
# PCM implicitly.
# ---------------------------------------------------------------------------
audio = "android/app/src/main/java/io/github/retrofrost/cts/android/HardwareAudioTranscoder.kt"
replace_once(
    audio,
    '''        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)\n        val channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)'''.replace('\\n','\n'),
    '''        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)\n        val sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)\n        require(sourceChannels in 1..2) {\n            "The selected soundtrack has $sourceChannels channels. Cubical Compare currently supports mono or stereo audio."\n        }\n        val channels = sourceChannels'''.replace('\\n','\n'),
)

# ---------------------------------------------------------------------------
# Crash-safe autosave: fsync + atomic replace; no delete-before-rename window.
# ---------------------------------------------------------------------------
Path("android/app/src/main/java/io/github/retrofrost/cts/android/ProjectAutosave.kt").write_text(r'''package io.github.retrofrost.cts.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ProjectAutosave {
    private const val FILE_NAME = "current.ccproject.json"

    fun load(context: Context): StudioProject? = runCatching {
        val file = File(File(context.filesDir, "autosave"), FILE_NAME)
        if (!file.isFile) null else StudioProject.fromJson(file.readText())
    }.getOrNull()

    fun save(context: Context, project: StudioProject) {
        val dir = File(context.filesDir, "autosave").apply { mkdirs() }
        val destination = File(dir, FILE_NAME)
        val temp = File(dir, "$FILE_NAME.tmp")
        val bytes = project.toJson().toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear(context: Context) {
        File(File(context.filesDir, "autosave"), FILE_NAME).delete()
    }
}
''')

# RendererStore uses the same safer atomic replacement strategy.
bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(bundle, "import java.io.InputStream\n", "import java.io.InputStream\nimport java.io.FileOutputStream\nimport java.nio.file.AtomicMoveNotSupportedException\nimport java.nio.file.Files\nimport java.nio.file.StandardCopyOption\n")
replace_once(
    bundle,
    '''    private fun atomicWrite(destination: File, bytes: ByteArray) {\n        destination.parentFile?.mkdirs()\n        val tmp = File(destination.parentFile, destination.name + ".tmp")\n        tmp.outputStream().use { it.write(bytes) }\n        if (!tmp.renameTo(destination)) tmp.copyTo(destination, overwrite = true)\n        tmp.delete()\n    }'''.replace('\\n','\n'),
    '''    private fun atomicWrite(destination: File, bytes: ByteArray) {\n        destination.parentFile?.mkdirs()\n        val tmp = File(destination.parentFile, destination.name + ".tmp")\n        FileOutputStream(tmp).use { output ->\n            output.write(bytes)\n            output.fd.sync()\n        }\n        try {\n            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)\n        } catch (_: AtomicMoveNotSupportedException) {\n            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)\n        }\n    }'''.replace('\\n','\n'),
)
for old in (
    "RendererRuntime.active = spec\n        return spec",
    "return RendererSpec.builtIn().also { RendererRuntime.active = it }",
):
    if old in Path(bundle).read_text():
        if old.startswith("RendererRuntime"):
            replace_once(bundle, old, "RendererBridge.setRuntimeActive(spec)\n        return spec")
        else:
            replace_once(bundle, old, "return RendererSpec.builtIn().also(RendererBridge::setRuntimeActive)")
# There are two identical direct assignments (activate + rollback); handle remaining.
text = Path(bundle).read_text()
text = text.replace("RendererRuntime.active = spec\n        return spec", "RendererBridge.setRuntimeActive(spec)\n        return spec")
Path(bundle).write_text(text)

# Application startup goes through revision-aware setter.
app = "android/app/src/main/java/io/github/retrofrost/cts/android/CubicalCompareApplication.kt"
replace_once(app, "RendererRuntime.active = RendererStore(this).active()", "RendererBridge.setRuntimeActive(RendererStore(this).active())")

# ---------------------------------------------------------------------------
# Main UI: observe renderer revisions; display/select codec for actual resolved
# output; persist output URI grant where the provider supports it.
# ---------------------------------------------------------------------------
main = "android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt"
replace_once(main, "import android.content.Intent\n", "import android.content.Intent\nimport android.content.pm.PackageManager\n")
replace_once(main, "import androidx.compose.material.icons.Icons\n", "import androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.automirrored.rounded.List\n")
replace_once(main, "CARDS(\"Cards\", Icons.Rounded.List)", "CARDS(\"Cards\", Icons.AutoMirrored.Rounded.List)")
replace_once(
    main,
    '''    val exportProgress by ExportState.state.collectAsState()''',
    '''    val exportProgress by ExportState.state.collectAsState()\n    val rendererRevision by RendererBridge.runtimeRevision.collectAsState()\n    val activeRenderer = RendererRuntime.active'''.replace('\\n','\n'),
)
replace_once(main, "RendererRuntime.active.id,\n    )", "rendererRevision,\n    )")
replace_once(main, "val accuracy = accuracyState(project)", "val accuracy = accuracyState(project, activeRenderer)")
replace_once(main, 'Text("${RendererRuntime.active.name} · $saveState"', 'Text("${activeRenderer.name} · $saveState"')
replace_once(
    main,
    '''    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->\n        val value = pendingExport\n        if (uri != null && value != null) ExportService.start(context, value, uri)\n    }'''.replace('\\n','\n'),
    '''    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->\n        val value = pendingExport\n        if (uri != null && value != null) {\n            runCatching {\n                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)\n            }\n            runCatching { ExportService.start(context, value, uri) }.onFailure(::report)\n        }\n    }'''.replace('\\n','\n'),
)
replace_once(
    main,
    '''                    metadata = metadata,\n                    exportProgress = exportProgress,'''.replace('\\n','\n'),
    '''                    metadata = metadata,\n                    rendererSpec = activeRenderer,\n                    exportProgress = exportProgress,'''.replace('\\n','\n'),
)
replace_once(
    main,
    '''private fun MorePage(\n    project: StudioProject,\n    metadata: RenderMetadata,\n    exportProgress: ExportProgress,'''.replace('\\n','\n'),
    '''private fun MorePage(\n    project: StudioProject,\n    metadata: RenderMetadata,\n    rendererSpec: RendererSpec,\n    exportProgress: ExportProgress,'''.replace('\\n','\n'),
)
replace_once(
    main,
    '''    val codec = remember(project.encoderPreference, project.width, project.height, project.fps) {\n        HardwareCodecSelector.describe(project.encoderPreference, project.width, project.height, project.fps)\n    }'''.replace('\\n','\n'),
    '''    val outputProject = remember(project, rendererSpec) { RendererBridge.resolveOutputProject(project, rendererSpec) }\n    val codec = remember(outputProject.encoderPreference, outputProject.width, outputProject.height, outputProject.fps) {\n        HardwareCodecSelector.describe(outputProject.encoderPreference, outputProject.width, outputProject.height, outputProject.fps)\n    }'''.replace('\\n','\n'),
)
replace_once(main, 'SettingRow("Output", "${project.width}×${project.height} · ${project.fps} FPS")', 'SettingRow("Output", "${outputProject.width}×${outputProject.height} · ${outputProject.fps} FPS")')
# Drop unused PackageManager import if lint flags it; it is intentionally not needed.
Path(main).write_text(Path(main).read_text().replace("import android.content.pm.PackageManager\n", ""))

# ---------------------------------------------------------------------------
# Direct preview selection geometry now follows exact-v2 absolute image band and
# card X/Y tracks instead of the old 115px legacy relationship approximation.
# ---------------------------------------------------------------------------
direct = "android/app/src/main/java/io/github/retrofrost/cts/android/DirectPreviewTransform.kt"
replace_once(
    direct,
    '''    val height = if (RelationshipsTimeline.isRelationships(spec)) {\n        val descriptionHeight = if (card.description.isBlank()) 0f else 115f\n        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight\n        (refHeight - descriptionHeight - titleHeight).coerceAtLeast(1f)\n    } else {\n        spec.imageHeight.coerceIn(1f, refHeight)\n    }\n    return DirectPreviewGeometry(left, 0f, width, height, refWidth, refHeight)'''.replace('\\n','\n'),
    '''    val height = when {\n        RelationshipsPrecisionFrameRenderer.enabled(spec) -> spec.imageHeight.coerceIn(1f, refHeight)\n        RelationshipsTimeline.isRelationships(spec) -> {\n            val descriptionHeight = if (card.description.isBlank()) 0f else 115f\n            val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight\n            (refHeight - descriptionHeight - titleHeight).coerceAtLeast(1f)\n        }\n        else -> spec.imageHeight.coerceIn(1f, refHeight)\n    }\n    val top = if (RelationshipsPrecisionFrameRenderer.enabled(spec)) spec.track("card.$index.y", rendererFrame) ?: 0f else 0f\n    return DirectPreviewGeometry(left, top, width, height, refWidth, refHeight)'''.replace('\\n','\n'),
)
replace_once(
    direct,
    '''        return index * spec.slotPitch\n    }\n\n    val scroll = if (RelationshipsTimeline.isRelationships(spec)) {'''.replace('\\n','\n'),
    '''        val base = index * spec.slotPitch\n        return if (RelationshipsPrecisionFrameRenderer.enabled(spec)) spec.track("card.$index.x", frame) ?: base else base\n    }\n\n    val scroll = if (RelationshipsTimeline.isRelationships(spec)) {'''.replace('\\n','\n'),
)
replace_once(
    direct,
    '''    val slotX = index * spec.slotPitch - scroll\n    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()'''.replace('\\n','\n'),
    '''    val baseX = index * spec.slotPitch - scroll\n    val slotX = if (RelationshipsPrecisionFrameRenderer.enabled(spec)) spec.track("card.$index.x", frame) ?: baseX else baseX\n    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()'''.replace('\\n','\n'),
)

# ---------------------------------------------------------------------------
# Renderer manager/import lifecycle hardening: recycle previews and keep the
# runtime revision signal current. Heavy background refactor will follow only if
# lint/tests expose more; this fixes the definite bitmap leak now.
# ---------------------------------------------------------------------------
manager = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererManagerActivity.kt"
replace_once(manager, "active = store.active().also { RendererRuntime.active = it }", "active = store.active().also(RendererBridge::setRuntimeActive)")
replace_once(
    manager,
    '''        }.onSuccess {\n            previewBitmap = it\n        }.onFailure {'''.replace('\\n','\n'),
    '''        }.onSuccess { next ->\n            previewBitmap?.takeIf { it !== next && !it.isRecycled }?.recycle()\n            previewBitmap = next\n        }.onFailure {'''.replace('\\n','\n'),
)
# recycle on destroy and when candidate is cancelled/installed
insert_marker = '''    override fun onNewIntent(intent: Intent) {'''
replace_once(
    manager,
    insert_marker,
    '''    override fun onDestroy() {\n        previewBitmap?.takeIf { !it.isRecycled }?.recycle()\n        previewBitmap = null\n        super.onDestroy()\n    }\n\n    override fun onNewIntent(intent: Intent) {'''.replace('\\n','\n'),
)
# replace obvious null-outs with recycle helper pattern
text = Path(manager).read_text()
text = text.replace("candidate = null\n                                                    previewBitmap = null", "candidate = null\n                                                    previewBitmap?.takeIf { !it.isRecycled }?.recycle()\n                                                    previewBitmap = null")
text = text.replace("candidate = null\n                                                previewBitmap = null", "candidate = null\n                                                previewBitmap?.takeIf { !it.isRecycled }?.recycle()\n                                                previewBitmap = null")
Path(manager).write_text(text)

imp = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererImportActivity.kt"
replace_once(
    imp,
    '''    override fun onNewIntent(intent: Intent) {''',
    '''    override fun onDestroy() {\n        preview?.takeIf { !it.isRecycled }?.recycle()\n        preview = null\n        super.onDestroy()\n    }\n\n    override fun onNewIntent(intent: Intent) {'''.replace('\\n','\n'),
)

# ---------------------------------------------------------------------------
# Android manifest: remove invalid file:// path-only deep-link filter. The
# renderer MIME filter above already handles file:// with the proper MIME type.
# ---------------------------------------------------------------------------
manifest = "android/app/src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '''            <!-- Direct file URI fallback; runtime preflight still verifies CCRNDR magic/checksum. -->\n            <intent-filter>\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <category android:name="android.intent.category.BROWSABLE" />\n                <data android:scheme="file" android:pathPattern=".*\\\\.renderer" />\n            </intent-filter>\n\n'''.replace('\\n','\n'),
    "",
)

# ---------------------------------------------------------------------------
# Regression tests for resolved exact output and runtime ownership setter.
# ---------------------------------------------------------------------------
test = Path("android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt")
text = test.read_text()
if "frameExactOutputResolutionIsCanonical" not in text:
    addition = r'''

    @Test
    fun frameExactOutputResolutionIsCanonical() {
        val spec = RendererSpec(
            id = "relationships.test.output",
            engine = "relationships-exact",
            precisionMode = "frame-exact",
            referenceWidth = 1920,
            referenceHeight = 1080,
            referenceFps = 60,
            tags = listOf("relationships.exact.v2=true"),
        )
        val project = StudioProject(width = 1280, height = 720, fps = 30)
        val resolved = RendererBridge.resolveOutputProject(project, spec)
        assertEquals(1920, resolved.width)
        assertEquals(1080, resolved.height)
        assertEquals(60, resolved.fps)
    }
'''
    text = text.rstrip()
    if not text.endswith("}"):
        raise SystemExit("test class closing brace not found")
    text = text[:-1] + addition + "\n}\n"
    test.write_text(text)

# Remove the temporary read-only audit workflow after the verified hardening
# workflow commits these changes.
