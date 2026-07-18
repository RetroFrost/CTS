package io.github.retrofrost.cts.android.export

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal class CodecInputSurface(
    private val surface: Surface,
) {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE
    private val textureRenderer = TextureRenderer()

    init {
        setupEgl()
        makeCurrent()
        textureRenderer.initialize()
    }

    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "Could not make encoder EGL surface current"
        }
    }

    fun draw(bitmap: Bitmap, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        textureRenderer.draw(bitmap)
    }

    fun setPresentationTime(nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanoseconds)
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    fun release() {
        if (display !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        textureRenderer.release()
        surface.release()
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "Could not get EGL display" }

        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            "Could not initialize EGL"
        }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "Could not choose EGL configuration"
        }
        val config = configs[0] ?: error("No recordable EGL configuration")

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        checkEgl("eglCreateContext")

        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        checkEgl("eglCreateWindowSurface")
    }

    private fun checkEgl(operation: String) {
        val error = EGL14.eglGetError()
        check(error == EGL14.EGL_SUCCESS) {
            "$operation failed with EGL error 0x${Integer.toHexString(error)}"
        }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}

private class TextureRenderer {
    private val vertices: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_DATA.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTEX_DATA)
            position(0)
        }

    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var textureCoordinateHandle = 0
    private var samplerHandle = 0
    private var textureAllocated = false

    fun initialize() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    fun draw(bitmap: Bitmap) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (!textureAllocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            textureAllocated = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
        GLES20.glUniform1i(samplerHandle, 0)

        vertices.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices,
        )

        vertices.position(2)
        GLES20.glEnableVertexAttribArray(textureCoordinateHandle)
        GLES20.glVertexAttribPointer(
            textureCoordinateHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            STRIDE_BYTES,
            vertices,
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureCoordinateHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun release() {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val result = GLES20.glCreateProgram()
        check(result != 0) { "Could not create OpenGL program" }
        GLES20.glAttachShader(result, vertex)
        GLES20.glAttachShader(result, fragment)
        GLES20.glLinkProgram(result)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linkStatus, 0)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "Could not link OpenGL program: ${GLES20.glGetProgramInfoLog(result)}"
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "Could not create shader" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Shader compilation failed: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    companion object {
        private const val STRIDE_BYTES = 4 * 4

        // Texture coordinates are vertically flipped because Android Bitmaps are top-left based.
        private val VERTEX_DATA = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

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
    }
}
