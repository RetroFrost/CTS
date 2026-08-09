package io.github.retrofrost.cts.android.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

internal object ReferenceOverlayRenderer {
    fun drawRelationshipsPrelude(
        canvas: Canvas,
        width: Int,
        height: Int,
        sourceFrame: Int,
        disclaimerAlpha: Float,
        showIntro: Boolean,
        paint: Paint,
    ) {
        if (showIntro && sourceFrame in 1 until 550) {
            val shapeFrame = sourceFrame.coerceAtMost(373)
            val opacity = when {
                sourceFrame < 34 -> 0f
                sourceFrame < 70 -> ((sourceFrame - 34) / 36f).coerceIn(0f, 1f)
                sourceFrame < 450 -> 1f
                else -> 1f - smoothStep((sourceFrame - 450) / 100f)
            }
            paint.style = Paint.Style.FILL
            if (sourceFrame < 374) {
                paint.color = Color.rgb(9, 9, 9)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            val scale = min(width / 1920f, height / 1080f)
            fun drawLoop(state: Triple<Float, Float, Float>, opening: Float, color: Int) {
                val (cx, cy, radius) = state
                val rect = RectF(
                    cx * width / 1920f - radius * scale,
                    cy * height / 1080f - radius * scale,
                    cx * width / 1920f + radius * scale,
                    cy * height / 1080f + radius * scale,
                )
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (16f + 0.055f * radius) * scale
                paint.color = Color.argb((255 * opacity).toInt(), 244, 242, 227)
                canvas.drawArc(rect, opening + 46f, 268f, false, paint)
                paint.strokeWidth = (5f + 0.014f * radius) * scale
                paint.color = Color.argb((255 * opacity).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawArc(rect, opening + 46f, 268f, false, paint)
                paint.style = Paint.Style.FILL
            }
            val opening = sampleOpening(shapeFrame)
            drawLoop(loopState(shapeFrame, true), opening, Color.rgb(198, 233, 0))
            drawLoop(loopState(shapeFrame, false), opening + 180f, Color.rgb(238, 91, 127))
            if (sourceFrame >= 240) {
                val first = "Infinite".take((((shapeFrame - 240) / 50f).coerceIn(0f, 1f) * 8).toInt())
                val second = "Comparison".take((((shapeFrame - 288) / 62f).coerceIn(0f, 1f) * 10).toInt())
                paint.color = Color.argb((255 * opacity).toInt(), 245, 245, 245)
                drawCentered(canvas, first, 0f, width.toFloat(), height * 0.69f, height * 0.057f, paint)
                drawCentered(canvas, second, 0f, width.toFloat(), height * 0.75f, height * 0.057f, paint)
            }
        }
        if (disclaimerAlpha > 0f) {
            val alpha = (255 * disclaimerAlpha.coerceIn(0f, 1f)).toInt()
            val left = width * 0.75f
            paint.color = Color.argb((170 * disclaimerAlpha).toInt(), 18, 18, 18)
            canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.argb(alpha, 224, 17, 27)
            drawCentered(canvas, "DISCLAIMER:", left, width * 0.25f, height * 0.30f, height * 0.022f, paint, true)
            paint.color = Color.argb((225 * disclaimerAlpha).toInt(), 210, 210, 210)
            drawCentered(canvas, "Based on public data, surveys and discussions.", left, width * 0.25f, height * 0.36f, height * 0.015f, paint)
            drawCentered(canvas, "Values are approximate and may vary.", left, width * 0.25f, height * 0.40f, height * 0.015f, paint)
        }
    }

    fun drawRelationshipsOutro(
        canvas: Canvas,
        width: Int,
        height: Int,
        localFrame: Int,
        contentAlpha: Float,
        paint: Paint,
    ) {
        if (localFrame < 0) return
        val overlayRight = width * 0.75f
        if (contentAlpha <= 0f) return
        val alpha = (255 * contentAlpha.coerceIn(0f, 1f)).toInt()
        paint.color = Color.argb(alpha, 9, 9, 9)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)
        val question = "Which relationship type are you in right now?"
        val q = question.take((((localFrame - 42) / 80f).coerceIn(0f, 1f) * question.length).toInt())
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, q, 0f, overlayRight * 0.55f, height * 0.24f, height * 0.052f, paint)
        val comment = "Comment below!"
        val c = comment.take((((localFrame - 142) / 40f).coerceIn(0f, 1f) * comment.length).toInt())
        paint.color = Color.argb(alpha, 234, 127, 28)
        drawCentered(canvas, c, 0f, overlayRight * 0.55f, height * 0.34f, height * 0.058f, paint)
        val subscribe = "SUBSCRIBE for more comparison videos."
        val s = subscribe.take((((localFrame - 206) / 94f).coerceIn(0f, 1f) * subscribe.length).toInt())
        paint.color = Color.argb(alpha, 244, 244, 244)
        drawCentered(canvas, s, 0f, overlayRight * 0.62f, height * 0.68f, height * 0.066f, paint)
        if (localFrame >= 35) {
            paint.color = Color.argb(alpha, 31, 31, 31)
            canvas.drawRoundRect(
                RectF(width * 1314f / 1920f, height * 79f / 1080f, width * 1866f / 1920f, height * 971f / 1080f),
                18f,
                18f,
                paint,
            )
            paint.color = Color.argb(alpha, 244, 244, 244)
            drawCentered(canvas, "WATCH MORE", width * 1314f / 1920f, width * 552f / 1920f, height * 0.11f, height * 0.040f, paint)
        }
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun loopState(frame: Int, yellow: Boolean): Triple<Float, Float, Float> {
        val keys = if (yellow) {
            arrayOf(
                floatArrayOf(34f, 987.6f, -142.2f, 478.8f), floatArrayOf(50f, 778.3f, 71.9f, 357.9f),
                floatArrayOf(70f, 677.3f, 286f, 276.1f), floatArrayOf(100f, 678.5f, 478.9f, 207.7f),
                floatArrayOf(120f, 708.9f, 523.2f, 181.4f), floatArrayOf(150f, 740.5f, 526.4f, 158.5f),
                floatArrayOf(180f, 752.2f, 527.3f, 150f), floatArrayOf(373f, 752.6f, 527.5f, 149.7f),
            )
        } else {
            arrayOf(
                floatArrayOf(34f, 997.6f, 1169f, 475.5f), floatArrayOf(50f, 1178.4f, 959.6f, 360.9f),
                floatArrayOf(70f, 1253.4f, 744.6f, 278.6f), floatArrayOf(100f, 1227.2f, 561.2f, 209.5f),
                floatArrayOf(120f, 1192.7f, 523.8f, 182.8f), floatArrayOf(150f, 1163.2f, 525f, 159.7f),
                floatArrayOf(180f, 1152.3f, 525.8f, 151.2f), floatArrayOf(373f, 1152.1f, 525.8f, 151.1f),
            )
        }
        if (frame <= keys.first()[0]) return Triple(keys.first()[1], keys.first()[2], keys.first()[3])
        if (frame >= keys.last()[0]) return Triple(keys.last()[1], keys.last()[2], keys.last()[3])
        val right = keys.indexOfFirst { frame <= it[0] }
        val a = keys[right - 1]
        val b = keys[right]
        val t = (frame - a[0]) / (b[0] - a[0])
        return Triple(a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t)
    }

    private fun sampleOpening(frame: Int): Float {
        val keys = arrayOf(34f to 90f, 50f to 65f, 70f to 37.5f, 100f to 7.5f, 120f to -0.5f, 373f to -1f)
        if (frame <= keys.first().first) return keys.first().second
        if (frame >= keys.last().first) return keys.last().second
        val right = keys.indexOfFirst { frame <= it.first }
        val (f0, v0) = keys[right - 1]
        val (f1, v1) = keys[right]
        return v0 + (v1 - v0) * (frame - f0) / (f1 - f0)
    }

    fun drawIntroCredits(canvas: Canvas, width: Int, height: Int, paint: Paint) {
        val cardWidth = width / 4f
        val left = width - cardWidth
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(32, 32, 32)
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "The values presented are average milestones", left, cardWidth, height * 0.06f, height * 0.018f, paint)
        paint.color = Color.rgb(190, 190, 190)
        canvas.drawRect(left + cardWidth * 0.12f, height * 0.20f, width - cardWidth * 0.12f, height * 0.202f, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "Credits", left, cardWidth, height * 0.30f, height * 0.042f, paint, true)
        val lines = listOf(
            "Lead Research & Sourcing", "Independent Fact Check", "Lead Graphic Designer",
            "Edit & Post-Production", "Thumbnail Designer", "Video Idea & Quality Check",
        )
        lines.forEachIndexed { index, line ->
            drawCentered(canvas, line, left, cardWidth, height * (0.39f + index * 0.075f), height * 0.018f, paint)
        }
        paint.color = Color.rgb(200, 200, 200)
        drawCentered(canvas, "DISCLAIMER · COMMUNITY DISCUSSIONS AND SOURCES", left, cardWidth, height * 0.93f, height * 0.011f, paint)
    }

    fun drawOutro(
        canvas: Canvas,
        width: Int,
        height: Int,
        coverProgress: Float,
        contentAlpha: Float,
        paint: Paint,
    ) {
        val overlayRight = width * 0.75f
        if (coverProgress > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(17, 17, 17)
            canvas.drawRect(0f, 0f, overlayRight, height * coverProgress.coerceIn(0f, 1f), paint)
        }
        if (contentAlpha <= 0f) return
        val alpha = (255f * contentAlpha.coerceIn(0f, 1f)).toInt()
        paint.color = Color.argb(alpha, 17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val margin = width * 0.02f
        val gap = width * 0.025f
        val boxTop = height * 0.17f
        val boxBottom = height * 0.53f
        val boxWidth = (overlayRight - margin * 2 - gap) / 2f
        paint.color = Color.argb(alpha, 224, 0, 0)
        canvas.drawRoundRect(RectF(margin, boxTop, margin + boxWidth, boxBottom), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(margin + boxWidth + gap, boxTop, overlayRight - margin, boxBottom), 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "BEST VIDEO FOR YOU", margin, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", margin + boxWidth + gap, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)

        val credits = RectF(overlayRight * 0.32f, height * 0.62f, overlayRight * 0.68f, height * 0.84f)
        paint.color = Color.argb(alpha, 98, 95, 86)
        canvas.drawRoundRect(credits, 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "Video Made By", credits.left, credits.width(), credits.top + credits.height() * 0.22f, height * 0.026f, paint, true)
        drawCentered(canvas, "Research · Editing · Design · Quality Check", credits.left, credits.width(), credits.top + credits.height() * 0.58f, height * 0.014f, paint)
    }

    private fun drawCentered(
        canvas: Canvas,
        text: String,
        left: Float,
        width: Float,
        baseline: Float,
        size: Float,
        paint: Paint,
        bold: Boolean = false,
    ) {
        paint.textSize = max(8f, size)
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, left + width / 2f, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
