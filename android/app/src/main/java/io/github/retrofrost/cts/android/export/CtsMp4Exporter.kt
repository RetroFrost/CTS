package io.github.retrofrost.cts.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

enum class CtsExportPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val bitRate: Int,
) {
    HD("720p", 1280, 720, 6_000_000),
    FULL_HD("1080p", 1920, 1080, 12_000_000),
}

data class CtsExportResult(
    val frameCount: Int,
    val durationSeconds: Float,
    val bytes: Long,
    val hasAudio: Boolean,
)

object CtsMp4Exporter {
    private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FRAME_RATE = 30
    private const val I_FRAME_INTERVAL_SECONDS = 1
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val VIDEO_PROGRESS_WITH_AUDIO = 0.78f
    private const val AUDIO_PROGRESS_WEIGHT = 0.17f

    suspend fun export(
        context: Context,
        project: CtsProject,
        destination: Uri,
        preset: CtsExportPreset,
        onProgress: suspend (Float) -> Unit = {},
    ): CtsExportResult = withContext(Dispatchers.Default) {
        require(project.cards.isNotEmpty()) { "Add at least one card before exporting." }

        val normalized = project.normalized()
        val duration = TimelineEngine.duration(normalized).coerceAtLeast(1f / FRAME_RATE)
        val durationUs = (duration * 1_000_000L).toLong()
        val frameCount = ceil(duration * FRAME_RATE).toInt().coerceAtLeast(1)
        val videoFile = File.createTempFile("cts-video-", ".mp4", context.cacheDir)
        val audioFile = File.createTempFile("cts-audio-", ".m4a", context.cacheDir)
        val combinedFile = File.createTempFile("cts-final-", ".mp4", context.cacheDir)
        var exportedFile = videoFile

        suspend fun report(value: Float) {
            withContext(Dispatchers.Main.immediate) {
                onProgress(value.coerceIn(0f, 1f))
            }
        }

        try {
            val videoWeight = if (normalized.soundtrack == null) {
                1f
            } else {
                VIDEO_PROGRESS_WITH_AUDIO
            }
            encodeVideo(
                context = context,
                project = normalized,
                preset = preset,
                duration = duration,
                frameCount = frameCount,
                outputFile = videoFile,
                onProgress = { progress -> report(progress * videoWeight) },
            )

            normalized.soundtrack?.let { soundtrack ->
                CtsAudioTranscoder.transcodeToAac(
                    context = context.applicationContext,
                    soundtrack = soundtrack,
                    durationUs = durationUs,
                    outputFile = audioFile,
                    onProgress = { progress ->
                        report(
                            VIDEO_PROGRESS_WITH_AUDIO +
                                progress * AUDIO_PROGRESS_WEIGHT,
                        )
                    },
                )
                report(0.97f)
                CtsAvMuxer.combine(
                    videoFile = videoFile,
                    audioFile = audioFile,
                    outputFile = combinedFile,
                    durationUs = durationUs,
                )
                exportedFile = combinedFile
            }

            report(0.99f)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    exportedFile.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Android could not open the selected MP4 destination.")
            }
            report(1f)

            CtsExportResult(
                frameCount = frameCount,
                durationSeconds = duration,
                bytes = exportedFile.length(),
                hasAudio = normalized.soundtrack != null,
            )
        } finally {
            videoFile.delete()
            audioFile.delete()
            combinedFile.delete()
        }
    }

    private suspend fun encodeVideo(
        context: Context,
        project: CtsProject,
        preset: CtsExportPreset,
        duration: Float,
        frameCount: Int,
        outputFile: File,
        onProgress: suspend (Float) -> Unit,
    ) {
        val renderer = CtsCanvasSceneRenderer(context.applicationContext, project)
        renderer.preloadImages()

        val format = MediaFormat.createVideoFormat(MIME_TYPE, preset.width, preset.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, preset.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var inputSurface: CodecInputSurface? = null
        var muxerStarted = false
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        val bitmap = Bitmap.createBitmap(preset.width, preset.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val codecSurface = codec.createInputSurface()
            codec.start()
            val surface = CodecInputSurface(codecSurface)
            inputSurface = surface

            fun drainEncoder(endOfStream: Boolean): Boolean {
                var reachedEnd = false
                while (true) {
                    val status = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                    when {
                        status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }

                        status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder output format changed twice." }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        status >= 0 -> {
                            val encodedData = codec.getOutputBuffer(status)
                                ?: error("Encoder returned an empty output buffer.")

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0) {
                                check(muxerStarted) { "MediaMuxer has not started." }
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }

                            reachedEnd =
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(status, false)
                            if (reachedEnd) break
                        }
                    }
                }
                return reachedEnd
            }

            for (frameIndex in 0 until frameCount) {
                coroutineContext.ensureActive()
                val timeSeconds = (frameIndex.toFloat() / FRAME_RATE).coerceAtMost(duration)
                renderer.drawFrame(canvas, preset.width, preset.height, timeSeconds)

                surface.makeCurrent()
                surface.draw(bitmap, preset.width, preset.height)
                surface.setPresentationTime(
                    frameIndex.toLong() * 1_000_000_000L / FRAME_RATE,
                )
                check(surface.swapBuffers()) { "Could not submit frame to video encoder." }
                drainEncoder(endOfStream = false)

                if (frameIndex == frameCount - 1 || frameIndex % 3 == 0) {
                    onProgress((frameIndex + 1f) / frameCount)
                }
            }

            codec.signalEndOfInputStream()
            while (!drainEncoder(endOfStream = true)) {
                coroutineContext.ensureActive()
            }
        } finally {
            bitmap.recycle()
            runCatching { inputSurface?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
