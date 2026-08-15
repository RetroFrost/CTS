package io.github.retrofrost.cts.android.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.render.ReferenceBadgePainter
import io.github.retrofrost.cts.android.timeline.CardPlacement

/** Draws one model-owned card body and badge from measured 480 x 1080 geometry. */
internal class ReferenceCardPainter(
    private val project: CtsProject,
    private val frameHeight: Int,
    private val images: BitmapSourceCache,
    private val paint: Paint,
    private val text: TextBlockPainter,
) {
    fun drawBody(canvas: Canvas, card: CtsCard, cardWidth: Float, placement: CardPlacement) {
        val displayCard = card.withNormalizedText()
        val frames = CardContentLayout.frames(project.model, displayCard)
        val image = frameRect(frames.image, cardWidth)
        val title = frames.title?.let { frameRect(it, cardWidth) }
        val description = frames.description?.let { frameRect(it, cardWidth) }

        images.load(displayCard.imageSubcard.backgroundSource)?.let { background ->
            BitmapPainter.drawCenterCrop(
                canvas = canvas,
                bitmap = background,
                destination = RectF(0f, 0f, cardWidth, frameHeight.toFloat()),
                paint = paint,
            )
        } ?: drawArtworkFallback(canvas, image)

        images.load(displayCard.imageSubcard.source)?.let { bitmap ->
            val transform = displayCard.imageSubcard.transform.clamped()
            val destination = RectF(
                image.left + image.width() * transform.x,
                image.top + image.height() * transform.y,
                image.left + image.width() * (transform.x + transform.width),
                image.top + image.height() * (transform.y + transform.height),
            )
            canvas.save()
            canvas.clipRect(image)
            BitmapPainter.drawCenterCrop(
                canvas = canvas,
                bitmap = bitmap,
                destination = destination,
                focusX = displayCard.imageSubcard.cropFocusX,
                focusY = displayCard.imageSubcard.cropFocusY,
                zoom = displayCard.imageSubcard.cropZoom,
                paint = paint,
            )
            canvas.restore()
        }

        title?.let { drawTitle(canvas, displayCard.title, it, placement) }
        description?.let { drawDescription(canvas, displayCard.description, it, placement) }

        paint.shader = null
        paint.color = Color.rgb(17, 16, 12)
        canvas.drawRect(frameRect(CardContentLayout.bottomRule(), cardWidth), paint)

    }

    fun drawBadge(
        canvas: Canvas,
        card: CtsCard,
        cardX: Float,
        cardWidth: Float,
        placement: CardPlacement,
    ) {
        ReferenceBadgePainter.draw(
            canvas = canvas,
            card = card.withNormalizedText(),
            model = project.model,
            placement = placement,
            cardLeft = cardX,
            cardWidth = cardWidth,
            frameHeight = frameHeight.toFloat(),
        )
    }

    private fun drawArtworkFallback(canvas: Canvas, image: RectF) {
        val topColor = Color.rgb(19, 141, 219)
        val bottomColor = Color.rgb(11, 116, 190)
        paint.shader = LinearGradient(
            image.left,
            image.top,
            image.left,
            image.bottom,
            intArrayOf(topColor, topColor, bottomColor),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(image, paint)
        paint.shader = null
    }

    private fun drawTitle(canvas: Canvas, value: String, rect: RectF, placement: CardPlacement) {
        // The attached light title strip is model-owned and always opaque.
        paint.shader = null
        paint.color = Color.rgb(242, 242, 242)
        canvas.drawRect(rect, paint)
        val padding = rect.width() * 0.035f
        text.draw(
            canvas = canvas,
            text = value,
            rect = RectF(rect.left + padding, rect.top + 2f, rect.right - padding, rect.bottom - 2f),
            color = Color.BLACK,
            bold = true,
            maximumSize = frameHeight * 0.043f,
            minimumSize = frameHeight * 0.018f,
            maxLines = 2,
        )
    }

    private fun drawDescription(canvas: Canvas, value: String, rect: RectF, placement: CardPlacement) {
        paint.shader = null
        paint.color = Color.rgb(99, 94, 87)
        canvas.drawRect(rect, paint)
        val padding = rect.width() * 0.035f
        text.draw(
            canvas = canvas,
            text = value,
            rect = RectF(rect.left + padding, rect.top + 2f, rect.right - padding, rect.bottom - 2f),
            color = Color.WHITE,
            bold = true,
            maximumSize = frameHeight * 0.027f,
            minimumSize = frameHeight * 0.014f,
            maxLines = 3,
        )
    }

    private fun frameRect(rect: NormalizedRect, cardWidth: Float): RectF = RectF(
        cardWidth * rect.x,
        frameHeight * rect.y,
        cardWidth * (rect.x + rect.width),
        frameHeight * (rect.y + rect.height),
    )
}
