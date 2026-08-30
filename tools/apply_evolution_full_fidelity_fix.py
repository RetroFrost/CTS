from pathlib import Path

path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt')
text = path.read_text(encoding='utf-8')


def replace_once(old: str, new: str, name: str) -> None:
    global text
    if old not in text:
        raise SystemExit(f'{name}: target not found')
    text = text.replace(old, new, 1)


replace_once(
    '        canvas.drawColor(spec.backgroundColor)\n',
    '        canvas.drawColor(frameBackgroundColor(spec, frame))\n',
    'frame-addressed background',
)

replace_once(
'''private fun motionTrack(spec: RendererSpec, target: String, frame: Int): Float? {
    val centre = spec.track(target, frame) ?: return null
    if (spec.precisionMode == "frame-exact") return centre
    val previous = spec.track(target, frame - 1) ?: centre
    val next = spec.track(target, frame + 1) ?: centre
    return previous * 0.20f + centre * 0.60f + next * 0.20f
}
''',
'''private fun motionTrack(spec: RendererSpec, target: String, frame: Int): Float? {
    val centre = spec.track(target, frame) ?: return null
    if (spec.precisionMode == "frame-exact") return centre
    val previous = spec.track(target, frame - 1) ?: centre
    val next = spec.track(target, frame + 1) ?: centre
    return previous * 0.20f + centre * 0.60f + next * 0.20f
}

    private fun frameBackgroundColor(spec: RendererSpec, frame: Int): Int {
        val gray = motionTrack(spec, "ribbon.background.gray", frame)
        val r = motionTrack(spec, "ribbon.background.r", frame)
        val g = motionTrack(spec, "ribbon.background.g", frame)
        val b = motionTrack(spec, "ribbon.background.b", frame)
        if (gray == null && r == null && g == null && b == null) return spec.backgroundColor
        val fallback = gray ?: 0f
        return Color.rgb(
            (r ?: fallback).roundToInt().coerceIn(0, 255),
            (g ?: fallback).roundToInt().coerceIn(0, 255),
            (b ?: fallback).roundToInt().coerceIn(0, 255),
        )
    }
''',
    'background helper',
)

replace_once(
'''        val age: Float
        val matrix = Matrix()
        if (index < 4) {
            if (local < OPENING_BADGE_FIRST_FRAME) return
            age = ((local.coerceAtMost(OPENING_BADGE_FINAL_FRAME) - OPENING_BADGE_FIRST_FRAME).toFloat() /
                (OPENING_BADGE_FINAL_FRAME - OPENING_BADGE_FIRST_FRAME)) * BADGE_ENTRY_AGE

            // Corrected hand-dissolve bundles provide a different measured affine
            // path for each opening badge. Older Ribbon bundles keep their shared path.
            val exactPrefix = "ribbon.open.$index"
            val prefix = if (spec.track("$exactPrefix.m00", local) != null) exactPrefix else "ribbon.open"
''',
'''        val age: Float
        val matrix = Matrix()
        if (index < 4) {
            val exactPrefix = "ribbon.open.$index"
            val explicitVisibility = motionTrack(spec, "$exactPrefix.visible", local)
            if (explicitVisibility != null) {
                if (explicitVisibility <= 0.001f) return
            } else if (local < OPENING_BADGE_FIRST_FRAME) return
            age = motionTrack(spec, "$exactPrefix.age", local)
                ?: ((local.coerceAtMost(OPENING_BADGE_FINAL_FRAME) - OPENING_BADGE_FIRST_FRAME).toFloat() /
                    (OPENING_BADGE_FINAL_FRAME - OPENING_BADGE_FIRST_FRAME)) * BADGE_ENTRY_AGE

            // Source-exact bundles may provide a different frame-addressed affine
            // path for every opening badge. Older Ribbon bundles keep their shared path.
            val prefix = if (spec.track("$exactPrefix.m00", local) != null) exactPrefix else "ribbon.open"
''',
    'exact badge visibility and age',
)

replace_once(
'''        drawRibbonBadgeText(canvas, project, card, age, spec)
        drawRibbonShine(canvas, age, path, spec, index, local)
''',
'''        drawRibbonBadgeText(canvas, project, card, age, spec, index, local)
        drawRibbonShine(canvas, age, path, spec, index, local)
''',
    'badge text call',
)

replace_once(
'''    private fun drawRibbonBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, age: Float, spec: RendererSpec) {
''',
'''    private fun drawRibbonBadgeText(
        canvas: Canvas,
        project: StudioProject,
        card: StudioCard,
        age: Float,
        spec: RendererSpec,
        cardIndex: Int,
        local: Int,
    ) {
''',
    'badge text signature',
)

replace_once(
'''            val startAge = 0.90f + index * 0.10f
            val progress = ((age - startAge) / 0.42f).coerceIn(0f, 1f)
            if (progress <= 0f) return@forEachIndexed
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            val y = item.second + textLandingOffset(age) - (1f - eased) * 112f
            val alpha = (255f * (progress * 1.75f).coerceIn(0f, 1f)).roundToInt()
''',
'''            val startAge = 0.90f + index * 0.10f
            val prefix = if (cardIndex < 4) "ribbon.open.$cardIndex" else "ribbon.card.$cardIndex"
            val groupProgress = motionTrack(spec, "$prefix.text.progress", local)
            val exactProgress = motionTrack(spec, "$prefix.text.$index.progress", local) ?: groupProgress
            val progress = (exactProgress ?: ((age - startAge) / 0.42f)).coerceIn(0f, 1f)
            if (progress <= 0f) return@forEachIndexed
            val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
            val exactOffset = motionTrack(spec, "$prefix.text.$index.y", local)
                ?: motionTrack(spec, "$prefix.text.y", local)
            val y = item.second + (exactOffset ?: (textLandingOffset(age) - (1f - eased) * 112f))
            val exactAlpha = motionTrack(spec, "$prefix.text.$index.alpha", local)
                ?: motionTrack(spec, "$prefix.text.alpha", local)
            val alpha = (255f * (exactAlpha ?: (progress * 1.75f)).coerceIn(0f, 1f)).roundToInt()
            val exactBlur = motionTrack(spec, "$prefix.text.$index.blur", local)
                ?: motionTrack(spec, "$prefix.text.blur", local)
''',
    'exact badge text tracks',
)

replace_once(
'''                    textPaint.maskFilter = BlurMaskFilter(max(0.2f, (1f - progress) * 5.8f), BlurMaskFilter.Blur.NORMAL)
''',
'''                    textPaint.maskFilter = BlurMaskFilter(
                        max(0.2f, exactBlur ?: ((1f - progress) * 5.8f)),
                        BlurMaskFilter.Blur.NORMAL,
                    )
''',
    'badge trail blur',
)

replace_once(
'''            textPaint.maskFilter = if (progress < 0.96f) {
                BlurMaskFilter(max(0.2f, (1f - progress) * 5.8f), BlurMaskFilter.Blur.NORMAL)
            } else null
''',
'''            textPaint.maskFilter = when {
                exactBlur != null && exactBlur > 0.05f -> BlurMaskFilter(exactBlur, BlurMaskFilter.Blur.NORMAL)
                exactBlur != null -> null
                progress < 0.96f -> BlurMaskFilter(max(0.2f, (1f - progress) * 5.8f), BlurMaskFilter.Blur.NORMAL)
                else -> null
            }
''',
    'badge main blur',
)

replace_once(
'''        drawContent(canvas, project, (contentEnd - 1).coerceAtLeast(0), spec)
''',
'''        drawOutroAnchor(canvas, project, (contentEnd - 1).coerceAtLeast(0), local, spec)
''',
    'outro final-card anchor',
)

replace_once(
'''            paint.color = spec.backgroundColor
''',
'''            paint.color = frameBackgroundColor(spec, frame)
''',
    'outro wipe background',
)

replace_once(
'''        paint.color = spec.backgroundColor
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, 1440f, REFERENCE_HEIGHT.toFloat(), paint)
''',
'''        paint.color = frameBackgroundColor(spec, frame)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, 1440f, REFERENCE_HEIGHT.toFloat(), paint)
''',
    'outro body background',
)

insert_before = '''    private fun drawEndGroup(canvas: Canvas, top: Float) {
'''
if insert_before not in text:
    raise SystemExit('outro anchor insertion marker not found')
text = text.replace(insert_before, '''    private fun drawOutroAnchor(
        canvas: Canvas,
        project: StudioProject,
        settledFrame: Int,
        local: Int,
        spec: RendererSpec,
    ) {
        if (project.cards.isEmpty()) return
        val index = project.cards.lastIndex
        val defaultX = if (project.cards.size >= 4) 3f * spec.slotPitch else index * spec.slotPitch
        val x = motionTrack(spec, "ribbon.outro.card.x", local) ?: defaultX
        drawCardBody(canvas, project, project.cards[index], x, spec)
        drawBadge(canvas, project, index, x, settledFrame, spec)
        if (project.cards[index].imageLayer.equals("front", ignoreCase = true)) {
            drawFrontArtwork(canvas, project.cards[index], x, spec)
        }
    }

''' + insert_before, 1)

replace_once(
'''        lineHeight: Float,
    ) {
        textPaint.typeface = if (bold) boldTypeface else regularTypeface
''',
'''        lineHeight: Float,
        project: StudioProject? = null,
    ) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val fallback = if (bold) boldTypeface else regularTypeface
        textPaint.typeface = if (project != null) ProjectFontResolver.resolve(project, fallback, style) else fallback
''',
    'project font multiline',
)

replace_once(
'''    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean) {
        textPaint.typeface = if (bold) boldTypeface else regularTypeface
''',
'''    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean,
        project: StudioProject? = null,
    ) {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        val fallback = if (bold) boldTypeface else regularTypeface
        textPaint.typeface = if (project != null) ProjectFontResolver.resolve(project, fallback, style) else fallback
''',
    'project font centered',
)

# The two opening-credit multiline calls and all credit labels should honour the
# user/project typeface just like card text. Keep end-screen/action-bar defaults.
text = text.replace(
'''            24f,
        )
        paint.color = Color.rgb(180, 180, 180)
''',
'''            24f,
            project,
        )
        paint.color = Color.rgb(180, 180, 180)
''',
1,
)
text = text.replace('drawCenteredText(canvas, "Credits", cx, 286f, 44f, Color.WHITE, true)', 'drawCenteredText(canvas, "Credits", cx, 286f, 44f, Color.WHITE, true, project)', 1)
text = text.replace('drawCenteredText(canvas, label, cx, y, 18f, Color.WHITE, false)', 'drawCenteredText(canvas, label, cx, y, 18f, Color.WHITE, false, project)', 1)
text = text.replace('drawCenteredText(canvas, value, cx, y + 28f, 18f, Color.WHITE, true)', 'drawCenteredText(canvas, value, cx, y + 28f, 18f, Color.WHITE, true, project)', 1)
text = text.replace(
'''            14f,
        )
    }

    private fun drawBadge(
''',
'''            14f,
            project,
        )
    }

    private fun drawBadge(
''',
1,
)

replace_once(
'''            val minimum = if (count <= 4) spec.openingEnds.getOrElse(count - 1) { spec.continuousStartFrame }
            else spec.continuousStartFrame + (count - 4)
''',
'''            val minimum = if (count <= 4) spec.continuousStartFrame
            else spec.continuousStartFrame + (count - 4)
''',
    'short custom project minimum',
)

replace_once(
'''        if (count <= 4) return spec.openingEnds.getOrElse(count - 1) { spec.continuousStartFrame }
''',
'''        if (count <= 4) return spec.continuousStartFrame
''',
    'short automatic project minimum',
)

path.write_text(text, encoding='utf-8')
