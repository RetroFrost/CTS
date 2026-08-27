package dev.thedataguys.cc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.view.Surface
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

class DirectMediaCodecRenderer(private val context: Context) {
    private val painter = ScenePainter()

    fun render(project: CompareProject, onProgress: (String) -> Unit): File {
        require(project.width > 0 && project.height > 0) { "Invalid render size" }
        require(project.fps > 0) { "FPS must be greater than zero" }
        require(project.seconds > 0) { "Duration must be greater than zero" }

        val outDir = File(context.getExternalFilesDir(null), "renders").apply {
            check(exists() || mkdirs()) { "Could not create render directory" }
        }
        val outFile = File(outDir, "cubical-compare-native-${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) check(outFile.delete()) { "Could not replace old render" }

        val width = project.width
        val height = project.height
        val fps = max(1, project.fps)
        val totalFrames = max(1, project.seconds * fps)

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            if (Build.VERSION.SDK_INT >= 23) {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            if (Build.VERSION.SDK_INT >= 24) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

        val configuredEncoder = configurePreferredAvcEncoder(format)
        val codec = configuredEncoder.codec
        var codecStarted = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrack = -1
        var inputSurface: CodecInputSurface? = null
        var frameRenderer: CanvasTextureRenderer? = null
        var completed = false

        try {
            codec.start()
            codecStarted = true

            val eglInput = CodecInputSurface(configuredEncoder.inputSurface)
            inputSurface = eglInput
            eglInput.makeCurrent()

            val renderer = CanvasTextureRenderer(width, height, painter)
            frameRenderer = renderer

            val muxerRef = MediaMuxer(
                outFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = muxerRef

            val info = MediaCodec.BufferInfo()
            for (frameIndex in 0 until totalFrames) {
                renderer.draw(project, frameIndex)
                eglInput.setPresentationTime(
                    frameIndex * 1_000_000_000L / fps.toLong()
                )
                eglInput.swapBuffers()

                drain(
                    codec = codec,
                    muxer = muxerRef,
                    info = info,
                    endOfStream = false,
                    videoTrackProvider = { videoTrack }
                ) { outputFormat ->
                    check(!muxerStarted) { "Encoder changed format more than once" }
                    videoTrack = muxerRef.addTrack(outputFormat)
                    muxerRef.start()
                    muxerStarted = true
                }

                if (frameIndex % fps == 0 || frameIndex == totalFrames - 1) {
                    val secondsDone = minOf(project.seconds, frameIndex / fps)
                    onProgress(
                        "Rendering ${secondsDone}s / ${project.seconds}s • " +
                            "${codec.name} • H.264 BT.709 SDR"
                    )
                }
            }

            codec.signalEndOfInputStream()
            drain(
                codec = codec,
                muxer = muxerRef,
                info = info,
                endOfStream = true,
                videoTrackProvider = { videoTrack }
            ) { outputFormat ->
                check(!muxerStarted) { "Encoder changed format more than once" }
                videoTrack = muxerRef.addTrack(outputFormat)
                muxerRef.start()
                muxerStarted = true
            }

            check(muxerStarted && videoTrack >= 0) { "Encoder produced no video track" }
            completed = true
            onProgress("Saved ${outFile.name}")
            return outFile
        } finally {
            try {
                frameRenderer?.close()
            } catch (_: Throwable) {
            }
            try {
                inputSurface?.close()
            } catch (_: Throwable) {
            }
            if (codecStarted) {
                try {
                    codec.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                codec.release()
            } catch (_: Throwable) {
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Throwable) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            if (!completed) {
                outFile.delete()
            }
        }
    }

    private data class ConfiguredEncoder(
        val codec: MediaCodec,
        val inputSurface: Surface
    )

    private fun configurePreferredAvcEncoder(format: MediaFormat): ConfiguredEncoder {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS)
            .codecInfos
            .asSequence()
            .filter { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            .mapNotNull { info ->
                val capabilities = runCatching {
                    info.getCapabilitiesForType(mime)
                }.getOrNull() ?: return@mapNotNull null

                if (
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    !in capabilities.colorFormats
                ) {
                    return@mapNotNull null
                }

                val softwareName =
                    info.name.startsWith("OMX.google.", ignoreCase = true) ||
                        info.name.startsWith("c2.android.", ignoreCase = true)

                val score = when {
                    Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated -> 100
                    softwareName -> 0
                    else -> 50
                }
                score to info.name
            }
            .sortedByDescending { it.first }
            .map { it.second }
            .toList()

        for (codecName in candidates) {
            val codec = runCatching {
                MediaCodec.createByCodecName(codecName)
            }.getOrNull() ?: continue

            try {
                codec.configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                return ConfiguredEncoder(codec, codec.createInputSurface())
            } catch (_: Throwable) {
                try {
                    codec.release()
                } catch (_: Throwable) {
                }
            }
        }

        val fallback = MediaCodec.createEncoderByType(mime)
        try {
            fallback.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            return ConfiguredEncoder(fallback, fallback.createInputSurface())
        } catch (error: Throwable) {
            try {
                fallback.release()
            } catch (_: Throwable) {
            }
            throw error
        }
    }

    private fun drain(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        videoTrackProvider: () -> Int,
        onFormatChanged: (MediaFormat) -> Unit
    ) {
        var emptyPolls = 0

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(
                info,
                if (endOfStream) 10_000L else 0L
            )

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    emptyPolls++
                    check(emptyPolls < 500) { "Timed out waiting for encoder EOS" }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    emptyPolls = 0
                    onFormatChanged(codec.outputFormat)
                }

                outputIndex >= 0 -> {
                    emptyPolls = 0
                    val encodedData = codec.getOutputBuffer(outputIndex)
                    checkNotNull(encodedData) { "Encoder returned a null output buffer" }

                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }

                    if (info.size > 0) {
                        val videoTrack = videoTrackProvider()
                        check(videoTrack >= 0) {
                            "Encoder produced samples before output format"
                        }
                        encodedData.position(info.offset)
                        encodedData.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, encodedData, info)
                    }

                    val eos =
                        (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)

                    if (eos) return
                }
            }
        }
    }
}

private class CodecInputSurface(
    private val surface: Surface
) : Closeable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "Could not get EGL display" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) {
            "Could not initialise EGL"
        }

        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                configAttributes,
                0,
                configs,
                0,
                configs.size,
                numConfigs,
                0
            ) && numConfigs[0] > 0
        ) {
            "No recordable EGL config"
        }

        val eglConfig = checkNotNull(configs[0]) { "EGL config was null" }
        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        context = EGL14.eglCreateContext(
            display,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create EGL context" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            eglConfig,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) {
            "Could not create EGL window surface"
        }
    }

    fun makeCurrent() {
        check(
            EGL14.eglMakeCurrent(
                display,
                eglSurface,
                eglSurface,
                context
            )
        ) {
            "Could not make codec EGL surface current"
        }
    }

    fun setPresentationTime(presentationTimeNanos: Long) {
        check(
            EGLExt.eglPresentationTimeANDROID(
                display,
                eglSurface,
                presentationTimeNanos
            )
        ) {
            "Could not set frame presentation time"
        }
    }

    fun swapBuffers() {
        check(EGL14.eglSwapBuffers(display, eglSurface)) {
            "Could not submit frame to MediaCodec"
        }
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(display, eglSurface)
            }
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }

        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface.release()
    }
}

private class CanvasTextureRenderer(
    private val width: Int,
    private val height: Int,
    private val painter: ScenePainter
) : Closeable {
    private val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val vertexBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )
    private val textureBuffer = floatBufferOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    private val program: Int
    private val textureId: Int
    private val positionLocation: Int
    private val textureCoordinateLocation: Int
    private val textureSamplerLocation: Int

    init {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureSamplerLocation = GLES20.glGetUniformLocation(program, "uTexture")

        check(positionLocation >= 0) { "Missing aPosition attribute" }
        check(textureCoordinateLocation >= 0) { "Missing aTexCoord attribute" }
        check(textureSamplerLocation >= 0) { "Missing uTexture uniform" }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        check(textureId != 0) { "Could not create GL texture" }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        checkGlError("initialise texture renderer")
    }

    fun draw(project: CompareProject, frameIndex: Int) {
        painter.drawVideoFrame(canvas, project, frameIndex)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        GLES20.glUniform1i(textureSamplerLocation, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )

        textureBuffer.position(0)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glVertexAttribPointer(
            textureCoordinateLocation,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureBuffer
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(textureCoordinateLocation)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        checkGlError("draw frame")
    }

    override fun close() {
        GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        GLES20.glDeleteProgram(program)
        bitmap.recycle()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        val result = GLES20.glCreateProgram()
        check(result != 0) { "Could not create GL program" }

        GLES20.glAttachShader(result, vertexShader)
        GLES20.glAttachShader(result, fragmentShader)
        GLES20.glLinkProgram(result)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val message = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)
            error("Could not link GL program: $message")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Could not create GL shader" }

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            error("Could not compile GL shader: $message")
        }
        return shader
    }

    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) {
            "$operation failed with GL error 0x${Integer.toHexString(error)}"
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;

            void main() {
                gl_Position = aPosition;
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

        private fun floatBufferOf(vararg values: Float): FloatBuffer {
            return ByteBuffer
                .allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
        }
    }
}
