package dev.thedataguys.cc

import android.graphics.*
import kotlin.math.*

class ScenePainter {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(252, 244, 238) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 28, 26)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(53, 44, 40)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 74, 66)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(218, 211, 9, 9) }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(82, 177, 5, 7)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 0, 0, 0) }

    fun drawVideoFrame(canvas: Canvas, project: CompareProject, frameIndex: Int) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val t = frameIndex / project.fps.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        drawHeader(canvas, project, w)

        val scroll = easeInOut(((t - 1.1f) / max(1f, project.seconds - 3f)).coerceIn(0f, 1f))
        val top = 360f - scroll * max(0f, project.items.size * 315f - 1120f)
        project.items.forEachIndexed { index, item ->
            val y = top + index * 315f
            if (y > -340f && y < h + 80f) {
                val entry = openingEntry(index, frameIndex)
                drawCard(canvas, item, 88f + entry.first, y + entry.second, w - 176f, index)
            }
        }
    }

    fun drawThumbnail(canvas: Canvas, project: CompareProject) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        canvas.drawRect(0f, 0f, w, h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 13, 12) })

        titlePaint.textSize = 88f
        titlePaint.color = Color.WHITE
        canvas.drawText(project.title.uppercase(), w / 2f, 170f, titlePaint)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(72, 211, 9, 9) }
        canvas.drawCircle(w * 0.78f, h * 0.32f, 250f, glow)
        canvas.drawCircle(w * 0.20f, h * 0.72f, 210f, glow)

        val big = project.items.getOrNull(0) ?: return
        drawCard(canvas, big.copy(title = "#1 ${big.title}", value = "???"), 76f, 420f, w - 152f, 0, forceHuge = true)

        bodyPaint.color = Color.WHITE
        bodyPaint.textSize = 58f
        canvas.drawText("THE LAST ONE IS WORSE", w / 2f, h - 290f, bodyPaint)

        val pill = RectF(115f, h - 235f, w - 115f, h - 135f)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 9, 9) }
        canvas.drawRoundRect(pill, 42f, 42f, pillPaint)
        bodyPaint.textSize = 45f
        canvas.drawText("WATCH BEFORE YOU EXPORT", w / 2f, h - 171f, bodyPaint)
    }

    private fun drawHeader(canvas: Canvas, project: CompareProject, w: Float) {
        titlePaint.color = Color.rgb(35, 28, 26)
        titlePaint.textSize = 58f
        canvas.drawText(project.title, w / 2f, 125f, titlePaint)
        smallPaint.textSize = 31f
        canvas.drawText(project.subtitle, w / 2f, 176f, smallPaint)
    }

    private fun drawCard(canvas: Canvas, item: CompareItem, x: Float, y: Float, width: Float, index: Int, forceHuge: Boolean = false) {
        val height = if (forceHuge) 500f else 255f
        val r = RectF(x + 10f, y + 12f, x + width + 10f, y + height + 12f)
        canvas.drawRoundRect(r, 44f, 44f, shadowPaint)
        val card = RectF(x, y, x + width, y + height)
        canvas.drawRoundRect(card, 44f, 44f, cardPaint)

        val badgeCx = x + width - 150f
        val badgeCy = y + 86f
        drawHexBadge(canvas, badgeCx, badgeCy, if (forceHuge) 103f else 78f)

        bodyPaint.color = Color.rgb(33, 28, 26)
        bodyPaint.textSize = if (forceHuge) 62f else 39f
        bodyPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(item.title.take(30), x + 46f, y + if (forceHuge) 126f else 75f, bodyPaint)

        smallPaint.textSize = if (forceHuge) 38f else 27f
        smallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(item.subtitle.take(36), x + 46f, y + if (forceHuge) 193f else 120f, smallPaint)

        bodyPaint.textAlign = Paint.Align.CENTER
        bodyPaint.color = Color.WHITE
        bodyPaint.textSize = if (forceHuge) 36f else 28f
        canvas.drawText(item.value.take(10), badgeCx, badgeCy + 10f, bodyPaint)

        smallPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawHexBadge(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val path = Path()
        for (i in 0 until 6) {
            val a = Math.toRadians((60.0 * i) - 30.0)
            val x = cx + cos(a).toFloat() * radius
            val y = cy + sin(a).toFloat() * radius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, badgePaint)
        canvas.drawPath(path, badgeStroke)
    }

    private fun openingEntry(index: Int, frame: Int): Pair<Float, Float> {
        if (frame > 160) return 0f to 0f
        val p = easeOut((frame / 160f).coerceIn(0f, 1f))
        val y = (1f - p) * -70f
        val x = if (index == 3) (1f - p) * 430f else 0f
        return x to y
    }

    private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x)
    private fun easeInOut(x: Float): Float = (x * x * (3f - 2f * x))
}
