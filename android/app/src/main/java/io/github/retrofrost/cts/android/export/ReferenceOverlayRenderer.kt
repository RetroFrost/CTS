package io.github.retrofrost.cts.android.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.retrofrost.cts.android.model.CreditsSettings
import io.github.retrofrost.cts.android.timeline.ExactReferenceFrames
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
            fun drawLoop(state: ExactReferenceFrames.LoopState?, color: Int) {
                state ?: return
                val cx = state.centerXPx
                val cy = state.centerYPx
                val radius = state.radiusPx
                val rect = RectF(
                    cx * width / 1920f - radius * scale,
                    cy * height / 1080f - radius * scale,
                    cx * width / 1920f + radius * scale,
                    cy * height / 1080f + radius * scale,
                )
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = (16f + 0.055f * radius) * scale
                val alpha = opacity * state.alpha
                paint.color = Color.argb((255 * alpha).toInt(), 244, 242, 227)
                canvas.drawArc(rect, state.startDegrees, state.sweepDegrees, false, paint)
                paint.strokeWidth = (5f + 0.014f * radius) * scale
                paint.color = Color.argb((255 * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawArc(rect, state.startDegrees, state.sweepDegrees, false, paint)
                paint.style = Paint.Style.FILL
            }
            drawLoop(ExactReferenceFrames.relationshipLoop(shapeFrame, lime = true), Color.rgb(198, 233, 0))
            drawLoop(ExactReferenceFrames.relationshipLoop(shapeFrame, lime = false), Color.rgb(238, 91, 127))
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
        credits: CreditsSettings,
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
            paint.color = Color.argb(alpha, 220, 220, 220)
            drawCentered(
                canvas,
                credits.endingHeading,
                width * 1314f / 1920f,
                width * 552f / 1920f,
                height * 0.86f,
                height * 0.020f,
                paint,
                true,
            )
            drawCentered(
                canvas,
                credits.endingDetails,
                width * 1314f / 1920f,
                width * 552f / 1920f,
                height * 0.91f,
                height * 0.014f,
                paint,
            )
        }
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun drawIntroCredits(
        canvas: Canvas,
        width: Int,
        height: Int,
        credits: CreditsSettings,
        paint: Paint,
    ) {
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
        drawCentered(canvas, credits.heading, left, cardWidth, height * 0.30f, height * 0.042f, paint, true)
        val lines = credits.lines.lineSequence().filter(String::isNotBlank).take(7).toList()
        lines.forEachIndexed { index, line ->
            drawCentered(canvas, line, left, cardWidth, height * (0.39f + index * 0.075f), height * 0.018f, paint)
        }
        paint.color = Color.rgb(200, 200, 200)
        drawCentered(canvas, credits.footer, left, cardWidth, height * 0.93f, height * 0.011f, paint)
    }

    fun drawOutro(
        canvas: Canvas,
        width: Int,
        height: Int,
        coverProgress: Float,
        contentAlpha: Float,
        creditsSettings: CreditsSettings,
        paint: Paint,
    ) {
        val overlayRight = width * 0.75f
        if (coverProgress > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(17, 17, 17)
            canvas.drawRect(0f, 0f, overlayRight, height * coverProgress.coerceIn(0f, 1f), paint)
        }
        if (contentAlpha <= 0f) return
        val rise = 1f - (1f - contentAlpha.coerceIn(0f, 1f)).let { it * it * it }
        val yOffset = height * (1f - rise)
        val alpha = 255
        paint.color = Color.rgb(17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val scaleX = width / 1920f
        val scaleY = height / 1080f
        val margin = 25f * scaleX
        val gap = 42f * scaleX
        val boxTop = 245f * scaleY + yOffset
        val boxBottom = 665f * scaleY + yOffset
        val boxWidth = (overlayRight - margin * 2f - gap) / 2f
        paint.color = Color.argb(alpha, 216, 0, 22)
        canvas.drawRoundRect(RectF(margin, boxTop, margin + boxWidth, boxBottom), 12f * scaleX, 12f * scaleY, paint)
        canvas.drawRoundRect(RectF(margin + boxWidth + gap, boxTop, overlayRight - margin, boxBottom), 12f * scaleX, 12f * scaleY, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "BEST VIDEO FOR YOU", margin, boxWidth, boxTop + 35f * scaleY, 35f * scaleY, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", margin + boxWidth + gap, boxWidth, boxTop + 35f * scaleY, 35f * scaleY, paint, true)

        val creditWidth = 460f * scaleX
        val creditHeight = 150f * scaleY
        val creditLeft = (overlayRight - creditWidth) / 2f
        val creditTop = 790f * scaleY + yOffset
        val credits = RectF(creditLeft, creditTop, creditLeft + creditWidth, creditTop + creditHeight)
        paint.color = Color.rgb(96, 93, 84)
        canvas.drawRoundRect(credits, 20f * scaleX, 20f * scaleY, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, creditsSettings.endingHeading, credits.left, credits.width(), credits.top + credits.height() * 0.22f, height * 0.026f, paint, true)
        drawCentered(canvas, creditsSettings.endingDetails, credits.left, credits.width(), credits.top + credits.height() * 0.58f, height * 0.014f, paint)
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
