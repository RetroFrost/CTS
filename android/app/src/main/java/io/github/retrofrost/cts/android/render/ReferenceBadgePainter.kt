package io.github.retrofrost.cts.android.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.CardPlacement
import kotlin.math.max

/** One badge implementation shared by the Compose preview and bitmap exporter. */
object ReferenceBadgePainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    @Synchronized
    fun draw(
        canvas: Canvas,
        card: CtsCard,
        model: VisualModel,
        placement: CardPlacement,
        cardLeft: Float,
        cardWidth: Float,
        frameHeight: Float,
    ) {
        if (model == VisualModel.Relationships) {
            drawRelationships(canvas, card, placement, cardLeft, cardWidth, frameHeight)
        } else {
            drawMales(canvas, card, placement, cardLeft, cardWidth, frameHeight)
        }
    }

    private fun drawMales(
        canvas: Canvas,
        card: CtsCard,
        placement: CardPlacement,
        cardLeft: Float,
        cardWidth: Float,
        frameHeight: Float,
    ) {
        val sx = cardWidth / 480f
        val sy = frameHeight / 1080f
        val motion = placement.badgeAffine
        canvas.save()
        canvas.translate(cardLeft, 0f)
        canvas.scale(sx, sy)
        canvas.concat(Matrix().apply {
            setValues(floatArrayOf(
                motion.m00, motion.m01, motion.tx,
                motion.m10, motion.m11, motion.ty,
                0f, 0f, 1f,
            ))
        })

        val path = Path().apply {
            moveTo(240f, 32f)
            lineTo(393f, 116f)
            lineTo(393f, 289f)
            lineTo(247f, 375f)
            lineTo(96f, 289f)
            lineTo(96f, 118f)
            close()
        }
        fill.shader = LinearGradient(
            0f, 32f, 0f, 375f,
            intArrayOf(Color.rgb(235, 9, 9), Color.rgb(224, 0, 0), Color.rgb(213, 0, 0)),
            null, Shader.TileMode.CLAMP,
        )
        fill.setShadowLayer(8f, 5f, 8f, Color.argb(150, 0, 0, 0))
        canvas.drawPath(path, fill)
        fill.clearShadowLayer()
        fill.shader = null
        stroke.color = Color.rgb(185, 0, 8)
        stroke.strokeWidth = 2f
        canvas.drawPath(path, stroke)

        val age = placement.badgeAgeSeconds
        val alpha = ((age - 0.90f) / 0.42f).coerceIn(0f, 1f)
        text.alpha = (255 * alpha).toInt()
        text.textSize = if (card.badgeSecondary.isBlank()) 72f else 64f
        canvas.drawText(card.badgePrimary, 243.5f, if (card.badgeSecondary.isBlank()) 220f else 185f, text)
        if (card.badgeSecondary.isNotBlank()) {
            text.textSize = 34f
            val lines = splitLabel(card.badgeSecondary)
            val firstY = if (lines.size == 1) 255f else 245f
            lines.take(2).forEachIndexed { index, line ->
                canvas.drawText(line.uppercase(), 243.5f, firstY + index * 38f, text)
            }
        }
        text.alpha = 255
        canvas.restore()
    }

    private fun drawRelationships(
        canvas: Canvas,
        card: CtsCard,
        placement: CardPlacement,
        cardLeft: Float,
        cardWidth: Float,
        frameHeight: Float,
    ) {
        val normalized = placement.badgeRect ?: return
        val badge = RectF(
            cardLeft + cardWidth * normalized.x,
            frameHeight * normalized.y,
            cardLeft + cardWidth * (normalized.x + normalized.width),
            frameHeight * (normalized.y + normalized.height),
        )
        val cutX = badge.width() * 0.18f
        val cutY = badge.height() * 0.18f
        val path = Path().apply {
            moveTo(badge.left + cutX, badge.top)
            lineTo(badge.right - cutX, badge.top)
            lineTo(badge.right, badge.top + cutY)
            lineTo(badge.right, badge.bottom - cutY)
            lineTo(badge.right - cutX, badge.bottom)
            lineTo(badge.left + cutX, badge.bottom)
            lineTo(badge.left, badge.bottom - cutY)
            lineTo(badge.left, badge.top + cutY)
            close()
        }
        fill.shader = null
        fill.color = Color.rgb(224, 17, 27)
        canvas.drawPath(path, fill)
        stroke.color = Color.rgb(239, 194, 72)
        stroke.strokeWidth = max(1f, badge.width() * 0.006f)
        canvas.drawPath(path, stroke)

        text.alpha = (255 * placement.badgeTextAlpha.coerceIn(0f, 1f)).toInt()
        text.textSize = badge.width() * 0.13f
        canvas.drawText("1 in", badge.centerX(), badge.top + badge.height() * 0.27f, text)
        text.textSize = badge.width() * 0.30f
        canvas.drawText(relationshipNumber(card.badgePrimary), badge.centerX(), badge.top + badge.height() * 0.62f, text)
        text.textSize = badge.width() * 0.12f
        canvas.drawText("People", badge.centerX(), badge.top + badge.height() * 0.86f, text)
        text.alpha = 255
    }

    private fun splitLabel(value: String): List<String> {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 2) return words
        val split = words.indices.drop(1).minByOrNull { index ->
            kotlin.math.abs(words.take(index).joinToString(" ").length - words.drop(index).joinToString(" ").length)
        } ?: 1
        return listOf(words.take(split).joinToString(" "), words.drop(split).joinToString(" "))
    }

    private fun relationshipNumber(value: String): String {
        val words = value.replace('\n', ' ').split(' ').filter { it.isNotBlank() }
        val inIndex = words.indexOfFirst { it.equals("in", ignoreCase = true) }
        return words.getOrNull(inIndex + 1)
            ?: words.firstOrNull { word -> word.any { it.isDigit() } }
            ?: value.ifBlank { "?" }
    }
}
