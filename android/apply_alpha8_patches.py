from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new, 1)


# The primary compatibility patch inserts the controller into the legacy shell. Replace
# its former speed-oriented name with the exact output-length controller.
shell_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ui/GoogleCtsApp.kt")
shell = shell_path.read_text()
shell = replace_once(
    shell,
    "            VideoSpeedControl(\n",
    "            VideoLengthControl(\n",
    "video length controller",
)
shell_path.write_text(shell)


# Preview text is handled by AutoFitBadgeText. The Canvas renderer used by MediaCodec must
# apply the same semantic rule: only explicit newlines may separate words.
renderer_path = Path(
    "android/app/src/main/java/io/github/retrofrost/cts/android/export/CtsCanvasSceneRenderer.kt"
)
renderer = renderer_path.read_text()
renderer = replace_once(
    renderer,
    "        drawCenteredText(canvas, label, badge, Color.WHITE, badge.width() * 0.12f, true, 4)",
    "        drawBadgeText(canvas, label, badge, Color.WHITE, badge.width() * 0.12f, true, 4)",
    "MP4 badge text call",
)

insert_at = renderer.index("    private fun drawCenteredText(")
badge_method = '''    /** Draw explicit badge lines without allowing Android to split a word. */
    private fun drawBadgeText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        color: Int,
        requestedSize: Float,
        bold: Boolean,
        maxLines: Int,
    ) {
        if (text.isBlank() || rect.width() <= 1f || rect.height() <= 1f) return
        val lines = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(maxLines)
            .toList()
        if (lines.isEmpty()) return

        textPaint.color = color
        textPaint.typeface = if (bold) {
            android.graphics.Typeface.DEFAULT_BOLD
        } else {
            android.graphics.Typeface.DEFAULT
        }
        textPaint.textAlign = Paint.Align.CENTER

        val horizontalPadding = rect.width() * 0.085f
        val verticalPadding = rect.height() * 0.075f
        val availableWidth = (rect.width() - horizontalPadding * 2f).coerceAtLeast(1f)
        val availableHeight = (rect.height() - verticalPadding * 2f).coerceAtLeast(1f)
        val minimumSize = max(6f, requestedSize * 0.34f)
        var size = requestedSize.coerceAtLeast(minimumSize)

        while (true) {
            textPaint.textSize = size
            val metrics = textPaint.fontMetrics
            val lineHeight = (metrics.descent - metrics.ascent) * 1.02f
            val widestLine = lines.maxOf { line -> textPaint.measureText(line) }
            val totalHeight = lineHeight * lines.size
            if (
                (widestLine <= availableWidth && totalHeight <= availableHeight) ||
                size <= minimumSize + 0.01f
            ) {
                val firstBaseline = rect.centerY() - totalHeight / 2f - metrics.ascent
                val save = canvas.save()
                canvas.clipRect(rect)
                lines.forEachIndexed { index, line ->
                    canvas.drawText(
                        line,
                        rect.centerX(),
                        firstBaseline + index * lineHeight,
                        textPaint,
                    )
                }
                canvas.restoreToCount(save)
                return
            }
            size = max(minimumSize, size - 1f)
        }
    }

'''
renderer = renderer[:insert_at] + badge_method + renderer[insert_at:]
renderer_path.write_text(renderer)
