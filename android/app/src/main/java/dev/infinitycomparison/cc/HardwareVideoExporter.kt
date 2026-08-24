package dev.infinitycomparison.cc

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

data class SelectedVideoCodec(
    val name: String,
    val mime: String,
    val label: String,
)

object HardwareCodecSelector {
    private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    fun select(
        preference: EncoderPreference,
        width: Int,
        height: Int,
        fps: Int,
    ): SelectedVideoCodec = candidates(preference, width, height, fps).first()

    fun candidates(
        preference: EncoderPreference,
        width: Int,
        height: Int,
        fps: Int,
    ): List<SelectedVideoCodec> {
        val mimeOrder = when (preference) {
            EncoderPreference.AUTO -> listOf(HEVC, AVC)
            EncoderPreference.H264 -> listOf(AVC)
            EncoderPreference.H265 -> listOf(HEVC)
        }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder && isHardware(it) }
            .toList()

        val selected = buildList {
            for (mime in mimeOrder) {
                for (info in codecs) {
                    if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                    val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                    if (!capabilities.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
                    val supported = runCatching {
                        capabilities.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                    }.getOrDefault(false)
                    if (!supported) continue
                    add(
                        SelectedVideoCodec(
                            name = info.name,
                            mime = mime,
                            label = if (mime == HEVC) "H.265 (HEVC)" else "H.264 (AVC)",
                        ),
                    )
                    break
                }
            }
        }
        if (selected.isNotEmpty()) return selected
        val requested = when (preference) {
            EncoderPreference.AUTO -> "H.265 or H.264"
            EncoderPreference.H264 -> "H.264"
            EncoderPreference.H265 -> "H.265"
        }
        error("No hardware $requested encoder supports ${width}×${height} at $fps FPS.")
    }

    fun describe(preference: EncoderPreference, width: Int, height: Int, fps: Int): String =
        runCatching {
            val selected = select(preference, width, height, fps)
            "${selected.label} • ${selected.name}"
        }.getOrElse { "Unavailable on this device" }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return listOf("google", "android", "ffmpeg", "sw", "software")
            .none(name::contains)
    }
}

class HardwareVideoExporter(
    private val context: Context,
    private val project: StudioProject,
    private val shouldCancel: () -> Boolean,
    private val onProgress: (Int, String, String) -> Unit,
) {
    fun export(destination: Uri) {
        require(project.cards.isNotEmpty()) { "Add at least one card before exporting." }
        val marker = System.nanoTime()
        val video = File(context.cacheDir, "cc-$marker-video.mp4")
        val audio = File(context.cacheDir, "cc-$marker-audio.m4a")
        val final = File(context.cacheDir, "cc-$marker-final.mp4")
        try {
            val metadata = RendererBridge.metadata(project)
            val selected = encodeVideo(video, metadata)
            val completed = if (project.soundtrack.isNotBlank() && project.soundtrackVolume > 0f) {
                checkCancelled()
                onProgress(84, "Soundtrack", "Hardware AAC encoding")
                HardwareAudioTranscoder.transcode(
                    context = context,
                    source = Uri.parse(project.soundtrack),
                    output = audio,
                    durationUs = (metadata.duration * 1_000_000.0).toLong(),
                    volume = project.soundtrackVolume,
                    loop = project.soundtrackLoop,
                    shouldCancel = shouldCancel,
                )
                onProgress(94, "Muxing", "${selected.label} + AAC")
                mux(video, audio, final)
                final
            } else {
                video
            }
            checkCancelled()
            onProgress(98, "Saving", "Writing the finished MP4")
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { "Could not open the selected output file." }
                FileInputStream(completed).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            onProgress(100, "Finished", "MP4 saved • ${selected.label}")
        } finally {
            video.delete()
            audio.delete()
            final.delete()
        }
    }

    private fun encodeVideo(output: File, metadata: RenderMetadata): SelectedVideoCodec {
        val width = project.width.coerceAtLeast(2).let { it - it % 2 }
        val height = project.height.coerceAtLeast(2).let { it - it % 2 }
        val fps = project.fps.coerceIn(1, 120)
        val candidates = HardwareCodecSelector.candidates(project.encoderPreference, width, height, fps)
        var lastFailure: Throwable? = null
        candidates.forEachIndexed { index, selected ->
            try {
                encodeVideoWithCodec(output, metadata, width, height, fps, selected)
                return selected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (memory: OutOfMemoryError) {
                throw memory
            } catch (error: Throwable) {
                lastFailure = error
                output.delete()
                val fallback = candidates.getOrNull(index + 1)
                if (fallback != null) {
                    onProgress(
                        0,
                        "Encoder fallback",
                        "${selected.label} failed • trying ${fallback.label}",
                    )
                }
            }
        }
        val reason = lastFailure?.message?.takeIf(String::isNotBlank) ?: lastFailure?.javaClass?.simpleName
        throw IllegalStateException(
            listOfNotNull(
                "Every compatible hardware encoder failed. Try selecting H.264 in Settings.",
                reason,
            ).joinToString(" "),
            lastFailure,
        )
    }

    private fun encodeVideoWithCodec(
        output: File,
        metadata: RenderMetadata,
        width: Int,
        height: Int,
        fps: Int,
        selected: SelectedVideoCodec,
    ) {
        val requestedBitrate = if (selected.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            (width * height * fps * 0.075).roundToInt().coerceIn(3_000_000, 32_000_000)
        } else {
            (width * height * fps * 0.11).roundToInt().coerceIn(4_000_000, 45_000_000)
        }
        val capabilities = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.name == selected.name }
            ?.let { runCatching { it.getCapabilitiesForType(selected.mime) }.getOrNull() }
        val bitrate = capabilities?.videoCapabilities?.bitrateRange?.let { range ->
            requestedBitrate.coerceIn(range.lower, range.upper)
        } ?: requestedBitrate
        val format = MediaFormat.createVideoFormat(selected.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            val encoder = capabilities?.encoderCapabilities
            when {
                encoder?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true ->
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                encoder?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true ->
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }
        val codec = MediaCodec.createByCodecName(selected.name)
        var inputSurface: Surface? = null
        var egl: CodecInputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            egl = CodecInputSurface(inputSurface, width, height, context, project)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            val totalFrames = metadata.frameCount.coerceAtLeast(1)
            val started = System.nanoTime()
            onProgress(0, "Preparing GPU", "${egl.glRenderer} • ${selected.label}")

            repeat(totalFrames) { frame ->
                checkCancelled()
                egl.draw(frame, frame * 1_000_000_000L / fps)
                val drain = drainCodec(codec, muxer, info, false, track, muxerStarted)
                track = drain.track
                muxerStarted = drain.started
                if (frame == 0 || frame + 1 == totalFrames || frame % fps == 0) {
                    val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
                    val renderedFps = (frame + 1) * 1_000_000_000.0 / elapsed
                    val remaining = ((totalFrames - frame - 1) / renderedFps.coerceAtLeast(0.01)).toInt()
                    onProgress(
                        ((frame + 1) * 82 / totalFrames).coerceIn(0, 82),
                        "GPU rendering + encoding",
                        "${frame + 1} / $totalFrames • ${"%.1f".format(renderedFps)} fps • ${remaining}s left",
                    )
                }
            }
            codec.signalEndOfInputStream()
            var ended = false
            while (!ended) {
                checkCancelled()
                val drain = drainCodec(codec, muxer, info, true, track, muxerStarted)
                track = drain.track
                muxerStarted = drain.started
                ended = drain.ended
            }
        } finally {
            runCatching { egl?.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { inputSurface?.release() }
        }
    }

    private data class DrainResult(val track: Int, val started: Boolean, val ended: Boolean)

    private fun drainCodec(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        wait: Boolean,
        currentTrack: Int,
        alreadyStarted: Boolean,
    ): DrainResult {
        var track = currentTrack
        var started = alreadyStarted
        var ended = false
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(track, started, ended)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "Video encoder format changed twice." }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                index >= 0 -> {
                    val buffer = requireNotNull(codec.getOutputBuffer(index))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(started) { "Video encoder produced data before its format." }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (ended) return DrainResult(track, started, true)
                }
            }
        }
    }

    private fun mux(video: File, audio: File, output: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoIndex = (0 until videoExtractor.trackCount).first {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioIndex = (0 until audioExtractor.trackCount).first {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            videoExtractor.selectTrack(videoIndex)
            audioExtractor.selectTrack(audioIndex)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoIndex))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioIndex))
            muxer.start()
            copyTrack(videoExtractor, muxer, outVideo)
            copyTrack(audioExtractor, muxer, outAudio)
            muxer.stop()
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            muxer.release()
        }
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            checkCancelled()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0), flags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun checkCancelled() {
        if (shouldCancel()) throw CancellationException("Export cancelled")
    }
}

private class CodecInputSurface(
    surface: Surface,
    private val width: Int,
    private val height: Int,
    appContext: Context,
    project: StudioProject,
) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val renderer: NativeGpuRenderer
    val glRenderer: String

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "The phone did not provide an EGL display." }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Could not initialise EGL." }
        val attributeChoices = listOf(
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 5, EGL14.EGL_GREEN_SIZE, 6, EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_ALPHA_SIZE, 0, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            ),
        )
        var chosenConfig: android.opengl.EGLConfig? = null
        for (attributes in attributeChoices) {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                chosenConfig = configs[0]
                if (chosenConfig != null) break
            }
        }
        val config = requireNotNull(chosenConfig) { "Could not choose a recordable encoder EGL configuration." }
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create the encoder OpenGL context." }
        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not create the encoder EGL surface." }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "Could not activate the encoder EGL surface."
        }
        glRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Phone GPU"
        renderer = NativeGpuRenderer(appContext.applicationContext, project, width, height)
    }

    fun draw(frame: Int, presentationNanos: Long) {
        if (frame == 0) CrashJournal.updateExportStage("GPU first frame • compositor entered", addEvent = false)
        renderer.draw(frame)
        if (frame == 0) CrashJournal.updateExportStage("GPU first frame • compositor finished", addEvent = false)
        val glError = GLES20.glGetError()
        check(glError == GLES20.GL_NO_ERROR) { "OpenGL export failed with error 0x${glError.toString(16)}." }
        check(EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNanos)) {
            "Could not set the encoder frame timestamp."
        }
        if (frame == 0) CrashJournal.updateExportStage("GPU first frame • swapping encoder surface", addEvent = false)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "The GPU encoder surface stopped responding." }
        if (frame == 0) CrashJournal.updateExportStage("GPU first frame • submitted to encoder", addEvent = false)
    }

    fun release() {
        renderer.release()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }

}
