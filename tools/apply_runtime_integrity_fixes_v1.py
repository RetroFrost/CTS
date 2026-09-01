#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"
MARKER = "runtime-integrity-fixes-v1"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# RendererStore: renderer identity is FILE identity, never id-string identity.
# Re-importing a corrected .renderer with the same id must not leave an older
# active.renderer snapshot masquerading as the new library entry.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
text = replace_once(
    text,
    'class RendererStore(private val context: Context) {\n    private val dir = File(context.filesDir, "renderers")',
    'class RendererStore(private val rootDir: File) {\n    constructor(context: Context) : this(context.filesDir)\n\n    private val dir = File(rootDir, "renderers")',
    "testable renderer store root",
)
text = replace_once(
    text,
    '''    fun install(candidate: RendererCandidate): InstalledRenderer {
        require(candidate.report.compatible) { candidate.report.errors.joinToString("\\n") }
        libraryDir.mkdirs()
        val destination = File(libraryDir, "${candidate.spec.id}.renderer")
        atomicWrite(destination, candidate.bytes)
        return InstalledRenderer(candidate.spec, destination, active().id == candidate.spec.id)
    }
''',
    '''    fun install(candidate: RendererCandidate): InstalledRenderer {
        require(candidate.report.compatible) { candidate.report.errors.joinToString("\\n") }
        libraryDir.mkdirs()
        val destination = File(libraryDir, "${candidate.spec.id}.renderer")
        atomicWrite(destination, candidate.bytes)
        val activeBytes = activeFile.takeIf(File::isFile)?.readBytes()
        return InstalledRenderer(
            candidate.spec,
            destination,
            activeBytes?.contentEquals(candidate.bytes) == true,
        )
    }
''',
    "renderer install file identity",
)
text = replace_once(
    text,
    '''        dir.mkdirs()
        if (activeFile.isFile) atomicWrite(previousFile, activeFile.readBytes())
        atomicWrite(activeFile, bytes)
        RendererBridge.setRuntimeActive(spec)
        return spec
''',
    '''        dir.mkdirs()
        val current = activeFile.takeIf(File::isFile)?.readBytes()
        if (current == null || !current.contentEquals(bytes)) {
            if (current != null) atomicWrite(previousFile, current)
            atomicWrite(activeFile, bytes)
        }
        RendererBridge.setRuntimeActive(spec)
        return spec
''',
    "renderer activation exact bytes",
)
text = replace_once(
    text,
    '''    fun listInstalled(): List<InstalledRenderer> {
        val activeId = active().id
        if (!libraryDir.isDirectory) return emptyList()
        return libraryDir.listFiles { file -> file.isFile && file.extension.equals("renderer", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val spec = file.inputStream().use(RendererBundle::read)
                    InstalledRenderer(spec, file, spec.id == activeId)
                }.getOrNull()
            }
            .sortedBy { it.spec.name.lowercase() }
    }

    fun uninstall(id: String) {
        require(id != active().id) { "Activate another renderer before deleting the active renderer." }
        File(libraryDir, "$id.renderer").delete()
    }
''',
    '''    fun listInstalled(): List<InstalledRenderer> {
        val activeBytes = activeFile.takeIf(File::isFile)?.readBytes()
        if (!libraryDir.isDirectory) return emptyList()
        return libraryDir.listFiles { file -> file.isFile && file.extension.equals("renderer", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val bytes = file.readBytes()
                    val spec = RendererBundle.read(ByteArrayInputStream(bytes))
                    InstalledRenderer(spec, file, activeBytes?.contentEquals(bytes) == true)
                }.getOrNull()
            }
            .sortedBy { it.spec.name.lowercase() }
    }

    fun uninstall(id: String) {
        val target = File(libraryDir, "$id.renderer")
        require(target.isFile) { "Renderer '$id' is not installed." }
        val activeBytes = activeFile.takeIf(File::isFile)?.readBytes()
        val targetBytes = target.readBytes()
        require(activeBytes == null || !activeBytes.contentEquals(targetBytes)) {
            "Activate another renderer before deleting the active renderer."
        }
        require(target.delete()) { "Renderer '$id' could not be deleted." }
    }
''',
    "renderer installed active identity",
)
if MARKER not in text:
    text = text.replace(
        '/** Declarative renderer package. It never loads executable code. */',
        f'// {MARKER}\n/** Declarative renderer package. It never loads executable code. */',
        1,
    )
path.write_text(text)


# ---------------------------------------------------------------------------
# Project files: do not archive a made-up old renderer/model lock into every
# autosave/project. Renderer selection is global and owned by RendererStore.
# ---------------------------------------------------------------------------
path = ANDROID / "StudioProject.kt"
text = path.read_text()
text = replace_once(
    text,
    '''        val settings = JSONObject()
            .put("model_id", "what-males-learn-at-each-age")
            .put("model_revision", 1)
            .put("width", width)
''',
    '''        val settings = JSONObject()
            .put("width", width)
''',
    "remove stale model id",
)
text = replace_once(
    text,
    '''        return JSONObject()
            .put("version", 5)
            .put("name", name)
            .put("cards", cardArray)
            .put("settings", settings)
            .put(
                "model_lock",
                JSONObject()
                    .put("id", "what-males-learn-at-each-age")
                    .put("revision", 1)
                    .put("renderer_profile", "what-males-learn-at-each-age"),
            )
            .toString()
''',
    '''        return JSONObject()
            .put("version", 6)
            .put("name", name)
            .put("cards", cardArray)
            .put("settings", settings)
            .toString()
''',
    "remove stale renderer model lock",
)
path.write_text(text)


# ---------------------------------------------------------------------------
# MegaPack artwork: never rasterise into a hard-coded 471 x N frame. Preserve
# the natural asset canvas; the active renderer decides the destination frame.
# When a pack has both a background and a subject, compose them on the natural
# background canvas, not on an app-owned card frame.
# ---------------------------------------------------------------------------
path = ANDROID / "NativeImporters.kt"
text = path.read_text()
text = replace_once(
    text,
    '''                    if (background != null || subject != null) {
                        val descriptionHeight = if (description.isBlank()) 0 else 115
                        val titleHeight = if (title.isBlank()) 0 else 93
                        val imageHeight = (1080 - titleHeight - descriptionHeight).coerceAtLeast(1)
                        val artwork = composeArtwork(
                            background,
                            subject,
                            471,
                            imageHeight,
                            finite(item.opt("crop_focus_x"), 0.5).coerceIn(0.0, 1.0),
                            finite(item.opt("crop_focus_y"), 0.5).coerceIn(0.0, 1.0),
                            finite(item.opt("crop_zoom"), 1.0).coerceIn(1.0, 3.0),
                        )
''',
    '''                    if (background != null || subject != null) {
                        val artwork = composeArtwork(
                            background,
                            subject,
                            finite(item.opt("crop_focus_x"), 0.5).coerceIn(0.0, 1.0),
                            finite(item.opt("crop_focus_y"), 0.5).coerceIn(0.0, 1.0),
                            finite(item.opt("crop_zoom"), 1.0).coerceIn(1.0, 3.0),
                        )
''',
    "remove fixed megapack artwork frame",
)
old_compose_start = '''    private fun composeArtwork(backgroundBytes: ByteArray?, subjectBytes: ByteArray?, width: Int, height: Int, focusX: Double, focusY: Double, zoom: Double): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val subject = subjectBytes?.let(::decodeImage)
        try {
            val transparentSubject = subject?.let(::hasTransparentPixels) == true
            if (backgroundBytes != null) {
                val background = decodeImage(backgroundBytes)
                try {
                    drawCentreCrop(canvas, background, width, height, 0.5, 0.5, 1.0)
                } finally {
                    background.recycle()
                }
            } else if (transparentSubject) {
                drawBeachBackground(canvas, width, height)
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(19, 141, 219), Color.rgb(11, 116, 190), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            if (subject != null) {
                if (transparentSubject) {
                    drawContainedSubject(canvas, subject, width, height, focusX, focusY, zoom)
                } else {
                    drawCentreCrop(canvas, subject, width, height, focusX, focusY, zoom)
                }
            }
        } finally {
            subject?.recycle()
        }
        return output
    }
'''
new_compose = '''    private fun composeArtwork(
        backgroundBytes: ByteArray?,
        subjectBytes: ByteArray?,
        focusX: Double,
        focusY: Double,
        zoom: Double,
    ): Bitmap {
        val background = backgroundBytes?.let(::decodeImage)
        val subject = subjectBytes?.let(::decodeImage)
        require(background != null || subject != null) { "MegaPack card has no artwork." }
        val base = background ?: requireNotNull(subject)
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        try {
            if (background != null) {
                canvas.drawBitmap(
                    background,
                    Rect(0, 0, background.width, background.height),
                    Rect(0, 0, output.width, output.height),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            }
            if (subject != null) {
                if (background == null) {
                    // Preserve a single image exactly. Cropping belongs to the renderer.
                    canvas.drawBitmap(
                        subject,
                        Rect(0, 0, subject.width, subject.height),
                        Rect(0, 0, output.width, output.height),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                } else if (hasTransparentPixels(subject)) {
                    drawContainedSubject(canvas, subject, output.width, output.height, focusX, focusY, zoom)
                } else {
                    drawCentreCrop(canvas, subject, output.width, output.height, focusX, focusY, zoom)
                }
            }
        } finally {
            background?.recycle()
            subject?.recycle()
        }
        return output
    }
'''
text = replace_once(text, old_compose_start, new_compose, "renderer-owned artwork canvas")
path.write_text(text)


# ---------------------------------------------------------------------------
# RendererBridge/Direct GPU export: use the SAME render lock everywhere and do
# not swap the global active renderer per frame. The direct draw already gets
# an explicit immutable RendererSpec.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBridge.kt"
text = path.read_text()
text = replace_once(
    text,
    '''    val runtimeRevision = runtimeRevisionMutable.asStateFlow()

    fun setRuntimeActive(spec: RendererSpec) = synchronized(lock) {
''',
    '''    val runtimeRevision = runtimeRevisionMutable.asStateFlow()

    internal fun <T> withRenderLock(block: () -> T): T = synchronized(lock) { block() }

    fun setRuntimeActive(spec: RendererSpec) = synchronized(lock) {
''',
    "shared renderer lock",
)
path.write_text(text)

path = ANDROID / "DirectGpuVideoExporter.kt"
text = path.read_text()
text = replace_once(
    text,
    '''            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            selected.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
''',
    '''            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // We re-stamp direct-Canvas access units to the exact project cadence.
            // B-frame reordering would make output order differ from display order,
            // producing visible backwards/forwards motion on fast badge animation.
            if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            selected.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
''',
    "disable direct-export b frames",
)
text = replace_once(
    text,
    '''    ) = synchronized(RendererBridge) {
        val previous = RendererRuntime.active
        try {
            RendererRuntime.active = spec
            val safeFrame = frame.coerceAtLeast(0)
''',
    '''    ) = RendererBridge.withRenderLock {
            val safeFrame = frame.coerceAtLeast(0)
''',
    "direct renderer shared lock start",
)
text = replace_once(
    text,
    '''            canvas.restore()
        } finally {
            RendererRuntime.active = previous
        }
    }

    private fun drawFourArg(
''',
    '''            canvas.restore()
    }

    private fun drawFourArg(
''',
    "direct renderer remove global swap",
)
path.write_text(text)


# Android 8/9 cannot request zero B frames reliably on every codec. Frame-exact
# exports use the EGL path there, because it timestamps each submitted frame at
# the producer instead of guessing timestamps from codec output order.
path = ANDROID / "ExportService.kt"
text = path.read_text()
if 'import android.os.Build\n' not in text:
    text = text.replace('import android.net.Uri\n', 'import android.net.Uri\nimport android.os.Build\n', 1)
text = replace_once(
    text,
    '''                DirectGpuVideoExporter(
                    context = applicationContext,
                    sourceProject = exportProject,
                    rendererSpec = spec,
                    shouldCancel = ::cancelled,
                    onProgress = { percent, stage, detail ->
                        publish(ExportProgress(true, percent.coerceIn(0, 100), stage, detail))
                    },
                ).export(Uri.parse(destinationText))
''',
    '''                val progress: (Int, String, String) -> Unit = { percent, stage, detail ->
                    publish(ExportProgress(true, percent.coerceIn(0, 100), stage, detail))
                }
                if (spec.precisionMode == "frame-exact" && Build.VERSION.SDK_INT < 29) {
                    HardwareVideoExporter(
                        context = applicationContext,
                        sourceProject = exportProject,
                        rendererSpec = spec,
                        shouldCancel = ::cancelled,
                        onProgress = progress,
                    ).export(Uri.parse(destinationText))
                } else {
                    DirectGpuVideoExporter(
                        context = applicationContext,
                        sourceProject = exportProject,
                        rendererSpec = spec,
                        shouldCancel = ::cancelled,
                        onProgress = progress,
                    ).export(Uri.parse(destinationText))
                }
''',
    "frame exact old android exporter",
)
path.write_text(text)


# ---------------------------------------------------------------------------
# Direct artwork editor geometry must use the same renderer geometry and exact
# Ribbon scroll track as the actual raster, otherwise selection/drag overlays
# drift away from the image the renderer really draws.
# ---------------------------------------------------------------------------
path = ANDROID / "DirectPreviewTransform.kt"
text = path.read_text()
text = replace_once(
    text,
    '''        else -> spec.imageHeight.coerceIn(1f, refHeight)
''',
    '''        else -> {
            val descriptionHeight = if (card.description.isBlank()) 0f else (refHeight - spec.descriptionTop).coerceAtLeast(0f)
            val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
            (refHeight - descriptionHeight - titleHeight).coerceAtLeast(1f)
        }
''',
    "direct preview dynamic artwork height",
)
text = replace_once(
    text,
    '''    val scroll = if (RelationshipsTimeline.isRelationships(spec)) {
        val segment = (frame - spec.continuousStartFrame) / 4096
        spec.track("relationships.scroll.$segment", frame)
            ?: ((frame - spec.continuousStartFrame) * 2f)
    } else {
        ((frame - spec.continuousStartFrame) * 2f)
    }
''',
    '''    val scroll = when {
        RelationshipsTimeline.isRelationships(spec) -> {
            val segment = (frame - spec.continuousStartFrame) / 4096
            spec.track("relationships.scroll.$segment", frame)
                ?: ((frame - spec.continuousStartFrame) * 2f)
        }
        RibbonTimeline.isRibbon(spec) -> {
            val segment = (frame - spec.continuousStartFrame) / 4096
            spec.track("ribbon.scroll.$segment", frame)
                ?: ((frame - spec.continuousStartFrame).toFloat() /
                    RibbonTimeline.continuousStepFrames(project, spec).coerceAtLeast(1) * spec.slotPitch)
        }
        else -> ((frame - spec.continuousStartFrame) * 2f)
    }
''',
    "direct preview exact ribbon scroll",
)
path.write_text(text)

print("Applied runtime renderer identity, artwork geometry, export cadence, and preview integrity fixes")
