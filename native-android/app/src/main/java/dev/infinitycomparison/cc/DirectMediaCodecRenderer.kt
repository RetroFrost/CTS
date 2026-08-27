package dev.thedataguys.cc

import android.content.Context
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import java.io.File
import kotlin.math.max

class DirectMediaCodecRenderer(private val context: Context) {
    private val painter = ScenePainter()

    fun render(project: CompareProject, onProgress: (String) -> Unit): File {
        val outDir = File(context.getExternalFilesDir(null), "renders").apply { mkdirs() }
        val outFile = File(outDir, "cubical-compare-native-${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val width = project.width
        val height = project.height
        val fps = max(1, project.fps)
        val totalFrames = max(1, project.seconds * fps)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // Most-compatible SDR path: Android Canvas is sRGB, while MP4/H.264
            // players expect Rec.709-style SDR metadata. Avoid HDR/P3/full-range
            // surprises unless an advanced export mode is added later.
            if (Build.VERSION.SDK_INT >= 24) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            if (Build.VERSION.SDK_INT >= 23) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        var muxerStarted = false
        var videoTrack = -1

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.start()

            val info = MediaCodec.BufferInfo()
            for (frame in 0 until totalFrames) {
                drawFrame(inputSurface, project, frame)
                drain(codec, muxer, info, false, { videoTrack }) { newFormat ->
                    if (muxerStarted) return@drain
                    val muxerRef = requireNotNull(muxer) { "MediaMuxer was not created" }
                    applyCompatibleColourMetadata(newFormat)
                    videoTrack = muxerRef.addTrack(newFormat)
                    muxerRef.start()
                    muxerStarted = true
                }
                if (frame % fps == 0) {
                    onProgress("Rendering ${frame / fps}s / ${project.seconds}s with direct MediaCodec SDR")
                }
            }

            codec.signalEndOfInputStream()
            drain(codec, muxer, info, true, { videoTrack }) { newFormat ->
                if (!muxerStarted) {
                    val muxerRef = requireNotNull(muxer) { "MediaMuxer was not created" }
                    applyCompatibleColourMetadata(newFormat)
                    videoTrack = muxerRef.addTrack(newFormat)
                    muxerRef.start()
                    muxerStarted = true
                }
            }

            onProgress("Saved ${outFile.name}")
            return outFile
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            try { inputSurface?.release() } catch (_: Throwable) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
        }
    }

    private fun applyCompatibleColourMetadata(format: MediaFormat) {
        if (Build.VERSION.SDK_INT >= 24) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }

    private fun drawFrame(surface: Surface, project: CompareProject, frame: Int) {
        val canvas: Canvas = if (Build.VERSION.SDK_INT >= 23) surface.lockHardwareCanvas() else surface.lockCanvas(null)
        try {
            painter.drawVideoFrame(canvas, project, frame)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer?,
        info: MediaCodec.BufferInfo,
        end: Boolean,
        videoTrackProvider: () -> Int,
        onFormat: (MediaFormat) -> Unit
    ) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val track = videoTrackProvider()
                    if (buffer != null && info.size > 0 && track >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }
}
