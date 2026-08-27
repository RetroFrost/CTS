package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
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
import android.opengl.GLUtils
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import kotlin.math.roundToInt

data class SelectedVideoCodec(
    val name: String,
    val mime: String,
    val label: String,
    val hardware: Boolean,
)

/** H.264-first selection for maximum playback compatibility. */
object HardwareCodecSelector {
    private const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC

    fun select(preference: EncoderPreference, width: Int, height: Int, fps: Int): SelectedVideoCodec {
        val order = when (preference) {
            EncoderPreference.AUTO -> listOf(AVC, HEVC)
            EncoderPreference.H264 -> listOf(AVC)
            EncoderPreference.H265 -> listOf(HEVC)
        }
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter(MediaCodecInfo::isEncoder)
        val hardware = infos.filter(::isHardware)

        fun find(candidates: List<MediaCodecInfo>): SelectedVideoCodec? {
            for (mime in order) {
                for (info in candidates) {
                    if (info.supportedTypes.none { it.equals(mime, true) }) continue
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                    if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
                    val supported = runCatching {
                        caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble())
                    }.getOrDefault(false)
                    if (!supported) continue
                    val hw = isHardware(info)
                    return SelectedVideoCodec(
                        info.name,
                        mime,
                        if (mime == AVC) "H.264 (AVC)" else "H.265 (HEVC)",
                        hw,
                    )
                }
            }
            return null
        }

        return find(hardware)
            ?: find(infos)
            ?: error("No compatible ${order.joinToString(" / ") { if (it == AVC) "H.264" else "H.265" }} encoder supports ${width}×${height} at $fps FPS.")
    }

    fun describe(preference: EncoderPreference, width: Int, height: Int, fps: Int): String =
        runCatching {
            val codec = select(preference, width, height, fps)
            "${codec.label} • ${codec.name}${if (codec.hardware) " • hardware" else " • software fallback"}"
        }.getOrElse { "Unavailable on this device" }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        val name = info.name.lowercase()
        return listOf("google", "android", "ffmpeg", "sw", "software").none(name::contains)
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
            val metadata = NativeRenderer.metadata(project)
            val selected = encodeVideo(video, metadata)
            val completed = if (project.soundtrack.isNotBlank() && project.soundtrackVolume > 0f) {
                checkCancelled()
                onProgress(84, "Soundtrack", "Encoding AAC")
                HardwareAudioTranscoder.transcode(
                    context = context,
                    source = Uri.parse(project.soundtrack),
                    output = audio,
                    durationUs = (metadata.duration * 1_000_000.0).toLong(),
                    volume = project.soundtrackVolume,
                    loop = project.soundtrackLoop,
                    shouldCancel = shouldCancel,
                )
                onProgress(92, "Muxing", "${selected.label} + AAC")
                mux(video, audio, final)
                final
            } else video

            checkCancelled()
            onProgress(96, "Saving", "Writing MP4")
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
            onProgress(97, "Video finished", "${selected.label} • BT.709 SDR")
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
        val selected = HardwareCodecSelector.select(project.encoderPreference, width, height, fps)
        val bitrate = if (selected.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            (width * height * fps * 0.075).roundToInt().coerceIn(3_000_000, 32_000_000)
        } else {
            (width * height * fps * 0.11).roundToInt().coerceIn(4_000_000, 45_000_000)
        }
        val format = MediaFormat.createVideoFormat(selected.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            if (Build.VERSION.SDK_INT >= 24) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

        val codec = MediaCodec.createByCodecName(selected.name)
        var inputSurface: Surface? = null
        var egl: CodecInputSurface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            egl = CodecInputSurface(inputSurface, width, height)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            val totalFrames = metadata.frameCount.coerceAtLeast(1)
            val started = System.nanoTime()
            onProgress(0, "Preparing GPU", "${egl.glRenderer} • ${selected.label}")

            repeat(totalFrames) { frame ->
                checkCancelled()
                NativeRenderer.renderInto(project, frame, frameBitmap)
                egl.draw(frameBitmap, frame * 1_000_000_000L / fps)
                val drain = drainCodec(codec, muxer, info, false, track, muxerStarted)
                track = drain.track
                muxerStarted = drain.started
                if (frame == 0 || frame + 1 == totalFrames || frame % fps == 0) {
                    val elapsed = (System.nanoTime() - started).coerceAtLeast(1L)
                    val renderedFps = (frame + 1) * 1_000_000_000.0 / elapsed
                    val remaining = ((totalFrames - frame - 1) / renderedFps.coerceAtLeast(0.01)).toInt()
                    onProgress(
                        ((frame + 1) * 82 / totalFrames).coerceIn(0, 82),
                        "Rendering + encoding",
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
            return selected
        } finally {
            frameBitmap.recycle()
            runCatching { egl?.release() }
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            inputSurface?.release()
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
                    val outFormat = codec.outputFormat
                    if (Build.VERSION.SDK_INT >= 24) {
                        outFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                        outFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        outFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    }
                    track = muxer.addTrack(outFormat)
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
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0), flags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun checkCancelled() {
        if (shouldCancel()) throw CancellationException("Export cancelled")
    }
}

private class CodecInputSurface(surface: Surface, private val width: Int, private val height: Int) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val eglSurface: android.opengl.EGLSurface
    private val program: Int
    private val texture: Int
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))
            position(0)
        }
    val glRenderer: String

    init {
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) { "Could not initialise EGL." }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "Could not choose an encoder EGL configuration." }
        val config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context." }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not create encoder EGL surface." }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "Could not activate the encoder EGL surface." }

        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        glRenderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Phone GPU"
    }

    fun draw(bitmap: Bitmap, presentationNanos: Long) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val sampler = GLES20.glGetUniformLocation(program, "uTexture")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationNanos)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "Could not submit frame to MediaCodec." }
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        GLES20.glDeleteProgram(program)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertexShader)
        GLES20.glAttachShader(result, fragmentShader)
        GLES20.glLinkProgram(result)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "Could not link encoder shader: ${GLES20.glGetProgramInfoLog(result)}" }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "Could not compile encoder shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
