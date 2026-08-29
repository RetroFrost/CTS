package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
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

    fun rendererIntroFrames(spec: RendererSpec = RendererRuntime.active): Int =
        spec.openingStarts.firstOrNull()?.coerceAtLeast(0) ?: 0

    fun customIntroFrames(project: StudioProject): Int {
        if (project.introMode != IntroMode.CUSTOM || project.introVideo.isBlank()) return 0
        return IntroVideoSource.frameCount(project.introVideo, project.fps.coerceAtLeast(1))
    }

    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val fps = project.fps.coerceIn(1, 120)
        val spec = RendererRuntime.active
        val base = baseFrameCount(project, spec)
        val rendererIntro = rendererIntroFrames(spec).coerceAtMost(base - 1)
        val frameCount = when (project.introMode) {
            IntroMode.RENDERER -> base
            IntroMode.DISABLED -> base - rendererIntro
            IntroMode.CUSTOM -> {
                require(project.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                IntroVideoSource.frameCount(project.introVideo, fps) + (base - rendererIntro)
            }
        }.coerceAtLeast(1)
        RenderMetadata(frameCount, frameCount.toDouble() / fps, fps)
    }

    /**
     * Keep the canonical renderer path identical to the pre-intro implementation.
     * The common RENDERER mode must not pay custom-intro mapping/decoder overhead per frame.
     */
    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray = synchronized(lock) {
        val spec = RendererRuntime.active
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        when (project.introMode) {
            IntroMode.RENDERER -> renderEngineRgba(project, spec, frame, safeWidth, safeHeight)
            IntroMode.DISABLED -> renderEngineRgba(
                project,
                spec,
                frame.coerceAtLeast(0) + rendererIntroFrames(spec),
                safeWidth,
                safeHeight,
            )
            IntroMode.CUSTOM -> {
                require(project.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                val customFrames = IntroVideoSource.frameCount(project.introVideo, project.fps.coerceAtLeast(1))
                if (frame < customFrames) {
                    IntroVideoSource.renderRgba(project.introVideo, frame.coerceAtLeast(0), project.fps, safeWidth, safeHeight)
                } else {
                    renderEngineRgba(
                        project,
                        spec,
                        frame - customFrames + rendererIntroFrames(spec),
                        safeWidth,
                        safeHeight,
                    )
                }
            }
        }
    }

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap = synchronized(lock) {
        val spec = RendererRuntime.active
        val safeWidth = width.coerceAtLeast(2)
        val safeHeight = height.coerceAtLeast(2)
        when (project.introMode) {
            IntroMode.RENDERER -> renderEngine(project, spec, frame, safeWidth, safeHeight)
            IntroMode.DISABLED -> renderEngine(
                project,
                spec,
                frame.coerceAtLeast(0) + rendererIntroFrames(spec),
                safeWidth,
                safeHeight,
            )
            IntroMode.CUSTOM -> {
                require(project.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                val customFrames = IntroVideoSource.frameCount(project.introVideo, project.fps.coerceAtLeast(1))
                if (frame < customFrames) {
                    IntroVideoSource.render(project.introVideo, frame.coerceAtLeast(0), project.fps, safeWidth, safeHeight)
                } else {
                    renderEngine(
                        project,
                        spec,
                        frame - customFrames + rendererIntroFrames(spec),
                        safeWidth,
                        safeHeight,
                    )
                }
            }
        }
    }

    fun renderWithSpec(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap = synchronized(lock) {
        val previous = RendererRuntime.active
        return try {
            RendererRuntime.active = spec
            renderEngine(project.copy(introMode = IntroMode.RENDERER, introVideo = ""), spec, frame, width, height)
        } finally {
            RendererRuntime.active = previous
        }
    }

    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap = when (engine(spec)) {
        "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
            relationshipsPrecisionRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        } else {
            relationshipsRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
        "ribbon-exact" -> ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        else -> nativeRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
    }

    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray = when (engine(spec)) {
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
