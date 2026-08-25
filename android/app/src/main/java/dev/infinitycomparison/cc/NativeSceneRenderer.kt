package dev.infinitycomparison.cc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.min

internal object NativeArtwork {
    data class BadgeLine(val text: String, val centreY: Float, val size: Float, val maxWidth: Float, val bold: Boolean)

    private const val badgeStageHeight = 476
    private const val titleTop = 476
    private const val titleHeight = 102
    private const val lowerTop = 578
    const val badgeWidth = 480
    const val badgeHeight = 430
    const val badgeTop = 32f

    @Volatile private var extraBold: Typeface? = null
    @Volatile private var semiBold: Typeface? = null
    @Volatile private var medium: Typeface? = null

    fun body(context: Context, card: StudioCard): Bitmap {
        val bitmap = Bitmap.createBitmap(NativeTimeline.bodyWidth, NativeTimeline.bodyHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(29, 29, 29))
        val title = card.title.trim().replace(Regex("\\s+"), " ")
        val description = card.description.trim().replace(Regex("\\s+"), " ")
        canvas.drawRect(0f, lowerTop.toFloat(), NativeTimeline.bodyWidth.toFloat(), NativeTimeline.bodyHeight.toFloat(), Paint().apply {
            color = Color.rgb(108, 103, 96)
        })

        if (card.imageLayer.lowercase() != "front") {
            drawArtwork(context, canvas, card, lowerTop + 78, NativeTimeline.bodyHeight - lowerTop - 78)
        }
        canvas.drawRect(0f, titleTop.toFloat(), NativeTimeline.bodyWidth.toFloat(), (titleTop + titleHeight).toFloat(), Paint().apply {
            color = Color.rgb(222, 222, 217)
        })
        if (title.isNotBlank()) {
            drawFittedCentredText(
                canvas, title, RectF(9f, titleTop + 4f, NativeTimeline.bodyWidth - 9f, titleTop + titleHeight - 4f),
                43f, 25f, 2, font(context, "Poppins-Bold.ttf", true), Color.rgb(22, 22, 22),
            )
        }
        if (description.isNotBlank()) {
            drawFittedCentredText(
                canvas, description, RectF(22f, lowerTop + 15f, NativeTimeline.bodyWidth - 22f, lowerTop + 94f),
                25f, 17f, 3, font(context, "Poppins-Medium.ttf", false), Color.rgb(250, 250, 248),
            )
        }
        val divider = Paint().apply { color = Color.rgb(15, 15, 15); strokeWidth = 2f }
        canvas.drawLine(0f, 0f, 0f, NativeTimeline.bodyHeight.toFloat(), divider)
        canvas.drawLine(NativeTimeline.bodyWidth - 1f, 0f, NativeTimeline.bodyWidth - 1f, NativeTimeline.bodyHeight.toFloat(), divider)
        return bitmap
    }

    fun frontArtwork(context: Context, card: StudioCard): Bitmap? {
        if (card.imageLayer.lowercase() != "front" || card.image.isBlank()) return null
        return Bitmap.createBitmap(NativeTimeline.bodyWidth, NativeTimeline.bodyHeight, Bitmap.Config.ARGB_8888).also {
            drawArtwork(context, Canvas(it), card, lowerTop + 78, NativeTimeline.bodyHeight - lowerTop - 78)
        }
    }

    fun badgeShell(): Bitmap {
        val bitmap = Bitmap.createBitmap(badgeWidth, badgeHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val face = Path().apply {
            moveTo(243f, 33f)
            lineTo(391f, 118f)
            lineTo(391f, 290f)
            lineTo(245f, 374f)
            lineTo(96f, 290f)
            lineTo(96f, 118f)
            close()
        }
        canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            setShadowLayer(8f, 5f, 7f, color)
        })
        canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 8, 9) })
        canvas.drawPath(face, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.rgb(171, 3, 7)
        })
        return bitmap
    }

    fun badgeLines(card: StudioCard): List<BadgeLine> {
        val words = card.value.trim().uppercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val header = card.badgeHeader.trim().uppercase().replace(Regex("\\s+"), " ")
        val lines = if (words.size <= 1) words else listOf(words.first(), words.drop(1).joinToString(" "))
        return when {
            header.isNotBlank() && lines.size == 1 -> listOf(
                BadgeLine(header, 110f, 36f, 300f, false),
                BadgeLine(lines.firstOrNull().orEmpty(), 238f, 82f, 310f, true),
            )
            header.isNotBlank() -> listOf(
                BadgeLine(header, 110f, 36f, 300f, false),
                BadgeLine(lines.firstOrNull().orEmpty(), 215f, 94f, 320f, true),
                BadgeLine(lines.drop(1).joinToString(" "), 310f, 38f, 320f, false),
            )
            lines.size == 1 -> listOf(BadgeLine(lines[0], 199f, 98f, 320f, true))
            else -> listOf(
                BadgeLine(lines.firstOrNull().orEmpty(), 168f, 98f, 320f, true),
                BadgeLine(lines.getOrElse(1) { "" }, 243f, 47f, 330f, false),
            )
        }
    }

    fun badgeText(context: Context, line: BadgeLine): Bitmap {
        val bitmap = Bitmap.createBitmap(badgeWidth, badgeHeight, Bitmap.Config.ARGB_8888)
        drawBadgeLine(Canvas(bitmap), context, line.text, line.centreY, line.size, line.maxWidth, line.bold)
        return bitmap
    }

    fun drawBadgeShine(canvas: Canvas, left: Float, top: Float, progress: Float, alpha: Int) {
        val topX = -42f + progress.coerceIn(0f, 1f) * 610f
        val bottomX = topX - 182f
        val face = Path().apply {
            moveTo(left + 243f, top + 33f)
            lineTo(left + 391f, top + 118f)
            lineTo(left + 391f, top + 290f)
            lineTo(left + 245f, top + 374f)
            lineTo(left + 96f, top + 290f)
            lineTo(left + 96f, top + 118f)
            close()
        }
        canvas.save()
        canvas.clipPath(face)
        fun band(halfWidth: Float): Path = Path().apply {
            moveTo(left + topX - halfWidth, top - 60f)
            lineTo(left + topX + halfWidth, top - 60f)
            lineTo(left + bottomX + halfWidth, top + badgeHeight + 60f)
            lineTo(left + bottomX - halfWidth, top + badgeHeight + 60f)
            close()
        }
        canvas.drawPath(band(43f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alpha * 0.20f).toInt(), 255, 255, 255)
        })
        canvas.drawPath(band(5f), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alpha * 0.35f).toInt(), 255, 255, 255)
        })
        canvas.restore()
    }

    fun credits(context: Context, project: StudioProject): Bitmap {
        val bitmap = Bitmap.createBitmap(NativeTimeline.bodyWidth, NativeTimeline.bodyHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(29, 29, 29))
        drawFittedCentredText(
            canvas,
            "The values presented are estimations based on forums, speculative community discussions, and lived experiences. All sources are listed below. Enjoy!",
            RectF(34f, 40f, NativeTimeline.bodyWidth - 34f, 205f), 25f, 17f, 7,
            font(context, "Poppins-Bold.ttf", true), Color.WHITE,
        )
        canvas.drawLine(50f, 232f, NativeTimeline.bodyWidth - 50f, 232f, Paint().apply { color = Color.LTGRAY; strokeWidth = 2f })
        drawCentred(canvas, "DISCLAIMER:", 310f, 44f, font(context, "Poppins-Medium.ttf", false), Color.WHITE)
        drawFittedCentredText(
            canvas,
            "THIS VIDEO IS BASED ON COMMUNITY DISCUSSIONS AND RELEVANT SOURCES. NUMBERS AND FACTS LISTED MAY NOT BE UP TO DATE, VALID OR IN ANY SPECIFIC ORDER. ANY SOURCE MATERIAL IS LINKED IN THE DESCRIPTION.",
            RectF(30f, 365f, NativeTimeline.bodyWidth - 30f, 650f), 17f, 11f, 12,
            font(context, "Poppins-Medium.ttf", false), Color.WHITE,
        )
        drawCentred(canvas, "SURVIVAL TIMES ARE ESTIMATES, NOT MEDICAL ADVICE", 1038f, 12f, font(context, "Poppins-Medium.ttf", false), Color.LTGRAY)
        return bitmap
    }

    fun outroPrompt(context: Context, project: StudioProject): Bitmap {
        val bitmap = Bitmap.createBitmap(1397, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val prompt = project.outroPrompt.ifBlank {
            if (project.name.contains("Worst Things To Hear", ignoreCase = true)) {
                "What’s the worst thing that you’ve ever heard in your life? Comment below!"
            } else {
                "What do you think? Comment below!"
            }
        }
        drawFittedCentredText(
            canvas, prompt, RectF(20f, 29f, 1360f, 235f), 52f, 34f, 3,
            font(context, "Poppins-Bold.ttf", true), Color.WHITE,
        )
        return bitmap
    }

    fun outroActionBar(context: Context, state: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(996, 242, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(RectF(0f, 0f, 996f, 242f), 36f, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(250, 250, 250) })
        val dark = Color.rgb(35, 35, 38)
        val blue = Color.rgb(25, 118, 210)
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (state >= 1) blue else dark; strokeWidth = 11f; style = Paint.Style.STROKE
        }
        canvas.drawPath(Path().apply {
            moveTo(82f, 132f); lineTo(115f, 132f); lineTo(139f, 74f); lineTo(159f, 74f)
            lineTo(159f, 101f); lineTo(193f, 101f); lineTo(193f, 164f); lineTo(115f, 164f); close()
        }, iconPaint)
        val dislike = Paint(iconPaint).apply { color = dark }
        canvas.save(); canvas.scale(1f, -1f, 303f, 121f)
        canvas.drawPath(Path().apply {
            moveTo(245f, 132f); lineTo(278f, 132f); lineTo(302f, 74f); lineTo(322f, 74f)
            lineTo(322f, 101f); lineTo(356f, 101f); lineTo(356f, 164f); lineTo(278f, 164f); close()
        }, dislike); canvas.restore()
        if (state >= 1) canvas.drawRoundRect(RectF(45f, 216f, 224f, 224f), 4f, 4f, Paint().apply { color = blue })
        val subscribed = state >= 2
        canvas.drawRoundRect(RectF(461f, 77f, 801f, 174f), 49f, 49f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (subscribed) Color.rgb(225, 225, 225) else Color.rgb(211, 8, 9)
        })
        drawFittedCentredText(
            canvas, if (subscribed) "SUBSCRIBED" else "SUBSCRIBE", RectF(482f, 87f, 780f, 164f), 31f, 22f, 1,
            font(context, "Poppins-Bold.ttf", true), if (subscribed) dark else Color.WHITE,
        )
        val bell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark; style = Paint.Style.STROKE; strokeWidth = 10f }
        canvas.drawArc(RectF(866f, 76f, 938f, 155f), 190f, 160f, false, bell)
        canvas.drawLine(870f, 139f, 934f, 139f, bell)
        canvas.drawCircle(902f, 167f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark })
        if (state >= 3) {
            canvas.drawArc(RectF(842f, 62f, 962f, 172f), 285f, 52f, false, Paint(bell).apply { color = blue; strokeWidth = 7f })
            canvas.drawArc(RectF(842f, 62f, 962f, 172f), 103f, 52f, false, Paint(bell).apply { color = blue; strokeWidth = 7f })
        }
        return bitmap
    }

    fun cursor(): Bitmap {
        val bitmap = Bitmap.createBitmap(62, 82, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            moveTo(5f, 4f); lineTo(5f, 66f); lineTo(22f, 51f); lineTo(34f, 77f)
            lineTo(48f, 70f); lineTo(36f, 45f); lineTo(59f, 43f); close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 8f })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bitmap
    }

    private fun drawArtwork(context: Context, canvas: Canvas, card: StudioCard, imageTop: Int, imageHeight: Int) {
        if (imageHeight <= 0) return
        val source = loadBitmap(context, card.image)
        if (source == null) return
        canvas.save()
        canvas.clipRect(0f, imageTop.toFloat(), NativeTimeline.bodyWidth.toFloat(), (imageTop + imageHeight).toFloat())
        val cropLeft = (source.width * card.imageCropLeft.coerceIn(0.0, 0.95)).toFloat()
        val cropTop = (source.height * card.imageCropTop.coerceIn(0.0, 0.95)).toFloat()
        val cropRight = (source.width * (1.0 - card.imageCropRight.coerceIn(0.0, 0.95))).toFloat()
        val cropBottom = (source.height * (1.0 - card.imageCropBottom.coerceIn(0.0, 0.95))).toFloat()
        val usableWidth = (cropRight - cropLeft).coerceAtLeast(1f)
        val usableHeight = (cropBottom - cropTop).coerceAtLeast(1f)
        val scale = min(NativeTimeline.bodyWidth / usableWidth, imageHeight / usableHeight) * card.imageScale.coerceIn(0.05, 20.0).toFloat()
        val matrix = Matrix().apply {
            postTranslate(-cropLeft - usableWidth / 2f, -cropTop - usableHeight / 2f)
            postScale(scale, scale)
            postRotate(card.imageRotation.toFloat())
            postTranslate(
                NativeTimeline.bodyWidth / 2f + card.imageX.toFloat(),
                imageTop + imageHeight / 2f + card.imageY.toFloat(),
            )
        }
        canvas.drawBitmap(source, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        source.recycle()
    }

    private fun loadBitmap(context: Context, value: String): Bitmap? {
        if (value.isBlank()) return null
        return runCatching {
            when {
                value.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(value)).use(BitmapFactory::decodeStream)
                value.startsWith("file://") -> BitmapFactory.decodeFile(Uri.parse(value).path)
                else -> BitmapFactory.decodeFile(value)
            }
        }.getOrNull()
    }

    private fun drawBadgeLine(canvas: Canvas, context: Context, text: String, centreY: Float, size: Float, maxWidth: Float, bold: Boolean) {
        if (text.isBlank()) return
        val base = font(context, if (bold) "Poppins-ExtraBold.ttf" else "Poppins-SemiBold.ttf", bold)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = base; textSize = size; color = Color.WHITE; textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 3f, 4f, Color.argb(110, 0, 0, 0))
        }
        while (paint.measureText(text) > maxWidth && paint.textSize > 17f) paint.textSize -= 1f
        val baseline = centreY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, badgeWidth / 2f, baseline, paint)
    }

    private fun drawCentred(canvas: Canvas, text: String, y: Float, size: Float, typeface: Typeface, colour: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface; textSize = size; color = colour; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, NativeTimeline.bodyWidth / 2f, y - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawFittedCentredText(
        canvas: Canvas, text: String, bounds: RectF, maximum: Float, minimum: Float,
        maxLines: Int, typeface: Typeface, colour: Int,
    ) {
        var size = maximum
        var lines: List<String>
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; color = colour; textAlign = Paint.Align.CENTER }
        while (true) {
            paint.textSize = size
            lines = wrap(text, paint, bounds.width(), maxLines)
            val lineHeight = size * 1.12f
            if ((lines.size * lineHeight <= bounds.height() && lines.all { paint.measureText(it) <= bounds.width() }) || size <= minimum) break
            size -= 1f
        }
        val lineHeight = size * 1.12f
        var baseline = bounds.centerY() - lines.size * lineHeight / 2f - paint.ascent()
        for (line in lines) {
            canvas.drawText(line, bounds.centerX(), baseline, paint)
            baseline += lineHeight
        }
    }

    private fun wrap(text: String, paint: Paint, width: Float, maxLines: Int): List<String> {
        val words = text.split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) <= width || line.isBlank()) line = candidate
            else {
                result += line
                line = word
                if (result.size == maxLines - 1) break
            }
        }
        if (line.isNotBlank() && result.size < maxLines) result += line
        return result
    }

    private fun font(context: Context, filename: String, bold: Boolean): Typeface {
        val cached = when (filename) {
            "Poppins-ExtraBold.ttf" -> extraBold
            "Poppins-SemiBold.ttf" -> semiBold
            else -> medium
        }
        if (cached != null) return cached
        val loaded = runCatching { Typeface.createFromAsset(context.assets, "fonts/$filename") }
            .getOrElse { if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        when (filename) {
            "Poppins-ExtraBold.ttf" -> extraBold = loaded
            "Poppins-SemiBold.ttf" -> semiBold = loaded
            else -> medium = loaded
        }
        return loaded
    }
}

object NativeFrameRenderer {
    private const val maxCachedBodies = 12
    private const val maxCachedBadgeLayers = 24
    private val bodies = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > maxCachedBodies
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }
    private val badges = object : LinkedHashMap<String, Bitmap>(36, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val remove = size > maxCachedBadgeLayers
            if (remove) eldest?.value?.recycle()
            return remove
        }
    }

    @Synchronized
    fun trimCaches() {
        bodies.values.forEach { it.recycle() }
        badges.values.forEach { it.recycle() }
        bodies.clear()
        badges.clear()
    }

    @Synchronized
    fun render(context: Context, project: StudioProject, frame: Int, width: Int, height: Int): Bitmap {
        val output = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(output.width / NativeTimeline.referenceWidth, output.height / NativeTimeline.referenceHeight)
        val alpha = NativeTimeline.sceneAlpha(project, frame)
        if (alpha > 0f) {
            val positions = NativeTimeline.positions(project, frame)
            val order = if (NativeTimeline.sceneFrame(project, frame) < NativeTimeline.continuousStart) {
                val active = positions.keys.maxOrNull()
                if (active == null) emptyList() else listOf(active) + positions.keys.filter { it != active }.sorted()
            } else positions.keys.sorted()
            val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = (alpha * 255).toInt() }
            for (index in order) {
                val card = project.cards[index]
                val key = bodyKey(card)
                val body = bodies.getOrPut(key) { NativeArtwork.body(context, card) }
                canvas.drawBitmap(body, positions.getValue(index) + NativeTimeline.bodyInset, 0f, layerPaint)
            }
            NativeTimeline.creditsX(project, frame)?.let { x ->
                val credits = NativeArtwork.credits(context, project)
                canvas.drawBitmap(credits, x + NativeTimeline.bodyInset, 0f, layerPaint)
                credits.recycle()
            }
            val starts = NativeTimeline.cardStarts(project)
            for (index in positions.keys.sorted()) {
                val card = project.cards[index]
                if (!project.showBadges || card.value.isBlank()) continue
                val localFrame = NativeTimeline.sceneFrame(project, frame) - starts[index]
                val offset = NativeTimeline.badgeOffset(index, localFrame, project.settledScrollingBadges) ?: continue
                val badge = badges.getOrPut("badge-shell") { NativeArtwork.badgeShell() }
                val left = positions.getValue(index) + (NativeTimeline.slotPitch - NativeArtwork.badgeWidth) / 2f
                val top = NativeArtwork.badgeTop + offset
                val matrix = badgeMatrix(left, top, NativeTimeline.badgeAffine(index, localFrame), NativeTimeline.badgeScale(index, localFrame, frame))
                canvas.save()
                canvas.concat(matrix)
                canvas.drawBitmap(badge, 0f, 0f, layerPaint)
                NativeTimeline.badgeShineProgress(index, localFrame)?.let {
                    NativeArtwork.drawBadgeShine(canvas, 0f, 0f, it, layerPaint.alpha)
                }
                NativeArtwork.badgeLines(card).forEachIndexed { lineIndex, line ->
                    val progress = NativeTimeline.badgeTextProgress(
                        index, lineIndex, localFrame, project.settledScrollingBadges,
                    )
                    if (progress <= 0f) return@forEachIndexed
                    val lineBitmap = badges.getOrPut("badge-text:${badgeKey(card)}:$lineIndex") {
                        NativeArtwork.badgeText(context, line)
                    }
                    canvas.drawBitmap(lineBitmap, 0f, 0f, layerPaint)
                }
                canvas.restore()
            }
            for (index in positions.keys.sorted()) {
                val front = NativeArtwork.frontArtwork(context, project.cards[index]) ?: continue
                canvas.drawBitmap(front, positions.getValue(index) + NativeTimeline.bodyInset, 0f, layerPaint)
                front.recycle()
            }
            drawOutro(context, canvas, project, frame, layerPaint)
        }
        canvas.restore()
        return output
    }

    private fun bodyKey(card: StudioCard): String = "${card.id}:${card.hashCode()}"
    private fun badgeKey(card: StudioCard): String = "${card.id}:${card.badgeHeader}:${card.value}"

    private fun badgeMatrix(left: Float, top: Float, affine: NativeTimeline.Affine, scale: Float): Matrix {
        val centreX = 243.5f
        val centreY = 203.5f
        return Matrix().apply {
            setValues(floatArrayOf(
                scale * affine.a, scale * affine.b, left + centreX + scale * (affine.e - centreX),
                scale * affine.c, scale * affine.d, top + centreY + scale * (affine.f - centreY),
                0f, 0f, 1f,
            ))
        }
    }

    private fun drawOutro(context: Context, canvas: Canvas, project: StudioProject, frame: Int, layerPaint: Paint) {
        val local = NativeTimeline.outroLocal(project, frame)
        if (local < 0) return
        canvas.drawRect(0f, 0f, NativeTimeline.referenceWidth, NativeTimeline.referenceHeight, Paint().apply { color = Color.BLACK })
        if (project.cards.isNotEmpty()) {
            val groupX = NativeTimeline.outroGroupX(local)
            val card = project.cards.last()
            val body = bodies.getOrPut(bodyKey(card)) { NativeArtwork.body(context, card) }
            canvas.drawBitmap(body, groupX + NativeTimeline.bodyInset, 0f, layerPaint)
            if (project.showBadges && card.value.isNotBlank()) {
                val badge = badges.getOrPut("badge-shell") { NativeArtwork.badgeShell() }
                val left = groupX + (NativeTimeline.slotPitch - NativeArtwork.badgeWidth) / 2f
                canvas.save()
                canvas.concat(badgeMatrix(left, NativeArtwork.badgeTop, NativeTimeline.Affine(1f, 0f, 0f, 1f, 0f, 0f), 1.25f))
                canvas.drawBitmap(badge, 0f, 0f, layerPaint)
                NativeArtwork.badgeLines(card).forEachIndexed { lineIndex, line ->
                    val text = badges.getOrPut("badge-text:${badgeKey(card)}:$lineIndex") { NativeArtwork.badgeText(context, line) }
                    canvas.drawBitmap(text, 0f, 0f, layerPaint)
                }
                canvas.restore()
            }
            val prompt = badges.getOrPut("outro-prompt:${project.name}:${project.outroPrompt}") { NativeArtwork.outroPrompt(context, project) }
            canvas.drawBitmap(prompt, groupX + 523f, 0f, layerPaint)
        }
        NativeTimeline.outroActionBar(local)?.let { bounds ->
            val state = NativeTimeline.outroActionState(local)
            val action = badges.getOrPut("outro-action:$state") { NativeArtwork.outroActionBar(context, state) }
            canvas.drawBitmap(
                action, null, RectF(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height), layerPaint,
            )
        }
        NativeTimeline.outroCursor(local)?.let { point ->
            val cursor = badges.getOrPut("outro-cursor") { NativeArtwork.cursor() }
            canvas.drawBitmap(cursor, point.x, point.y, layerPaint)
        }
    }
}
