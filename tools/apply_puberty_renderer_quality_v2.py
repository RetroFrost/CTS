#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RIBBON = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt"
BUNDLE = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
MARKER = "puberty-description-artwork-v1"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


ribbon = RIBBON.read_text()
if MARKER not in ribbon:
    old = '''    private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val left = slotX + spec.bodyInset
        val right = left + spec.bodyWidth
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val canonicalDescriptionHeight = REFERENCE_HEIGHT - spec.descriptionTop
        val descriptionHeight = if (hasDescription) canonicalDescriptionHeight else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 105, 211)
        canvas.drawRect(left, 0f, right, imageBottom, paint)
        if (!card.imageLayer.equals("front", ignoreCase = true)) {
            drawArtwork(canvas, card, RectF(left, 0f, right, imageBottom))
        }

        var cursor = imageBottom
        if (hasTitle) {
            paint.color = spec.titleBackgroundColor
            canvas.drawRect(left, cursor, right, cursor + titleHeight, paint)
            drawFittedText(
                canvas,
                card.title,
                RectF(left + 12f, cursor + 2f, right - 12f, cursor + titleHeight - 2f),
                spec.titleTextColor,
                spec.titleTextSize,
                22f,
                2,
                true,
                project,
            )
            cursor += titleHeight
        }
        if (hasDescription) {
            paint.color = spec.descriptionBackgroundColor
            canvas.drawRect(left, cursor, right, REFERENCE_HEIGHT.toFloat(), paint)
            drawFittedText(
                canvas,
                card.description,
                RectF(left + 17f, cursor + 6f, right - 17f, REFERENCE_HEIGHT - 6f),
                spec.descriptionTextColor,
                spec.descriptionTextSize,
                12f,
                4,
                false,
                project,
            )
        }
    }
'''
    new = '''    private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val left = slotX + spec.bodyInset
        val right = left + spec.bodyWidth
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val pubertyArtwork = spec.tags.contains("puberty-description-artwork-v1")

        if (pubertyArtwork) {
            // Puberty does not use the generic Ribbon image-at-the-top layout.
            // The upper 477 px lane is the dark badge field; artwork belongs in
            // the lower description panel beneath the copy. Use contain instead
            // of centre-crop so arbitrary megapack icons remain fully visible.
            val titleTop = spec.imageHeight.coerceIn(1f, REFERENCE_HEIGHT.toFloat())
            val titleHeight = if (hasTitle) spec.titleHeight else 0f
            val descriptionTop = (titleTop + titleHeight).coerceAtMost(REFERENCE_HEIGHT.toFloat())

            paint.style = Paint.Style.FILL
            paint.color = spec.backgroundColor
            canvas.drawRect(left, 0f, right, titleTop, paint)

            if (hasTitle) {
                paint.color = spec.titleBackgroundColor
                canvas.drawRect(left, titleTop, right, descriptionTop, paint)
                drawFittedText(
                    canvas,
                    card.title,
                    RectF(left + 12f, titleTop + 2f, right - 12f, descriptionTop - 2f),
                    spec.titleTextColor,
                    spec.titleTextSize,
                    22f,
                    2,
                    true,
                    project,
                )
            }

            paint.color = spec.descriptionBackgroundColor
            canvas.drawRect(left, descriptionTop, right, REFERENCE_HEIGHT.toFloat(), paint)
            val copyBottom = min(REFERENCE_HEIGHT - 8f, descriptionTop + 132f)
            if (hasDescription) {
                drawFittedText(
                    canvas,
                    card.description,
                    RectF(left + 17f, descriptionTop + 7f, right - 17f, copyBottom),
                    spec.descriptionTextColor,
                    spec.descriptionTextSize,
                    12f,
                    4,
                    false,
                    project,
                )
            }
            if (!card.imageLayer.equals("front", ignoreCase = true)) {
                val artTop = if (hasDescription) descriptionTop + 124f else descriptionTop + 14f
                drawArtworkContain(
                    canvas,
                    card,
                    RectF(left + 18f, artTop, right - 18f, REFERENCE_HEIGHT - 12f),
                )
            }
            return
        }

        val canonicalDescriptionHeight = REFERENCE_HEIGHT - spec.descriptionTop
        val descriptionHeight = if (hasDescription) canonicalDescriptionHeight else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 105, 211)
        canvas.drawRect(left, 0f, right, imageBottom, paint)
        if (!card.imageLayer.equals("front", ignoreCase = true)) {
            drawArtwork(canvas, card, RectF(left, 0f, right, imageBottom))
        }

        var cursor = imageBottom
        if (hasTitle) {
            paint.color = spec.titleBackgroundColor
            canvas.drawRect(left, cursor, right, cursor + titleHeight, paint)
            drawFittedText(
                canvas,
                card.title,
                RectF(left + 12f, cursor + 2f, right - 12f, cursor + titleHeight - 2f),
                spec.titleTextColor,
                spec.titleTextSize,
                22f,
                2,
                true,
                project,
            )
            cursor += titleHeight
        }
        if (hasDescription) {
            paint.color = spec.descriptionBackgroundColor
            canvas.drawRect(left, cursor, right, REFERENCE_HEIGHT.toFloat(), paint)
            drawFittedText(
                canvas,
                card.description,
                RectF(left + 17f, cursor + 6f, right - 17f, REFERENCE_HEIGHT - 6f),
                spec.descriptionTextColor,
                spec.descriptionTextSize,
                12f,
                4,
                false,
                project,
            )
        }
    }
'''
    ribbon = replace_once(ribbon, old, new, "Puberty card artwork layout")

    old = '''    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val descriptionHeight = if (hasDescription) REFERENCE_HEIGHT - spec.descriptionTop else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)
        drawArtwork(
            canvas,
            card,
            RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, imageBottom),
        )
    }
'''
    new = '''    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {
        if (spec.tags.contains("puberty-description-artwork-v1")) {
            val left = slotX + spec.bodyInset
            val right = left + spec.bodyWidth
            val titleTop = spec.imageHeight.coerceIn(1f, REFERENCE_HEIGHT.toFloat())
            val descriptionTop = (titleTop + if (card.title.isNotBlank()) spec.titleHeight else 0f)
                .coerceAtMost(REFERENCE_HEIGHT.toFloat())
            val artTop = if (card.description.isNotBlank()) descriptionTop + 124f else descriptionTop + 14f
            drawArtworkContain(canvas, card, RectF(left + 18f, artTop, right - 18f, REFERENCE_HEIGHT - 12f))
            return
        }
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val descriptionHeight = if (hasDescription) REFERENCE_HEIGHT - spec.descriptionTop else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)
        drawArtwork(
            canvas,
            card,
            RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, imageBottom),
        )
    }
'''
    ribbon = replace_once(ribbon, old, new, "Puberty front artwork layout")

    anchor = '''    private fun loadImage(path: String): Bitmap? {'''
    helper = '''    private fun drawArtworkContain(canvas: Canvas, card: StudioCard, destination: RectF) {
        if (destination.width() <= 1f || destination.height() <= 1f) return
        val bitmap = loadImage(card.image) ?: return
        val leftCrop = card.imageCropLeft.coerceIn(0.0, 0.95)
        val topCrop = card.imageCropTop.coerceIn(0.0, 0.95)
        val rightCrop = card.imageCropRight.coerceIn(0.0, 0.95)
        val bottomCrop = card.imageCropBottom.coerceIn(0.0, 0.95)
        val srcLeft = (bitmap.width * leftCrop).roundToInt().coerceIn(0, bitmap.width - 1)
        val srcTop = (bitmap.height * topCrop).roundToInt().coerceIn(0, bitmap.height - 1)
        val srcRight = (bitmap.width * (1.0 - rightCrop)).roundToInt().coerceIn(srcLeft + 1, bitmap.width)
        val srcBottom = (bitmap.height * (1.0 - bottomCrop)).roundToInt().coerceIn(srcTop + 1, bitmap.height)
        val source = Rect(srcLeft, srcTop, srcRight, srcBottom)
        val sourceWidth = source.width().toFloat().coerceAtLeast(1f)
        val sourceHeight = source.height().toFloat().coerceAtLeast(1f)
        val baseScale = min(destination.width() / sourceWidth, destination.height() / sourceHeight)
        val scale = baseScale * card.imageScale.coerceIn(0.05, 12.0).toFloat()
        val drawnWidth = sourceWidth * scale
        val drawnHeight = sourceHeight * scale
        val cx = destination.centerX() + card.imageX.toFloat()
        val cy = destination.centerY() + card.imageY.toFloat()
        val target = RectF(cx - drawnWidth / 2f, cy - drawnHeight / 2f, cx + drawnWidth / 2f, cy + drawnHeight / 2f)
        canvas.save()
        canvas.clipRect(destination)
        if (card.imageRotation != 0.0) canvas.rotate(card.imageRotation.toFloat(), cx, cy)
        paint.alpha = 255
        canvas.drawBitmap(bitmap, source, target, paint)
        canvas.restore()
    }

    private fun loadImage(path: String): Bitmap? {'''
    ribbon = replace_once(ribbon, anchor, helper, "Puberty contain artwork helper")

# After apply_puberty_outro_source_lock_v1.py, this exact block still exists.
old_x = '''        val defaultX = if (project.cards.size >= 4) 3f * spec.slotPitch else index * spec.slotPitch
        val x = motionTrack(spec, "ribbon.outro.card.x", local) ?: defaultX
        drawCardBody(canvas, project, project.cards[index], x, spec)
'''
new_x = '''        val defaultX = if (project.cards.size >= 4) 3f * spec.slotPitch else index * spec.slotPitch
        val exactX = motionTrack(spec, "ribbon.outro.card.x", local)
        val canonicalCount = spec.track("ribbon.card_count", 0)?.roundToInt()
        val x = if (
            exactX != null &&
            spec.tags.contains("ribbon-adaptive-outro-v1") &&
            canonicalCount != null &&
            project.cards.size != canonicalCount
        ) {
            // The source track is an absolute 50-card position curve. Rebase only
            // non-reference projects from their actual final conveyor position and
            // blend back into the source curve with zero endpoint velocity. The
            // canonical Puberty project remains bit-for-bit on its measured X path.
            val settledX = positionsForFrame(project, settledFrame, spec)[index] ?: defaultX
            val sourceStart = motionTrack(spec, "ribbon.outro.card.x", 0) ?: exactX
            val blendFrames = (motionTrack(spec, "ribbon.outro.adaptive.blend.frames", 0) ?: 60f)
                .roundToInt().coerceAtLeast(1)
            val p = smootherstep(local.toFloat() / blendFrames)
            exactX + (settledX - sourceStart) * (1f - p)
        } else {
            exactX ?: defaultX
        }
        drawCardBody(canvas, project, project.cards[index], x, spec)
'''
ribbon = replace_once(ribbon, old_x, new_x, "Adaptive Puberty outro X")

smooth_anchor = '''    private fun easeInOutCubic(value: Float): Float {'''
smooth_helper = '''    private fun smootherstep(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6f - 15f) + 10f)
    }

    private fun easeInOutCubic(value: Float): Float {'''
ribbon = replace_once(ribbon, smooth_anchor, smooth_helper, "Smootherstep helper")
RIBBON.write_text(ribbon)

bundle = BUNDLE.read_text()
for feature in ("ribbon-adaptive-outro-v1", "puberty-description-artwork-v1"):
    if f'"{feature}"' not in bundle:
        bundle = replace_once(
            bundle,
            '        "preview-frames",\n',
            f'        "preview-frames",\n        "{feature}",\n',
            f"renderer capability {feature}",
        )
BUNDLE.write_text(bundle)
print("Applied Puberty renderer quality v2: description artwork, contain fit, adaptive outro and smooth blend")
