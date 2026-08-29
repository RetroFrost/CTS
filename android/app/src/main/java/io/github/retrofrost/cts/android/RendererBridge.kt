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

    fun customIntroFrames(project: StudioProject): Int = synchronized(lock) {
        if (project.introMode != IntroMode.CUSTOM || project.introVideo.isBlank()) return@synchronized 0
        IntroVideoSource.frameCount(project.introVideo, project.fps.coerceAtLeast(1))
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

    fun renderRgba(project: StudioProject, frame: Int, width: Int, height: Int): ByteArray = synchronized(lock) {
        val spec = RendererRuntime.active
        val mapped = mapFrame(project, spec, frame)
        if (mapped.customIntroFrame != null) {
            return@synchronized IntroVideoSource.renderRgba(
                project.introVideo,
                mapped.customIntroFrame,
                project.fps,
                width.coerceAtLeast(2),
                height.coerceAtLeast(2),
            )
        }
        renderEngineRgba(project, spec, mapped.rendererFrame, width, height)
    }

    fun render(project: StudioProject, frame: Int, width: Int, height: Int): Bitmap = synchronized(lock) {
        val spec = RendererRuntime.active
        val mapped = mapFrame(project, spec, frame)
        if (mapped.customIntroFrame != null) {
            return@synchronized IntroVideoSource.render(
                project.introVideo,
                mapped.customIntroFrame,
                project.fps,
                width.coerceAtLeast(2),
                height.coerceAtLeast(2),
            )
        }
        renderEngine(project, spec, mapped.rendererFrame, width, height)
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

    private data class FrameMap(
        val rendererFrame: Int,
        val customIntroFrame: Int? = null,
    )

    private fun mapFrame(project: StudioProject, spec: RendererSpec, requestedFrame: Int): FrameMap {
        val frame = requestedFrame.coerceAtLeast(0)
        val intro = rendererIntroFrames(spec)
        return when (project.introMode) {
            IntroMode.RENDERER -> FrameMap(frame)
            IntroMode.DISABLED -> FrameMap(frame + intro)
            IntroMode.CUSTOM -> {
                require(project.introVideo.isNotBlank()) { "Choose a custom MP4 intro or switch the intro mode." }
                val customFrames = IntroVideoSource.frameCount(project.introVideo, project.fps.coerceAtLeast(1))
                if (frame < customFrames) FrameMap(0, frame)
                else FrameMap(frame - customFrames + intro)
            }
        }
    }

    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap = when (engine(spec)) {
        "relationships-exact" -> relationshipsRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        "ribbon-exact" -> ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        else -> nativeRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
    }

    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray = when (engine(spec)) {
        "relationships-exact" -> relationshipsRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
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
