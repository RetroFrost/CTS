package dev.infinitycomparison.cc

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap

internal class NativeGpuRenderer(
    private val context: Context,
    private val project: StudioProject,
    private val outputWidth: Int,
    private val outputHeight: Int,
) {
    private data class Texture(val id: Int, val width: Int, val height: Int)

    private val program = createProgram(vertexShader, fragmentShader)
    private val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
    private val textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
    private val alphaLocation = GLES20.glGetUniformLocation(program, "uAlpha")
    private val shineLocation = GLES20.glGetUniformLocation(program, "uShineProgress")
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val textures = object : LinkedHashMap<String, Texture>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Texture>?): Boolean {
            if (size <= 24 || eldest == null) return false
            GLES20.glDeleteTextures(1, intArrayOf(eldest.value.id), 0)
            return true
        }
    }
    private var drawingFrame = -1

    init {
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniform1i(samplerLocation, 0)
    }

    fun draw(frame: Int) {
        drawingFrame = frame
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val alpha = NativeTimeline.sceneAlpha(project, frame)
        if (alpha <= 0f) return
        val positions = NativeTimeline.positions(project, frame)
        val sceneFrame = NativeTimeline.sceneFrame(project, frame)
        val bodyOrder = if (sceneFrame < NativeTimeline.continuousStart) {
            val active = positions.keys.maxOrNull()
            if (active == null) emptyList() else listOf(active) + positions.keys.filter { it != active }.sorted()
        } else positions.keys.sorted()

        for (index in bodyOrder) {
            val card = project.cards[index]
            if (frame == 0) CrashJournal.updateExportStage("GPU first frame • preparing card texture", addEvent = false)
            val texture = texture("body:${card.id}:${card.hashCode()}") { NativeArtwork.body(context, card) }
            if (frame == 0) CrashJournal.updateExportStage("GPU first frame • drawing card texture", addEvent = false)
            drawTexture(texture, positions.getValue(index) + NativeTimeline.bodyInset, 0f, alpha)
        }
        NativeTimeline.creditsX(project, frame)?.let { left ->
            val texture = texture("credits:${project.name}") { NativeArtwork.credits(context, project) }
            drawTexture(texture, left + NativeTimeline.bodyInset, 0f, alpha)
        }
        val starts = NativeTimeline.cardStarts(project)
        for (index in positions.keys.sorted()) {
            val card = project.cards[index]
            if (!project.showBadges || card.value.isBlank()) continue
            val localFrame = sceneFrame - starts[index]
            val offset = NativeTimeline.badgeOffset(index, localFrame, project.settledScrollingBadges) ?: continue
            val texture = texture("badge-shell") { NativeArtwork.badgeShell() }
            val left = positions.getValue(index) + (NativeTimeline.slotPitch - NativeArtwork.badgeWidth) / 2f
            val shine = NativeTimeline.badgeShineProgress(index, localFrame) ?: -1f
            val affine = NativeTimeline.badgeAffine(index, localFrame)
            val scale = NativeTimeline.badgeScale(index, localFrame, frame)
            drawTexture(texture, left, NativeArtwork.badgeTop + offset, alpha, shine, affine = affine, scale = scale)
            NativeArtwork.badgeLines(card).forEachIndexed { lineIndex, line ->
                val progress = NativeTimeline.badgeTextProgress(
                    index, lineIndex, localFrame, project.settledScrollingBadges,
                )
                if (progress <= 0f) return@forEachIndexed
                val text = texture("badge-text:${card.id}:${card.badgeHeader}:${card.value}:$lineIndex") {
                    NativeArtwork.badgeText(context, line)
                }
                drawTexture(text, left, NativeArtwork.badgeTop + offset, alpha, affine = affine, scale = scale)
            }
        }
        for (index in positions.keys.sorted()) {
            val card = project.cards[index]
            if (card.imageLayer.lowercase() != "front" || card.image.isBlank()) continue
            val texture = texture("front:${card.id}:${card.hashCode()}") {
                NativeArtwork.frontArtwork(context, card)
                    ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
            drawTexture(texture, positions.getValue(index) + NativeTimeline.bodyInset, 0f, alpha)
        }
        drawOutro(frame, alpha)
    }

    fun release() {
        val ids = textures.values.map { it.id }.toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        textures.clear()
        GLES20.glDeleteProgram(program)
    }

    private fun texture(key: String, factory: () -> Bitmap): Texture = textures.getOrPut(key) {
        val source = factory()
        // Samsung's Android 12 Mali-G76 stack can hang or terminate the process when an RGB_565
        // bitmap is uploaded directly to a recordable MediaCodec EGL surface. Normalising the
        // upload to RGBA_8888 keeps the Canvas artwork unchanged and avoids that vendor path.
        val bitmap = try {
            if (source.config == Bitmap.Config.ARGB_8888) source
            else source.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("The renderer could not prepare an RGBA video texture.")
        } catch (error: Throwable) {
            source.recycle()
            throw error
        }
        try {
            if (drawingFrame == 0) CrashJournal.updateExportStage("GPU first frame • uploading RGBA texture", addEvent = false)
            val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            check(id != 0) { "The phone GPU could not allocate a video texture." }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            val glError = GLES20.glGetError()
            if (glError != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, intArrayOf(id), 0)
                error("The phone GPU rejected a video texture (0x${glError.toString(16)}).")
            }
            Texture(id, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
            if (bitmap !== source) source.recycle()
        }
    }

    private fun drawTexture(
        texture: Texture,
        left: Float,
        top: Float,
        alpha: Float,
        shine: Float = -1f,
        drawWidth: Float = texture.width.toFloat(),
        drawHeight: Float = texture.height.toFloat(),
        affine: NativeTimeline.Affine? = null,
        scale: Float = 1f,
    ) {
        val transform = affine ?: NativeTimeline.Affine(1f, 0f, 0f, 1f, 0f, 0f)
        val centreX = if (affine == null) 0f else 243.5f
        val centreY = if (affine == null) 0f else 203.5f
        fun point(x: Float, y: Float): NativeTimeline.Point {
            val transformedX = transform.a * x + transform.b * y + transform.e
            val transformedY = transform.c * x + transform.d * y + transform.f
            return NativeTimeline.Point(
                left + centreX + scale * (transformedX - centreX),
                top + centreY + scale * (transformedY - centreY),
            )
        }
        val bottomLeft = point(0f, drawHeight)
        val bottomRight = point(drawWidth, drawHeight)
        val topLeft = point(0f, 0f)
        val topRight = point(drawWidth, 0f)
        val points = listOf(bottomLeft, bottomRight, topLeft, topRight)
        if (points.maxOf { it.x } <= 0f || points.minOf { it.x } >= NativeTimeline.referenceWidth ||
            points.maxOf { it.y } <= 0f || points.minOf { it.y } >= NativeTimeline.referenceHeight) return
        fun clipX(value: Float) = value / NativeTimeline.referenceWidth * 2f - 1f
        fun clipY(value: Float) = 1f - value / NativeTimeline.referenceHeight * 2f
        vertices.clear()
        vertices.put(
            floatArrayOf(
                clipX(bottomLeft.x), clipY(bottomLeft.y), 0f, 1f,
                clipX(bottomRight.x), clipY(bottomRight.y), 1f, 1f,
                clipX(topLeft.x), clipY(topLeft.y), 0f, 0f,
                clipX(topRight.x), clipY(topRight.y), 1f, 0f,
            ),
        ).position(0)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.id)
        vertices.position(0)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(positionLocation)
        vertices.position(2)
        GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(textureLocation)
        GLES20.glUniform1f(alphaLocation, alpha)
        GLES20.glUniform1f(shineLocation, shine)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawOutro(frame: Int, alpha: Float) {
        val local = NativeTimeline.outroLocal(project, frame)
        if (local < 0) return
        val black = texture("solid:black") {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, android.graphics.Color.BLACK) }
        }
        drawTexture(black, 0f, 0f, 1f, drawWidth = NativeTimeline.referenceWidth, drawHeight = NativeTimeline.referenceHeight)
        if (project.cards.isNotEmpty()) {
            val groupX = NativeTimeline.outroGroupX(local)
            val card = project.cards.last()
            val body = texture("body:${card.id}:${card.hashCode()}") { NativeArtwork.body(context, card) }
            drawTexture(body, groupX + NativeTimeline.bodyInset, 0f, alpha)
            if (project.showBadges && card.value.isNotBlank()) {
                val badge = texture("badge-shell") { NativeArtwork.badgeShell() }
                val left = groupX + (NativeTimeline.slotPitch - NativeArtwork.badgeWidth) / 2f
                val identity = NativeTimeline.Affine(1f, 0f, 0f, 1f, 0f, 0f)
                drawTexture(badge, left, NativeArtwork.badgeTop, alpha, affine = identity, scale = 1.25f)
                NativeArtwork.badgeLines(card).forEachIndexed { lineIndex, line ->
                    val text = texture("badge-text:${card.id}:${card.badgeHeader}:${card.value}:$lineIndex") {
                        NativeArtwork.badgeText(context, line)
                    }
                    drawTexture(text, left, NativeArtwork.badgeTop, alpha, affine = identity, scale = 1.25f)
                }
            }
            val prompt = texture("outro-prompt:${project.name}:${project.outroPrompt}") { NativeArtwork.outroPrompt(context, project) }
            drawTexture(prompt, groupX + 523f, 0f, alpha)
        }
        NativeTimeline.outroActionBar(local)?.let { bounds ->
            val state = NativeTimeline.outroActionState(local)
            val action = texture("outro-action:$state") { NativeArtwork.outroActionBar(context, state) }
            drawTexture(action, bounds.left, bounds.top, alpha, drawWidth = bounds.width, drawHeight = bounds.height)
        }
        NativeTimeline.outroCursor(local)?.let { point ->
            val cursor = texture("outro-cursor") { NativeArtwork.cursor() }
            drawTexture(cursor, point.x, point.y, alpha)
        }
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
            return shader
        }
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vertexShader)
        GLES20.glAttachShader(result, fragmentShader)
        GLES20.glLinkProgram(result)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetProgramInfoLog(result) }
        return result
    }

    private companion object {
        const val vertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aTexCoord; }
        """
        const val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            uniform float uShineProgress;
            varying vec2 vTexCoord;
            void main() {
                vec4 colour = texture2D(uTexture, vTexCoord);
                if (uShineProgress >= 0.0) {
                    float diagonal = vTexCoord.x - (1.0 - vTexCoord.y) * 0.40;
                    float centre = mix(-0.50, 0.98, uShineProgress);
                    float distanceFromBand = abs(diagonal - centre);
                    float broad = (1.0 - smoothstep(0.04, 0.15, distanceFromBand)) * 0.19;
                    float core = (1.0 - smoothstep(0.00, 0.025, distanceFromBand)) * 0.48;
                    colour.rgb = mix(colour.rgb, vec3(1.0), min(0.72, broad + core) * colour.a);
                }
                gl_FragColor = vec4(colour.rgb, colour.a * uAlpha);
            }
        """
    }
}
