package io.github.retrofrost.cts.android.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max

/** Model-independent bitmap placement shared by artwork, backdrops and pre-roll frames. */
internal object BitmapPainter {
    fun drawCenterCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: RectF,
        focusX: Float = 0.5f,
        focusY: Float = 0.5f,
        zoom: Float = 1f,
        paint: Paint,
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        if (destination.width() <= 0f || destination.height() <= 0f) return
        val scale = max(destination.width() / bitmap.width, destination.height() / bitmap.height) *
            zoom.coerceIn(1f, 3f)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val translationX = (destination.centerX() - bitmap.width * focusX.coerceIn(0f, 1f) * scale)
            .coerceIn(destination.right - scaledWidth, destination.left)
        val translationY = (destination.centerY() - bitmap.height * focusY.coerceIn(0f, 1f) * scale)
            .coerceIn(destination.bottom - scaledHeight, destination.top)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(translationX, translationY)
        }
        canvas.drawBitmap(bitmap, matrix, paint)
    }
}
