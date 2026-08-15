package io.github.retrofrost.cts.android.rendering

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max

/** Consistent fitted text used by preview and encoded output. */
internal class TextBlockPainter {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    fun draw(
        canvas: Canvas,
        text: String,
        rect: RectF,
        color: Int,
        bold: Boolean,
        maximumSize: Float,
        minimumSize: Float,
        maxLines: Int,
    ) {
        val display = text.trim()
        if (display.isEmpty() || rect.width() <= 2f || rect.height() <= 2f) return
        paint.color = color
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        var size = maximumSize.coerceAtLeast(minimumSize)
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(display, 0, display.length, paint, max(1, rect.width().toInt()))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val ellipsized = (0 until layout.lineCount).any { line -> layout.getEllipsisCount(line) > 0 }
            if ((layout.height <= rect.height() && !ellipsized) || size <= minimumSize) break
            size = max(minimumSize, size - 1f)
        }
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left, rect.top + max(0f, (rect.height() - layout.height) / 2f))
        layout.draw(canvas)
        canvas.restore()
    }
}
