package io.github.retrofrost.cts.android.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Small, allocation-free helpers shared by the export renderer. */
object RendererHardening {
    fun sanitizeText(value: String?): String = value.orEmpty().trim().replace(Regex("[\\t\\r\\n ]+"), " ")

    fun alpha(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    fun clear(bitmap: Bitmap) {
        Canvas(bitmap).drawColor(Color.BLACK)
    }

    fun drawBitmapSafely(canvas: Canvas, bitmap: Bitmap?, destination: RectF, paint: Paint) {
        if (bitmap == null || bitmap.isRecycled || destination.width() <= 0f || destination.height() <= 0f) return
        canvas.drawBitmap(bitmap, null, destination, paint)
    }
}
