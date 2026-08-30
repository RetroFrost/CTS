package io.github.retrofrost.cts.android

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
    ): StudioProject = RenderOutputPolicy.resolve(project, spec)

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
