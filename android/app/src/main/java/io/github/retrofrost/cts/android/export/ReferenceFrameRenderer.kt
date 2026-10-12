package io.github.retrofrost.cts.android.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.rendering.BitmapPainter
import io.github.retrofrost.cts.android.rendering.BitmapSourceCache
import io.github.retrofrost.cts.android.rendering.ReferenceCardPainter
import io.github.retrofrost.cts.android.rendering.ReferenceSceneBuilder
import io.github.retrofrost.cts.android.rendering.TextBlockPainter

/**
 * Frame-perfect renderer shared by the live preview and MediaCodec export.
 * Timeline sampling, bitmap loading and text fitting are deliberately separate
 * passes so a UI refactor cannot change the encoded video.
 */
class ReferenceFrameRenderer(
    private val context: Context,
    private val project: CtsProject,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val images = BitmapSourceCache(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = TextBlockPainter()
    private val cards = ReferenceCardPainter(project, height, images, paint, text)
    @Volatile
    private var closed = false
    private val introSource = project.introVideo.uri?.takeIf { it.isNotBlank() }
    private var introRetriever: MediaMetadataRetriever? = null

    @Synchronized
    fun render(target: Bitmap, outputTimeSeconds: Float) {
        check(!closed) { "Renderer is closed" }
        require(target.width == width && target.height == height)
        // No Paint state may leak from one encoded frame into the next.
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        val scene = ReferenceSceneBuilder.build(project, outputTimeSeconds)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        if (scene.customIntroVisible) {
            drawCustomIntro(canvas, scene.outputTimeSeconds)
            return
        }
        val cardWidth = width / 4f

        if (scene.introCreditsVisible) {
            ReferenceOverlayRenderer.drawIntroCredits(canvas, width, height, project.credits, paint)
        }
        scene.placements.forEach { placement ->
            val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
            val cardX = cardWidth * placement.xInCards
            canvas.save()
            canvas.translate(cardX, 0f)
            placement.bodyTransform?.let { transform ->
                canvas.translate(
                    (transform.xPx / 480f - placement.xInCards) * cardWidth,
                    transform.yPx / 1080f * height,
                )
                canvas.scale(transform.scaleX, transform.scaleY)
            }
            canvas.clipRect(0f, 0f, cardWidth * placement.bodyReveal.coerceIn(0f, 1f), height.toFloat())
            cards.drawBody(canvas, card, cardWidth, placement)
            canvas.restore()
            if (placement.badgeVisible) {
                cards.drawBadge(canvas, card, cardX, cardWidth, placement)
            }
        }

        ReferenceOverlayRenderer.drawOutro(
            canvas,
            width,
            height,
            scene.outroCoverProgress,
            scene.outroContentAlpha,
            scene.outroContentYOffsetPx,
            project.credits,
            paint,
        )

        if (scene.fadeAlpha < 0.999f) {
            paint.shader = null
            paint.color = Color.argb(((1f - scene.fadeAlpha) * 255f).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        images.close()
        introRetriever?.release()
    }

    private fun drawCustomIntro(canvas: Canvas, outputTimeSeconds: Float) {
        val retriever = introRetriever ?: createIntroRetriever()?.also { introRetriever = it } ?: return
        val timeUs = (outputTimeSeconds.coerceAtLeast(0f) * 1_000_000f).toLong()
        val frame = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width,
                    height,
                ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }
        }.getOrNull() ?: return
        try {
            BitmapPainter.drawCenterCrop(
                canvas = canvas,
                bitmap = frame,
                destination = RectF(0f, 0f, width.toFloat(), height.toFloat()),
                paint = paint,
            )
        } finally {
            if (!frame.isRecycled) frame.recycle()
        }
    }

    private fun createIntroRetriever(): MediaMetadataRetriever? {
        val source = introSource ?: return null
        return runCatching {
            MediaMetadataRetriever().apply {
                val uri = Uri.parse(source)
                if (uri.scheme.isNullOrBlank()) setDataSource(source) else setDataSource(context, uri)
            }
        }.getOrNull()
    }

}
