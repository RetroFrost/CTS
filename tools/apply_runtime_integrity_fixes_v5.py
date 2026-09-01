#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Renderer capability: some references (Puberty in particular) do NOT use the
# top badge lane as an image canvas. A declarative renderer must be able to own
# a lower artwork rectangle without requiring the imported MegaPack to bake in
# app-specific padding/cropping.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
if '"ribbon-artwork-region-v1"' not in text:
    marker = '        "artwork-transform",\n'
    if marker not in text:
        raise SystemExit("renderer feature list marker changed")
    text = text.replace(marker, marker + '        "ribbon-artwork-region-v1",\n', 1)
path.write_text(text)


# ---------------------------------------------------------------------------
# Ribbon artwork region + fit mode.
# Default bundles retain the historical y=0..imageBottom center-crop behavior.
# A renderer that declares ribbon.artwork.region=description/custom may provide:
#   ribbon.artwork.top=<px>
#   ribbon.artwork.bottom=<px>
#   ribbon.artwork.inset-x=<px>
#   ribbon.artwork.fit=contain|cover
# This is fully declarative; no renderer id or Puberty string is inspected.
# ---------------------------------------------------------------------------
path = ANDROID / "RibbonFrameRenderer.kt"
text = path.read_text()

old_body = '''        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 105, 211)
        canvas.drawRect(left, 0f, right, imageBottom, paint)
        if (!card.imageLayer.equals("front", ignoreCase = true)) {
            drawArtwork(canvas, card, RectF(left, 0f, right, imageBottom))
        }

        var cursor = imageBottom
'''
new_body = '''        val rendererArtwork = rendererArtworkRect(card, slotX, spec)
        paint.style = Paint.Style.FILL
        paint.color = if (rendererArtwork != null) spec.backgroundColor else Color.rgb(0, 105, 211)
        canvas.drawRect(left, 0f, right, imageBottom, paint)
        if (!card.imageLayer.equals("front", ignoreCase = true) && rendererArtwork == null) {
            drawArtwork(canvas, card, RectF(left, 0f, right, imageBottom))
        }

        var cursor = imageBottom
'''
text = replace_once(text, old_body, new_body, "renderer artwork region body prelude")

old_desc_end = '''        if (hasDescription) {
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
new_desc_end = '''        if (hasDescription) {
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
        if (!card.imageLayer.equals("front", ignoreCase = true) && rendererArtwork != null) {
            drawArtwork(canvas, card, rendererArtwork, rendererArtworkFit(spec))
        }
    }
'''
text = replace_once(text, old_desc_end, new_desc_end, "renderer lower artwork draw")

old_front = '''    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val hasTitle = card.title.isNotBlank()
        val hasDescription = card.description.isNotBlank()
        val imageBottom = RendererArtworkLayout.imageBottom(card, spec)
        drawArtwork(
            canvas,
            card,
            RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, imageBottom),
        )
    }
'''
new_front = '''    private fun drawFrontArtwork(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {
        val custom = rendererArtworkRect(card, slotX, spec)
        val destination = custom ?: RectF(
            slotX + spec.bodyInset,
            0f,
            slotX + spec.bodyInset + spec.bodyWidth,
            RendererArtworkLayout.imageBottom(card, spec),
        )
        drawArtwork(canvas, card, destination, if (custom != null) rendererArtworkFit(spec) else "cover")
    }
'''
text = replace_once(text, old_front, new_front, "front renderer artwork region")

old_sig = '''    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF) {
'''
new_sig = '''    private fun drawArtwork(canvas: Canvas, card: StudioCard, destination: RectF, fit: String = "cover") {
'''
text = replace_once(text, old_sig, new_sig, "artwork fit signature")

old_scale = '''        val baseScale = max(destination.width() / sourceWidth, destination.height() / sourceHeight)
'''
new_scale = '''        val baseScale = if (fit.equals("contain", ignoreCase = true)) {
            min(destination.width() / sourceWidth, destination.height() / sourceHeight)
        } else {
            max(destination.width() / sourceWidth, destination.height() / sourceHeight)
        }
'''
text = replace_once(text, old_scale, new_scale, "renderer artwork contain fit")

helper_marker = '''    private fun loadImage(path: String): Bitmap? {'''
helpers = '''    private fun rendererArtworkRect(card: StudioCard, slotX: Float, spec: RendererSpec): RectF? {
        val region = tagText(spec, "ribbon.artwork.region", "").lowercase()
        if (region != "description" && region != "custom") return null
        val inset = tagFloat(spec, "ribbon.artwork.inset-x", 0f).coerceAtLeast(0f)
        val top = tagFloat(spec, "ribbon.artwork.top", spec.descriptionTop)
            .coerceIn(0f, REFERENCE_HEIGHT.toFloat())
        val bottom = tagFloat(spec, "ribbon.artwork.bottom", REFERENCE_HEIGHT.toFloat())
            .coerceIn(top + 1f, REFERENCE_HEIGHT.toFloat())
        val left = slotX + spec.bodyInset + inset
        val right = slotX + spec.bodyInset + spec.bodyWidth - inset
        if (right <= left + 1f) return null
        return RectF(left, top, right, bottom)
    }

    private fun rendererArtworkFit(spec: RendererSpec): String =
        tagText(spec, "ribbon.artwork.fit", "cover").lowercase()

    private fun tagFloat(spec: RendererSpec, key: String, fallback: Float): Float =
        spec.tags.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toFloatOrNull()
            ?: fallback

'''
if helpers not in text:
    if text.count(helper_marker) != 1:
        raise SystemExit("artwork helper insertion marker changed")
    text = text.replace(helper_marker, helpers + helper_marker, 1)
path.write_text(text)


# ---------------------------------------------------------------------------
# Runtime regression: verify a renderer-declared lower artwork region is used,
# and the historical top lane does not receive the image.
# ---------------------------------------------------------------------------
path = ROOT / "android/app/src/androidTest/java/io/github/retrofrost/cts/android/RuntimeIntegrityInstrumentedTest.kt"
text = path.read_text()
anchor = '''    private fun rendererBytes(spec: RendererSpec): ByteArray = ByteArrayOutputStream().use { output ->
'''
test = '''    @Test
    fun ribbonRendererOwnsLowerArtworkRegionInsteadOfForcingImageAtTop() {
        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.GREEN)
        val imageFile = File(scratch, "green-artwork.png")
        imageFile.outputStream().use { out -> check(image.compress(Bitmap.CompressFormat.PNG, 100, out)) }
        image.recycle()

        val spec = RendererSpec(
            id = "ribbon.lower-artwork-test",
            engine = "ribbon-exact",
            backgroundColor = Color.rgb(17, 17, 17),
            descriptionBackgroundColor = Color.rgb(106, 104, 98),
            bodyInset = 1f,
            bodyWidth = 470f,
            imageHeight = 477f,
            titleHeight = 100f,
            descriptionTop = 577f,
            openingStarts = listOf(0),
            openingEnds = listOf(120),
            continuousStartFrame = 120,
            requiredFeatures = listOf("ribbon-artwork-region-v1"),
            tags = listOf(
                "ribbon.artwork.region=description",
                "ribbon.artwork.top=720",
                "ribbon.artwork.bottom=1060",
                "ribbon.artwork.inset-x=55",
                "ribbon.artwork.fit=contain",
            ),
        )
        val project = StudioProject(
            cards = listOf(StudioCard(title = "Title", value = "", description = "Description", image = imageFile.absolutePath)),
            width = 1920,
            height = 1080,
            fps = 60,
            showBadges = false,
        )
        val bitmap = RendererBridge.renderWithSpec(project, spec, 119, 1920, 1080)
        try {
            assertNotEquals(Color.GREEN, bitmap.getPixel(235, 200))
            assertEquals(Color.GREEN, bitmap.getPixel(235, 890))
        } finally {
            bitmap.recycle()
        }
    }

'''
if test not in text:
    if text.count(anchor) != 1:
        raise SystemExit("runtime artwork test insertion marker changed")
    text = text.replace(anchor, test + anchor, 1)
path.write_text(text)

print("Applied renderer-owned Ribbon artwork region and contain-fit support")
