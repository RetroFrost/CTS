package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.min

/** Decodes a user supplied MP4 intro into deterministic project-timeline frames. */
object IntroVideoSource {
    private data class Cached(
        val path: String,
        val retriever: MediaMetadataRetriever,
        val durationUs: Long,
        val sourceFrames: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        var batchStart: Int = -1,
        var batchFrames: List<Bitmap> = emptyList(),
    )

    private var cached: Cached? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    @Synchronized
    fun durationUs(path: String): Long = source(path).durationUs

    @Synchronized
    fun frameCount(path: String, fps: Int): Int {
        if (path.isBlank()) return 0
        val duration = source(path).durationUs
        return ceil(duration / 1_000_000.0 * fps.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun render(path: String, projectFrame: Int, projectFps: Int, width: Int, height: Int): Bitmap {
        val source = source(path)
        val safeFps = projectFps.coerceAtLeast(1)
        val timeUs = (projectFrame.coerceAtLeast(0) * 1_000_000L / safeFps)
            .coerceIn(0L, (source.durationUs - 1L).coerceAtLeast(0L))

        val sourceIndex = if (Build.VERSION.SDK_INT >= 28 && source.sourceFrames > 0) {
            ((timeUs.toDouble() / source.durationUs.coerceAtLeast(1L)) * source.sourceFrames)
                .toInt().coerceIn(0, source.sourceFrames - 1)
        } else {
            -1
        }
        val decoded = decodedFrame(source, sourceIndex, timeUs)

        val output = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        if (decoded != null) {
            val scale = min(
                output.width.toFloat() / decoded.width.coerceAtLeast(1),
                output.height.toFloat() / decoded.height.coerceAtLeast(1),
            )
            val drawnWidth = decoded.width * scale
            val drawnHeight = decoded.height * scale
            val left = (output.width - drawnWidth) / 2f
            val top = (output.height - drawnHeight) / 2f
            canvas.drawBitmap(
                decoded,
                Rect(0, 0, decoded.width, decoded.height),
                RectF(left, top, left + drawnWidth, top + drawnHeight),
                paint,
            )
        }
        return output
    }

    @Synchronized
    fun renderRgba(path: String, projectFrame: Int, projectFps: Int, width: Int, height: Int): ByteArray {
        val bitmap = render(path, projectFrame, projectFps, width, height)
        return try {
            ByteArray(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun clear() {
        releaseCached(cached)
        cached = null
    }

    private fun decodedFrame(source: Cached, sourceIndex: Int, timeUs: Long): Bitmap? {
        if (sourceIndex >= 0 && sourceIndex in source.batchStart until (source.batchStart + source.batchFrames.size)) {
            return source.batchFrames[sourceIndex - source.batchStart]
        }

        recycleBatch(source)
        if (Build.VERSION.SDK_INT >= 28 && source.sourceFrames > 0 && sourceIndex >= 0) {
            val bytesPerFrame = source.sourceWidth.coerceAtLeast(1).toLong() *
                source.sourceHeight.coerceAtLeast(1).toLong() * 4L
            val budget = 24L * 1024L * 1024L
            val desired = (budget / bytesPerFrame.coerceAtLeast(1L)).toInt().coerceIn(1, 6)
            val count = min(desired, source.sourceFrames - sourceIndex).coerceAtLeast(1)
            val batch = if (count > 1) {
                runCatching { source.retriever.getFramesAtIndex(sourceIndex, count) }.getOrNull()
            } else {
                null
            }
            val loaded = batch?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(runCatching { source.retriever.getFrameAtIndex(sourceIndex) }.getOrNull())
            if (loaded.isNotEmpty()) {
                source.batchStart = sourceIndex
                source.batchFrames = loaded
                return loaded.first()
            }
        }

        val fallback = source.retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
        if (fallback != null) {
            source.batchStart = if (sourceIndex >= 0) sourceIndex else 0
            source.batchFrames = listOf(fallback)
        }
        return fallback
    }

    private fun recycleBatch(source: Cached) {
        source.batchFrames.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        source.batchFrames = emptyList()
        source.batchStart = -1
    }

    private fun releaseCached(source: Cached?) {
        if (source == null) return
        recycleBatch(source)
        source.retriever.release()
    }

    private fun source(path: String): Cached {
        require(path.isNotBlank()) { "Choose an MP4 intro first." }
        val file = File(path)
        require(file.isFile) { "The selected intro MP4 is no longer available." }
        cached?.takeIf { it.path == file.absolutePath }?.let { return it }
        releaseCached(cached)

        val retriever = MediaMetadataRetriever().apply { setDataSource(file.absolutePath) }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: error("Could not read the intro duration.")
        val frames = if (Build.VERSION.SDK_INT >= 28) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
        } else {
            0
        }
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
        return Cached(
            path = file.absolutePath,
            retriever = retriever,
            durationUs = (durationMs * 1_000L).coerceAtLeast(1L),
            sourceFrames = frames.coerceAtLeast(0),
            sourceWidth = width.coerceAtLeast(1),
            sourceHeight = height.coerceAtLeast(1),
        ).also { cached = it }
    }
}
