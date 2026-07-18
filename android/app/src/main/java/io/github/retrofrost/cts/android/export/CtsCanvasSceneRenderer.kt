package io.github.retrofrost.cts.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL
import kotlin.math.max
import kotlin.math.min

/**
 * Raster scene renderer used by the native MP4 exporter.
 *
 * Geometry, reveal timing, card scrolling and image-subcard transforms mirror the
 * production Program Monitor. Editor guides are deliberately absent from exported frames.
 */
class CtsCanvasSceneRenderer(
    private val context: Context,
    private val project: CtsProject,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val images = mutableMapOf<String, Bitmap?>()

    suspend fun preloadImages() = withContext(Dispatchers.IO) {
        project.cards.forEach { card ->
            val source = card.imageSubcard.source ?: return@forEach
            if (!images.containsKey(source)) {
                images[source] = loadBitmap(source)
            }
        }
    }

    fun drawFrame(canvas: Canvas, width: Int, height: Int, timeSeconds: Float) {
        canvas.drawColor(Color.BLACK)
        val placements = TimelineEngine.placements(project, timeSeconds)
        val fadeAlpha = TimelineEngine.fadeAlpha(project, timeSeconds)
        val cardWidth = width.toFloat() / project.model.visibleCards

        placements.forEach { placement ->
            val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
            val alpha = (placement.alpha * fadeAlpha).coerceIn(0f, 1f)
            if (alpha <= 0f) return@forEach

            val left = cardWidth * placement.xInCards
            val top = height * ((1f - placement.alpha) * 0.014f)
            val layer = canvas.saveLayerAlpha(
                left,
                top,
                left + cardWidth,
                top + height,
                (alpha * 255f).toInt(),
            )
            canvas.translate(left, top)
            drawParentCard(canvas, card, project.model, cardWidth, height.toFloat())
            canvas.restoreToCount(layer)
        }
    }

    private fun drawParentCard(
        canvas: Canvas,
        card: CtsCard,
        model: VisualModel,
        width: Float,
        height: Float,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(18, 20, 25)
        canvas.drawRect(0f, 0f, width, height, paint)

        when (model) {
            VisualModel.Reference -> drawReference(canvas, card, width, height)
            VisualModel.Illustrated -> drawIllustrated(canvas, card, width, height)
            VisualModel.Compact -> drawCompact(canvas, card, width, height)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, width * 0.003f)
        paint.color = Color.rgb(8, 9, 11)
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    private fun drawReference(canvas: Canvas, card: CtsCard, width: Float, height: Float) {
        fillFrame(canvas, width, height, NormalizedRect(0f, 0f, 1f, 0.44f), Color.rgb(17, 19, 25))
        drawBadge(canvas, card, frame(width, height, NormalizedRect(0f, 0f, 1f, 0.44f)))

        val title = frame(width, height, NormalizedRect(0f, 0.44f, 1f, 0.098f))
        fill(canvas, title, Color.rgb(247, 245, 239))
        drawCenteredText(canvas, card.title, title, Color.rgb(24, 23, 20), width * 0.060f, true, 2)

        val description = frame(width, height, NormalizedRect(0f, 0.538f, 1f, 0.132f))
        fill(canvas, description, Color.rgb(207, 203, 191))
        drawCenteredText(canvas, card.description, description, Color.rgb(48, 46, 41), width * 0.041f, false, 4)

        drawImageFrame(
            canvas,
            card,
            width,
            height,
            NormalizedRect(0.085f, 0.67f, 0.83f, 0.32f),
            crop = true,
        )
    }

    private fun drawIllustrated(canvas: Canvas, card: CtsCard, width: Float, height: Float) {
        val imageFrame = frame(width, height, NormalizedRect(0.01f, 0.01f, 0.98f, 0.87f))
        paint.shader = LinearGradient(
            0f,
            imageFrame.top,
            0f,
            imageFrame.bottom,
            intArrayOf(
                Color.rgb(87, 208, 230),
                Color.rgb(87, 208, 230),
                Color.rgb(235, 197, 125),
                Color.rgb(242, 214, 154),
            ),
            floatArrayOf(0f, 0.64f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(imageFrame, paint)
        paint.shader = null
        drawImageSubcard(canvas, card, imageFrame, crop = false)

        val title = frame(width, height, NormalizedRect(0f, 0.88f, 1f, 0.12f))
        fill(canvas, title, Color.rgb(247, 245, 239))
        drawCenteredText(canvas, card.title, title, Color.rgb(24, 23, 20), width * 0.057f, true, 2)

        drawBadge(canvas, card, frame(width, height, NormalizedRect(0.12f, 0.035f, 0.76f, 0.26f)))
    }

    private fun drawCompact(canvas: Canvas, card: CtsCard, width: Float, height: Float) {
        val top = frame(width, height, NormalizedRect(0f, 0f, 1f, 0.39f))
        fill(canvas, top, Color.rgb(16, 17, 20))
        drawBadge(canvas, card, top)

        val title = frame(width, height, NormalizedRect(0f, 0.39f, 1f, 0.115f))
        fill(canvas, title, Color.rgb(247, 245, 239))
        drawCenteredText(canvas, card.title, title, Color.rgb(24, 23, 20), width * 0.053f, true, 3)

        drawImageFrame(
            canvas,
            card,
            width,
            height,
            NormalizedRect(0.01f, 0.505f, 0.98f, 0.485f),
            crop = true,
        )
    }

    private fun drawImageFrame(
        canvas: Canvas,
        card: CtsCard,
        width: Float,
        height: Float,
        normalized: NormalizedRect,
        crop: Boolean,
    ) {
        val rect = frame(width, height, normalized)
        fill(canvas, rect, Color.rgb(116, 120, 115))
        drawImageSubcard(canvas, card, rect, crop)
    }

    private fun drawImageSubcard(canvas: Canvas, card: CtsCard, owner: RectF, crop: Boolean) {
        val transform = card.imageSubcard.transform.clamped()
        val destination = RectF(
            owner.left + owner.width() * transform.x,
            owner.top + owner.height() * transform.y,
            owner.left + owner.width() * (transform.x + transform.width),
            owner.top + owner.height() * (transform.y + transform.height),
        )
        val bitmap = card.imageSubcard.source?.let(images::get)
        if (bitmap == null) {
            drawImagePlaceholder(canvas, destination)
            return
        }
        drawScaledBitmap(canvas, bitmap, destination, crop)
    }

    private fun drawScaledBitmap(canvas: Canvas, bitmap: Bitmap, destination: RectF, crop: Boolean) {
        val sourceAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val destinationAspect = destination.width() / destination.height().coerceAtLeast(1f)
        val source = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val target = RectF(destination)

        if (crop) {
            if (sourceAspect > destinationAspect) {
                val wantedWidth = bitmap.height * destinationAspect
                source.left = (bitmap.width - wantedWidth) / 2f
                source.right = source.left + wantedWidth
            } else {
                val wantedHeight = bitmap.width / destinationAspect
                source.top = (bitmap.height - wantedHeight) / 2f
                source.bottom = source.top + wantedHeight
            }
        } else {
            if (sourceAspect > destinationAspect) {
                val fittedHeight = destination.width() / sourceAspect
                target.top += (destination.height() - fittedHeight) / 2f
                target.bottom = target.top + fittedHeight
            } else {
                val fittedWidth = destination.height() * sourceAspect
                target.left += (destination.width() - fittedWidth) / 2f
                target.right = target.left + fittedWidth
            }
        }
        canvas.drawBitmap(bitmap, source, target, paint)
    }

    private fun drawBadge(canvas: Canvas, card: CtsCard, owner: RectF) {
        val badgeWidth = owner.width() * 0.66f
        val badgeHeight = owner.height() * 0.54f
        val badge = RectF(
            owner.centerX() - badgeWidth / 2f,
            owner.centerY() - badgeHeight / 2f,
            owner.centerX() + badgeWidth / 2f,
            owner.centerY() + badgeHeight / 2f,
        )

        if (project.showHexagons) {
            val path = hexagonPath(badge)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(
                0f,
                badge.top,
                0f,
                badge.bottom,
                Color.rgb(255, 75, 85),
                Color.rgb(215, 25, 32),
                Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, badge.width() * 0.008f)
            paint.color = Color.rgb(255, 222, 224)
            canvas.drawPath(path, paint)
        }

        val label = listOf(card.badgePrimary, card.badgeSecondary)
            .filter(String::isNotBlank)
            .joinToString("\n")
        drawCenteredText(canvas, label, badge, Color.WHITE, badge.width() * 0.12f, true, 3)
    }

    private fun drawImagePlaceholder(canvas: Canvas, rect: RectF) {
        fill(canvas, rect, Color.rgb(71, 75, 72))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(2f, rect.width() * 0.015f)
        paint.color = Color.rgb(190, 194, 189)
        val icon = RectF(
            rect.centerX() - rect.width() * 0.12f,
            rect.centerY() - rect.width() * 0.09f,
            rect.centerX() + rect.width() * 0.12f,
            rect.centerY() + rect.width() * 0.09f,
        )
        canvas.drawRoundRect(icon, icon.width() * 0.08f, icon.width() * 0.08f, paint)
        val mountain = Path().apply {
            moveTo(icon.left + icon.width() * 0.12f, icon.bottom - icon.height() * 0.18f)
            lineTo(icon.left + icon.width() * 0.42f, icon.top + icon.height() * 0.48f)
            lineTo(icon.left + icon.width() * 0.58f, icon.top + icon.height() * 0.66f)
            lineTo(icon.left + icon.width() * 0.76f, icon.top + icon.height() * 0.38f)
            lineTo(icon.right - icon.width() * 0.08f, icon.bottom - icon.height() * 0.18f)
        }
        canvas.drawPath(mountain, paint)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        color: Int,
        size: Float,
        bold: Boolean,
        maxLines: Int,
    ) {
        if (text.isBlank() || rect.width() <= 1f || rect.height() <= 1f) return
        textPaint.color = color
        textPaint.textSize = size.coerceAtLeast(8f)
        textPaint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.LEFT

        val horizontalPadding = rect.width() * 0.055f
        val layoutWidth = (rect.width() - horizontalPadding * 2f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()
        val x = rect.left + horizontalPadding
        val y = rect.top + (rect.height() - layout.height) / 2f
        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    private fun fillFrame(canvas: Canvas, width: Float, height: Float, rect: NormalizedRect, color: Int) {
        fill(canvas, frame(width, height, rect), color)
    }

    private fun fill(canvas: Canvas, rect: RectF, color: Int) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        canvas.drawRect(rect, paint)
    }

    private fun frame(width: Float, height: Float, rect: NormalizedRect): RectF = RectF(
        width * rect.x,
        height * rect.y,
        width * (rect.x + rect.width),
        height * (rect.y + rect.height),
    )

    private fun hexagonPath(rect: RectF): Path = Path().apply {
        moveTo(rect.left + rect.width() * 0.25f, rect.top)
        lineTo(rect.left + rect.width() * 0.75f, rect.top)
        lineTo(rect.right, rect.centerY())
        lineTo(rect.left + rect.width() * 0.75f, rect.bottom)
        lineTo(rect.left + rect.width() * 0.25f, rect.bottom)
        lineTo(rect.left, rect.centerY())
        close()
    }

    private fun loadBitmap(source: String): Bitmap? = runCatching {
        val stream = when {
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) -> URL(source).openStream()
            Uri.parse(source).scheme != null -> context.contentResolver.openInputStream(Uri.parse(source))
            else -> FileInputStream(File(source))
        }
        stream?.use(BitmapFactory::decodeStream)
    }.getOrNull()
}
