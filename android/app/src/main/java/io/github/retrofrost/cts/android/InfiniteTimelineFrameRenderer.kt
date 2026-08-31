package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.io.File
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Source-measured Infinite Comparison timeline renderer.
 *
 * The coordinate system is always 1920x1080. A frame-exact bundle may request
 * 640x360 @ 30 fps; preview/export scale this logical canvas to that source
 * raster. The direct GPU exporter invokes [drawReference] on the codec Surface,
 * so generated frames do not pass through a full-frame Bitmap intermediary.
 */
class InfiniteTimelineFrameRenderer {
    private val imageCache = object : LinkedHashMap<String, Bitmap>(12, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > 12
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    @Synchronized
    fun render(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): Bitmap {
        val width = outputWidth.coerceAtLeast(2)
        val height = outputHeight.coerceAtLeast(2)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.save()
        canvas.scale(width / REFERENCE_WIDTH.toFloat(), height / REFERENCE_HEIGHT.toFloat())
        drawReference(canvas, project, frameIndex.coerceAtLeast(0), RendererRuntime.active)
        canvas.restore()
        return output
    }

    @Synchronized
    fun renderRgba(project: StudioProject, frameIndex: Int, outputWidth: Int, outputHeight: Int): ByteArray {
        val bitmap = render(project, frameIndex, outputWidth, outputHeight)
        return try {
            ByteArray(bitmap.byteCount).also { bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawReference(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        canvas.drawColor(spec.backgroundColor)
        if (frame < spec.continuousStartFrame) {
            drawBrandIntro(canvas, frame)
            drawDisclaimer(canvas, frame)
        }
        if (project.cards.isEmpty()) return

        val outroStart = InfiniteTimeline.outroStartFrame(project, spec)
        if (frame < spec.continuousStartFrame) {
            drawOpening(canvas, project, frame, spec)
        } else if (frame < outroStart) {
            drawConveyor(canvas, project, frame, spec)
        } else {
            drawOutro(canvas, project, frame, outroStart, spec)
        }
    }

    private fun drawBrandIntro(canvas: Canvas, frame: Int) {
        if (frame < 12) return

        val settle = when {
            frame < 30 -> sample(
                arrayOf(12f to 8.0f, 18f to 7.4f, 24f to 6.5f, 30f to 5.7f),
                frame.toFloat(),
            )
            frame < 90 -> sample(
                arrayOf(
                    30f to 5.7f, 45f to 3.7f, 60f to 2.35f, 75f to 1.47f,
                    90f to 1.0f,
                ),
                frame.toFloat(),
            )
            else -> 1f
        }
        val alpha = when {
            frame < 16 -> ((frame - 12) / 4f).coerceIn(0f, 1f)
            else -> 1f
        }

        val cx = 960f
        val cy = 466f
        canvas.save()
        canvas.scale(settle, settle, cx, cy)
        drawInfinityMark(canvas, cx, cy, alpha)
        canvas.restore()

        if (frame >= 128) {
            val line1 = "Infinite"
            val line2 = "Comparison"
            val visible = ((frame - 128) * 0.76f).toInt().coerceIn(0, line1.length + line2.length)
            textPaint.typeface = regular
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 35f
            textPaint.color = Color.argb((210f * alpha).roundToInt(), 220, 214, 214)
            val first = line1.take(min(line1.length, visible))
            val second = if (visible > line1.length) line2.take(visible - line1.length) else ""
            if (first.isNotBlank()) canvas.drawText(first, cx, 602f, textPaint)
            if (second.isNotBlank()) canvas.drawText(second, cx, 639f, textPaint)
        }
    }

    private fun drawInfinityMark(canvas: Canvas, cx: Float, cy: Float, alpha: Float) {
        val left = RectF(cx - 170f, cy - 84f, cx + 8f, cy + 84f)
        val right = RectF(cx - 8f, cy - 84f, cx + 170f, cy + 84f)

        fun stroke(width: Float, color: Int, startLeft: Float = 35f, startRight: Float = 215f) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = width
            paint.color = Color.argb(
                (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255),
                Color.red(color), Color.green(color), Color.blue(color),
            )
            canvas.drawArc(left, startLeft, 290f, false, paint)
            canvas.drawArc(right, startRight, 290f, false, paint)
        }

        stroke(32f, Color.argb(95, 0, 0, 0))
        stroke(22f, Color.rgb(24, 24, 24))
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 11f
        paint.shader = LinearGradient(
            cx - 170f, cy, cx + 170f, cy,
            intArrayOf(Color.rgb(204, 235, 20), Color.WHITE, Color.rgb(235, 128, 145)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        paint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
        canvas.drawArc(left, 35f, 290f, false, paint)
        canvas.drawArc(right, 215f, 290f, false, paint)
        paint.shader = null
        paint.alpha = 255
        stroke(4f, Color.rgb(240, 240, 240))

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.argb((235f * alpha).roundToInt(), 242, 242, 242)
        canvas.drawLine(cx - 100f, cy - 67f, cx + 100f, cy + 67f, paint)
        canvas.drawLine(cx + 100f, cy - 67f, cx - 100f, cy + 67f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawDisclaimer(canvas: Canvas, frame: Int) {
        if (frame < 222) return
        val text = "DISCLAIMER: This\ncomparison video\nis based on public\ndata, surveys,\npublic comments &\ndiscussions and\napproximate\nestimations that\nmight be subjected\nto some degree of\nerror."
        textPaint.typeface = regular
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 24f
        textPaint.color = Color.rgb(217, 208, 208)
        var y = 659f
        text.split('\n').forEach { line ->
            canvas.drawText(line, 1455f, y, textPaint)
            y += 31f
        }
    }

    private fun drawOpening(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        for (index in 0 until min(4, project.cards.size)) {
            val start = spec.openingStarts.getOrElse(index) { OPENING_STARTS[index] }
            if (frame < start) continue
            val local = frame - start
            val clipWidthSource = sample(
                arrayOf(
                    0f to 6f, 1f to 28f, 2f to 86f, 3f to 112f,
                    4f to 136f, 5f to 157f, 6f to 160f,
                ),
                local.toFloat(),
            ).coerceIn(0f, 160f)
            val slotLeft = index * SLOT_PITCH
            val half = clipWidthSource * 1.5f
            val centre = slotLeft + SLOT_PITCH / 2f
            canvas.save()
            canvas.clipRect(centre - half, 0f, centre + half, REFERENCE_HEIGHT.toFloat())
            drawCard(canvas, project, project.cards[index], slotLeft, spec, revealContent = openingContentProgress(local))
            canvas.restore()

            val badgeLocal = frame - (start + if (index == 0) 5 else 3)
            if (badgeLocal >= 0) {
                drawBadge(canvas, project, project.cards[index], slotLeft, spec, openingBadgeScale(badgeLocal), badgeLocal)
            }
        }
    }

    private fun openingContentProgress(local: Int): Float = when {
        local < 37 -> 0f
        local >= 58 -> 1f
        else -> ((local - 37) / 21f).coerceIn(0f, 1f)
    }

    private fun openingBadgeScale(local: Int): Float {
        val values = arrayOf(
            0f to 0.216f, 1f to 0.405f, 2f to 0.568f, 3f to 0.730f,
            4f to 0.878f, 5f to 1.027f, 6f to 1.081f, 7f to 1.135f,
            8f to 1.135f, 9f to 1.081f, 10f to 1.014f, 11f to 0.946f,
            12f to 0.905f, 13f to 0.932f, 14f to 0.973f, 15f to 1.014f,
            16f to 1.041f, 17f to 1.041f, 18f to 1.014f, 19f to 1.0f,
        )
        return sample(values, local.toFloat()).coerceAtLeast(0f)
    }

    private fun drawConveyor(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec) {
        val step = InfiniteTimeline.stepFrames(project, spec)
        val local = (frame - spec.continuousStartFrame).coerceAtLeast(0)
        val scroll = local.toFloat() / step * SLOT_PITCH
        val first = floor(scroll / SLOT_PITCH).toInt().coerceAtLeast(0)
        val last = min(project.cards.lastIndex, first + 4)
        for (index in first..last) {
            if (index !in project.cards.indices) continue
            val x = index * SLOT_PITCH - scroll
            if (x < REFERENCE_WIDTH && x + SLOT_PITCH > 0f) {
                drawCard(canvas, project, project.cards[index], x, spec, revealContent = 1f)
                drawBadge(canvas, project, project.cards[index], x, spec, 1f, Int.MAX_VALUE)
            }
        }
    }

    private fun drawCard(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        slotX: Float,
        spec: RendererSpec,
        revealContent: Float,
    ) {
        val right = slotX + CARD_WIDTH
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.rgb(30, 30, 30)
        canvas.drawRect(slotX, 0f, right, TITLE_TOP, paint)

        paint.color = spec.titleBackgroundColor
        canvas.drawRect(slotX, TITLE_TOP, right, TITLE_BOTTOM, paint)
        paint.color = Color.rgb(226, 128, 0)
        canvas.drawRect(slotX, TITLE_BOTTOM - 6f, right, TITLE_BOTTOM, paint)

        paint.color = spec.descriptionBackgroundColor
        canvas.drawRect(slotX, TITLE_BOTTOM, right, ART_TOP, paint)
        canvas.drawRect(slotX, ART_TOP, right, REFERENCE_HEIGHT.toFloat(), paint)

        paint.color = Color.rgb(8, 8, 8)
        canvas.drawRect(right, 0f, slotX + SLOT_PITCH, REFERENCE_HEIGHT.toFloat(), paint)

        val p = revealContent.coerceIn(0f, 1f)
        if (p <= 0f) return
        val contentAlpha = (255f * p).roundToInt().coerceIn(0, 255)
        canvas.saveLayerAlpha(null, contentAlpha)
        drawTitle(canvas, project, card, RectF(slotX + 9f, TITLE_TOP + 2f, right - 9f, TITLE_BOTTOM - 8f))
        drawDescription(canvas, project, card, RectF(slotX + 12f, TITLE_BOTTOM + 8f, right - 12f, ART_TOP - 5f))
        drawArtwork(canvas, card, RectF(slotX + 9f, ART_TOP + 3f, right - 9f, REFERENCE_HEIGHT - 3f))
        canvas.restore()
    }

    private fun drawTitle(canvas: Canvas, project: StudioProject, card: StudioCard, box: RectF) {
        val value = card.title.trim()
        if (value.isBlank()) return
        textPaint.typeface = ProjectFontResolver.resolve(project, medium, Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(20, 20, 20)
        drawWrapped(canvas, value, box, preferred = 50f, minimum = 32f, maxLines = 2, lineHeightFactor = 0.93f)
    }

    private fun drawDescription(canvas: Canvas, project: StudioProject, card: StudioCard, box: RectF) {
        val value = card.description.trim()
        if (value.isBlank()) return
        textPaint.typeface = ProjectFontResolver.resolve(project, regular, Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(226, 226, 226)
        drawWrapped(canvas, value, box, preferred = 27f, minimum = 19f, maxLines = 4, lineHeightFactor = 1.02f)
    }

    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
        val bitmap = loadImage(card.image) ?: return
        val leftCrop = card.imageCropLeft.coerceIn(0.0, 0.95)
        val topCrop = card.imageCropTop.coerceIn(0.0, 0.95)
        val rightCrop = card.imageCropRight.coerceIn(0.0, 0.95)
        val bottomCrop = card.imageCropBottom.coerceIn(0.0, 0.95)
        val srcLeft = (bitmap.width * leftCrop).roundToInt().coerceIn(0, bitmap.width - 1)
        val srcTop = (bitmap.height * topCrop).roundToInt().coerceIn(0, bitmap.height - 1)
        val srcRight = (bitmap.width * (1.0 - rightCrop)).roundToInt().coerceIn(srcLeft + 1, bitmap.width)
        val srcBottom = (bitmap.height * (1.0 - bottomCrop)).roundToInt().coerceIn(srcTop + 1, bitmap.height)
        val source = Rect(srcLeft, srcTop, srcRight, srcBottom)
        val sourceWidth = source.width().toFloat().coerceAtLeast(1f)
        val sourceHeight = source.height().toFloat().coerceAtLeast(1f)
        val contain = min(destination.width() / sourceWidth, destination.height() / sourceHeight)
        val scale = contain * card.imageScale.coerceIn(0.05, 12.0).toFloat()
        val w = sourceWidth * scale
        val h = sourceHeight * scale
        val cx = destination.centerX() + card.imageX.toFloat()
        val cy = destination.centerY() + card.imageY.toFloat()
        val target = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.save()
        canvas.clipRect(destination)
        if (card.imageRotation != 0.0) canvas.rotate(card.imageRotation.toFloat(), cx, cy)
        paint.alpha = 255
        paint.shader = null
        canvas.drawBitmap(bitmap, source, target, paint)
        canvas.restore()
    }

    private fun loadImage(path: String): Bitmap? {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return null
        imageCache[path]?.let { if (!it.isRecycled) return it }
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()?.also { imageCache[path] = it }
    }

    private fun drawBadge(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        slotX: Float,
        spec: RendererSpec,
        scale: Float,
        animationLocal: Int,
    ) {
        if (!project.showBadges || (card.value.isBlank() && card.badgeHeader.isBlank())) return
        val cx = slotX + 240f
        val cy = 239f
        canvas.save()
        canvas.scale(scale, scale, cx, cy)

        val badge = Path().apply {
            moveTo(slotX + 249f, 12f)
            lineTo(slotX + 417f, 96f)
            lineTo(slotX + 459f, 291f)
            lineTo(slotX + 339f, 441f)
            lineTo(slotX + 132f, 441f)
            lineTo(slotX + 18f, 297f)
            lineTo(slotX + 63f, 90f)
            close()
        }

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.save(); canvas.translate(5f, 8f); canvas.drawPath(badge, paint); canvas.restore()
        paint.shader = LinearGradient(
            0f, 10f, 0f, 445f,
            Color.rgb(239, 29, 23), Color.rgb(204, 11, 11),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(badge, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(194, 194, 184)
        canvas.drawPath(badge, paint)
        paint.style = Paint.Style.FILL

        val settled = animationLocal == Int.MAX_VALUE || animationLocal >= 37
        if (settled) drawBadgeText(canvas, project, card, cx, spec)
        if (animationLocal != Int.MAX_VALUE && animationLocal in 31..49) {
            val shineP = ((animationLocal - 31) / 18f).coerceIn(0f, 1f)
            drawBadgeShine(canvas, badge, slotX, shineP)
        }
        canvas.restore()
    }

    private fun drawBadgeShine(canvas: Canvas, badge: Path, slotX: Float, p: Float) {
        canvas.save()
        canvas.clipPath(badge)
        val x = slotX - 120f + 720f * p
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            x - 90f, 0f, x + 90f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(72, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.rotate(-12f, x, 220f)
        canvas.drawRect(x - 100f, -100f, x + 100f, 520f, paint)
        paint.shader = null
        canvas.restore()
    }

    private fun drawBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, cx: Float, spec: RendererSpec) {
        val header = card.badgeHeader.trim().ifBlank { "Before" }
        val pieces = card.value.trim().split(Regex("\\s+"), limit = 2)
        val primary = pieces.firstOrNull().orEmpty()
        val unit = pieces.getOrNull(1).orEmpty()
        textPaint.typeface = ProjectFontResolver.resolve(project, regular, Typeface.NORMAL)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = spec.badgeTextColor
        drawSingleFitted(canvas, header, cx, 128f, 57f, 30f, 380f)
        textPaint.typeface = ProjectFontResolver.resolve(project, regular, Typeface.NORMAL)
        drawSingleFitted(canvas, primary, cx, 256f, 126f, 58f, 330f)
        if (unit.isNotBlank()) drawSingleFitted(canvas, unit, cx, 360f, 61f, 32f, 360f)
    }

    private fun drawSingleFitted(canvas: Canvas, text: String, x: Float, centerY: Float, preferred: Float, minimum: Float, maxWidth: Float) {
        if (text.isBlank()) return
        var size = preferred
        textPaint.textSize = size
        while (textPaint.measureText(text) > maxWidth && size > minimum) {
            size -= 1f
            textPaint.textSize = size
        }
        val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text, x, baseline, textPaint)
    }

    private fun drawWrapped(
        canvas: Canvas,
        text: String,
        box: RectF,
        preferred: Float,
        minimum: Float,
        maxLines: Int,
        lineHeightFactor: Float,
    ) {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return
        var size = preferred
        var lines: List<String>
        while (true) {
            textPaint.textSize = size
            lines = wrap(normalized, box.width(), maxLines)
            val lineHeight = size * lineHeightFactor
            if ((lines.size * lineHeight <= box.height() && lines.all { textPaint.measureText(it) <= box.width() }) || size <= minimum) break
            size -= 1f
        }
        textPaint.textSize = size
        val lineHeight = size * lineHeightFactor
        val total = lineHeight * lines.size
        var y = box.centerY() - total / 2f + lineHeight / 2f
        for (line in lines) {
            val baseline = y - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(line, box.centerX(), baseline, textPaint)
            y += lineHeight
        }
    }

    private fun wrap(text: String, width: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        var index = 0
        while (index < words.size && lines.size < maxLines) {
            val word = words[index]
            val candidate = if (current.isBlank()) word else "$current $word"
            if (textPaint.measureText(candidate) <= width || current.isBlank()) {
                current = candidate
                index += 1
            } else {
                lines += current
                current = ""
            }
        }
        if (current.isNotBlank() && lines.size < maxLines) lines += current
        if (index < words.size && lines.isNotEmpty()) {
            var tail = lines.last()
            while (index < words.size) {
                val candidate = "$tail ${words[index]}"
                if (textPaint.measureText(candidate) > width) break
                tail = candidate
                index += 1
            }
            lines[lines.lastIndex] = tail
        }
        return lines
    }

    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, outroStart: Int, spec: RendererSpec) {
        val last = project.cards.last()
        val local = frame - outroStart
        val sourceLeft = sample(
            arrayOf(
                0f to 4f,
                5f to 100f,
                10f to 240f,
                15f to 318f,
                20f to 262f,
                25f to 206f,
                30f to 224f,
                35f to 260f,
                40f to 268f,
                45f to 264f,
                50f to 266f,
                55f to 268f,
                65f to 266f,
            ),
            local.toFloat(),
        )
        val x = sourceLeft * 3f
        drawCard(canvas, project, last, x, spec, 1f)
        drawBadge(canvas, project, last, x, spec, 1f, Int.MAX_VALUE)

        val message = "Although it seems impossible there are quite a few people who have swallowed their phone."
        val messageChars = ((frame - 5310) * 0.99f).toInt().coerceIn(0, message.length)
        if (messageChars > 0) {
            textPaint.typeface = regular
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.rgb(242, 242, 242)
            textPaint.textSize = 42f
            drawTypedParagraph(canvas, message.take(messageChars), RectF(12f, 605f, 756f, 835f), 1.08f)
        }

        val subscribe = "Subscribe"
        val rest = " for more\ncomparison videos."
        val subscribeStart = 5415
        if (frame >= subscribeStart) {
            val visible = ((frame - subscribeStart) * 1.08f).toInt().coerceAtLeast(0)
            textPaint.typeface = regular
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 47f
            val subVisible = subscribe.take(min(subscribe.length, visible))
            textPaint.color = Color.rgb(235, 126, 0)
            canvas.drawText(subVisible, 12f, 930f, textPaint)
            if (visible > subscribe.length) {
                val restVisible = rest.take((visible - subscribe.length).coerceAtMost(rest.length))
                textPaint.color = Color.rgb(242, 242, 242)
                val first = restVisible.substringBefore('\n')
                canvas.drawText(first, 12f + textPaint.measureText(subscribe), 930f, textPaint)
                if ('\n' in restVisible) {
                    canvas.drawText(restVisible.substringAfter('\n'), 12f, 985f, textPaint)
                }
            }
        }
    }

    private fun drawTypedParagraph(canvas: Canvas, text: String, box: RectF, lineHeightFactor: Float) {
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (textPaint.measureText(candidate) <= box.width() || current.isBlank()) current = candidate
            else { lines += current; current = word }
        }
        if (current.isNotBlank()) lines += current
        var y = box.top + textPaint.textSize
        val lineHeight = textPaint.textSize * lineHeightFactor
        for (line in lines) {
            canvas.drawText(line, box.left, y, textPaint)
            y += lineHeight
        }
    }

    private fun sample(keys: Array<Pair<Float, Float>>, value: Float): Float {
        if (value <= keys.first().first) return keys.first().second
        if (value >= keys.last().first) return keys.last().second
        for (index in 1 until keys.size) {
            val right = keys[index]
            if (value <= right.first) {
                val left = keys[index - 1]
                val p = (value - left.first) / (right.first - left.first).coerceAtLeast(0.0001f)
                return left.second + (right.second - left.second) * p
            }
        }
        return keys.last().second
    }

    companion object {
        const val REFERENCE_WIDTH = 1920
        const val REFERENCE_HEIGHT = 1080
        private const val SLOT_PITCH = 480f
        private const val CARD_WIDTH = 474f
        private const val TITLE_TOP = 471f
        private const val TITLE_BOTTOM = 594f
        private const val ART_TOP = 732f
        private val OPENING_STARTS = intArrayOf(188, 263, 331, 401)
    }
}

object InfiniteTimeline {
    private const val CANONICAL_CARDS = 35
    private const val CANONICAL_FRAMES = 5457
    private const val CANONICAL_OUTRO_START = 5305
    private const val TAIL_AFTER_LAST_ALIGNMENT = 205
    private const val OUTRO_FRAMES = CANONICAL_FRAMES - CANONICAL_OUTRO_START

    fun isInfinite(spec: RendererSpec): Boolean =
        spec.engine == "infinite-timeline-exact" || spec.id.startsWith("infinite.")

    fun stepFrames(project: StudioProject, spec: RendererSpec): Float {
        if (project.autoLength) return spec.continuousStepFrames.coerceAtLeast(1).toFloat()
        if (project.cards.size <= 4) return spec.continuousStepFrames.coerceAtLeast(1).toFloat()
        val targetFrames = (project.customLengthSeconds * project.fps.coerceAtLeast(1)).roundToInt()
        val fixed = spec.continuousStartFrame + TAIL_AFTER_LAST_ALIGNMENT + OUTRO_FRAMES
        val moving = (targetFrames - fixed).coerceAtLeast(project.cards.size - 4)
        return moving.toFloat() / (project.cards.size - 4).coerceAtLeast(1)
    }

    fun outroStartFrame(project: StudioProject, spec: RendererSpec): Int {
        if (project.autoLength && project.cards.size == CANONICAL_CARDS && spec.canonicalFrameCount == CANONICAL_FRAMES) {
            return CANONICAL_OUTRO_START
        }
        val steps = (project.cards.size - 4).coerceAtLeast(0)
        return spec.continuousStartFrame + (steps * stepFrames(project, spec)).roundToInt() + TAIL_AFTER_LAST_ALIGNMENT
    }

    fun totalFrameCount(project: StudioProject, spec: RendererSpec): Int {
        if (project.autoLength && project.cards.size == CANONICAL_CARDS && spec.canonicalFrameCount == CANONICAL_FRAMES) {
            return CANONICAL_FRAMES
        }
        return outroStartFrame(project, spec) + OUTRO_FRAMES
    }
}
