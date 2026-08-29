from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:180]!r}")
    p.write_text(text.replace(old, new, 1))

ledger = Path("android/app/src/main/java/io/github/retrofrost/cts/android/RenderPassLedger.kt")
ledger.write_text('''package io.github.retrofrost.cts.android

/**
 * Frame-scoped ownership ledger for renderer composition.
 *
 * A logical element owns exactly one paint pass per frame. Intentional layers
 * inside that element (gradient, stroke, shine, text) remain legal, but a
 * second caller cannot paint the same logical element again.
 */
internal class RenderPassLedger {
    private val claimed = HashSet<String>()

    fun claim(key: String): Boolean = claimed.add(key)

    fun once(key: String, block: () -> Unit) {
        if (claim(key)) block()
    }
}
''')

renderer = "android/app/src/main/java/io/github/retrofrost/cts/android/RelationshipsPrecisionFrameRenderer.kt"
replace_once(
    renderer,
    "import android.graphics.BitmapFactory\n",
    "import android.graphics.BitmapFactory\nimport android.graphics.BlurMaskFilter\n",
)

replace_once(
    renderer,
    '''        val spec = RendererRuntime.active
        val cfg = exactConfig(spec)
        drawReference(Canvas(reference), project, frameIndex.coerceAtLeast(0), spec, cfg)
''',
    '''        val spec = RendererRuntime.active
        val cfg = exactConfig(spec)
        val ledger = RenderPassLedger()
        drawReference(Canvas(reference), project, frameIndex.coerceAtLeast(0), spec, cfg, ledger)
''',
)

start = '''    private fun drawReference(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
        canvas.drawColor(spec.backgroundColor)
        drawFooterWaveform(canvas, frame, cfg)
        if (project.cards.isEmpty()) {
            drawIntroLogo(canvas, frame, spec, cfg)
            return
        }
        val contentEnd = RelationshipsTimeline.contentEndFrame(project, spec)
        when {
            frame < spec.openingStarts.firstOrNull().orZero() -> drawIntroLogo(canvas, frame, spec, cfg)
            frame < contentEnd -> {
                if (frame < cfg.int("intro.overlayUntilFrame", spec.openingStarts.firstOrNull().orZero())) {
                    drawIntroLogo(canvas, frame, spec, cfg)
                }
                drawContent(canvas, project, frame, spec, cfg)
            }
            else -> drawOutro(canvas, project, frame, contentEnd, spec, cfg)
        }
    }
'''
replacement = '''    private fun drawReference(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
        cfg: ExactConfig,
        ledger: RenderPassLedger,
    ) {
        canvas.drawColor(spec.backgroundColor)
        ledger.once("footer.waveform") { drawFooterWaveform(canvas, frame, cfg) }
        if (project.cards.isEmpty()) {
            ledger.once("intro") { drawIntroLogo(canvas, frame, spec, cfg) }
            return
        }
        val contentEnd = RelationshipsTimeline.contentEndFrame(project, spec)
        when {
            frame < spec.openingStarts.firstOrNull().orZero() -> {
                ledger.once("intro") { drawIntroLogo(canvas, frame, spec, cfg) }
            }
            frame < contentEnd -> {
                if (frame < cfg.int("intro.overlayUntilFrame", spec.openingStarts.firstOrNull().orZero())) {
                    ledger.once("intro") { drawIntroLogo(canvas, frame, spec, cfg) }
                }
                ledger.once("content") { drawContent(canvas, project, frame, spec, cfg, ledger) }
            }
            else -> ledger.once("outro") { drawOutro(canvas, project, frame, contentEnd, spec, cfg, ledger) }
        }
    }
'''
replace_once(renderer, start, replacement)

old_content = '''    private fun drawContent(canvas: Canvas, project: StudioProject, frame: Int, spec: RendererSpec, cfg: ExactConfig) {
        val positions = linkedMapOf<Int, Float>()
        if (frame < spec.continuousStartFrame) {
            val starts = spec.openingStarts
            for (index in 0 until min(4, project.cards.size)) {
                val start = starts.getOrElse(index) { starts.lastOrNull().orZero() + index * 140 }
                if (frame >= start) {
                    positions[index] = spec.track("card.$index.x", frame) ?: index * spec.slotPitch
                }
            }
        } else {
            val scroll = exactScroll(spec, frame) ?: ((frame - spec.continuousStartFrame) * 2f)
            project.cards.indices.forEach { index ->
                val baseX = index * spec.slotPitch - scroll
                val x = spec.track("card.$index.x", frame) ?: baseX
                if (x > -spec.slotPitch * 2f && x < 1920f + spec.slotPitch * 2f) positions[index] = x
            }
        }

        positions.forEach { (index, x) ->
            val y = spec.track("card.$index.y", frame) ?: 0f
            val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
            val local = frame - entry
            val uniform = spec.track("card.$index.body.scale", frame)
                ?: spec.track("relationships.card.body.scale", local)
                ?: 1f
            val scaleX = spec.track("card.$index.body.scaleX", frame)
                ?: spec.track("relationships.card.body.scaleX", local)
                ?: uniform
            val scaleY = spec.track("card.$index.body.scaleY", frame)
                ?: spec.track("relationships.card.body.scaleY", local)
                ?: uniform
            val pivotX = x + cfg.float("card.body.pivotX", spec.bodyInset + spec.bodyWidth / 2f)
            val pivotY = cfg.float("card.body.pivotY", 540f)
            canvas.save()
            canvas.translate(0f, y)
            canvas.scale(scaleX, scaleY, pivotX, pivotY)
            drawCardBody(canvas, project.cards[index], x, spec, cfg, frame, index)
            canvas.restore()
        }
        if (project.creditsEnabled && frame in spec.openingStarts.firstOrNull().orZero() until spec.continuousStartFrame) {
            drawDisclaimer(canvas, frame, spec, cfg)
        }
        positions.forEach { (index, x) -> drawBadge(canvas, project, index, x, frame, spec, cfg) }
        positions.forEach { (index, x) ->
            if (project.cards[index].imageLayer.equals("front", true)) {
                val y = spec.track("card.$index.y", frame) ?: 0f
                val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
                val local = frame - entry
                val uniform = spec.track("card.$index.body.scale", frame)
                    ?: spec.track("relationships.card.body.scale", local)
                    ?: 1f
                val scaleX = spec.track("card.$index.body.scaleX", frame)
                    ?: spec.track("relationships.card.body.scaleX", local)
                    ?: uniform
                val scaleY = spec.track("card.$index.body.scaleY", frame)
                    ?: spec.track("relationships.card.body.scaleY", local)
                    ?: uniform
                val pivotX = x + cfg.float("card.body.pivotX", spec.bodyInset + spec.bodyWidth / 2f)
                val pivotY = cfg.float("card.body.pivotY", 540f)
                canvas.save()
                canvas.translate(0f, y)
                canvas.scale(scaleX, scaleY, pivotX, pivotY)
                drawFrontArtwork(canvas, project.cards[index], x, spec, cfg)
                canvas.restore()
            }
        }
    }
'''
new_content = '''    private fun drawContent(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
        cfg: ExactConfig,
        ledger: RenderPassLedger,
    ) {
        val positions = linkedMapOf<Int, Float>()
        if (frame < spec.continuousStartFrame) {
            val starts = spec.openingStarts
            for (index in 0 until min(4, project.cards.size)) {
                val start = starts.getOrElse(index) { starts.lastOrNull().orZero() + index * 140 }
                if (frame >= start) {
                    positions[index] = spec.track("card.$index.x", frame) ?: index * spec.slotPitch
                }
            }
        } else {
            val scroll = exactScroll(spec, frame) ?: ((frame - spec.continuousStartFrame) * 2f)
            project.cards.indices.forEach { index ->
                val baseX = index * spec.slotPitch - scroll
                val x = spec.track("card.$index.x", frame) ?: baseX
                if (x > -spec.slotPitch * 2f && x < 1920f + spec.slotPitch * 2f) positions[index] = x
            }
        }

        positions.forEach { (index, x) ->
            ledger.once("card.$index.body") {
                val y = spec.track("card.$index.y", frame) ?: 0f
                val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
                val local = frame - entry
                val uniform = spec.track("card.$index.body.scale", frame)
                    ?: spec.track("relationships.card.body.scale", local)
                    ?: 1f
                val scaleX = spec.track("card.$index.body.scaleX", frame)
                    ?: spec.track("relationships.card.body.scaleX", local)
                    ?: uniform
                val scaleY = spec.track("card.$index.body.scaleY", frame)
                    ?: spec.track("relationships.card.body.scaleY", local)
                    ?: uniform
                val pivotX = x + cfg.float("card.body.pivotX", spec.bodyInset + spec.bodyWidth / 2f)
                val pivotY = cfg.float("card.body.pivotY", 540f)
                canvas.save()
                canvas.translate(0f, y)
                canvas.scale(scaleX, scaleY, pivotX, pivotY)
                drawCardBody(canvas, project.cards[index], x, spec, cfg, frame, index)
                canvas.restore()
            }
        }
        if (project.creditsEnabled && frame in spec.openingStarts.firstOrNull().orZero() until spec.continuousStartFrame) {
            ledger.once("disclaimer") { drawDisclaimer(canvas, frame, spec, cfg) }
        }
        positions.forEach { (index, x) ->
            ledger.once("card.$index.badge") { drawBadge(canvas, project, index, x, frame, spec, cfg) }
        }
        positions.forEach { (index, x) ->
            if (project.cards[index].imageLayer.equals("front", true)) {
                ledger.once("card.$index.artwork.front") {
                    val y = spec.track("card.$index.y", frame) ?: 0f
                    val entry = RelationshipsTimeline.cardEntryFrame(project.cards.size, index, spec)
                    val local = frame - entry
                    val uniform = spec.track("card.$index.body.scale", frame)
                        ?: spec.track("relationships.card.body.scale", local)
                        ?: 1f
                    val scaleX = spec.track("card.$index.body.scaleX", frame)
                        ?: spec.track("relationships.card.body.scaleX", local)
                        ?: uniform
                    val scaleY = spec.track("card.$index.body.scaleY", frame)
                        ?: spec.track("relationships.card.body.scaleY", local)
                        ?: uniform
                    val pivotX = x + cfg.float("card.body.pivotX", spec.bodyInset + spec.bodyWidth / 2f)
                    val pivotY = cfg.float("card.body.pivotY", 540f)
                    canvas.save()
                    canvas.translate(0f, y)
                    canvas.scale(scaleX, scaleY, pivotX, pivotY)
                    drawFrontArtwork(canvas, project.cards[index], x, spec, cfg)
                    canvas.restore()
                }
            }
        }
    }
'''
replace_once(renderer, old_content, new_content)

replace_once(
    renderer,
    '''    private fun drawOutro(canvas: Canvas, project: StudioProject, frame: Int, contentEnd: Int, spec: RendererSpec, cfg: ExactConfig) {
        val local = frame - contentEnd
        val last = project.cards.last()
        val cardX = spec.track("relationships.outro.card.x", frame) ?: when {
            local < 80 -> lerp(320f, 781f, smooth(local / 80f))
            else -> 781f
        }
        drawCardBody(canvas, last, cardX, spec, cfg, frame, project.cards.lastIndex)
        drawBadge(canvas, project, project.cards.lastIndex, cardX, frame, spec, cfg)
''',
    '''    private fun drawOutro(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        contentEnd: Int,
        spec: RendererSpec,
        cfg: ExactConfig,
        ledger: RenderPassLedger,
    ) {
        val local = frame - contentEnd
        val last = project.cards.last()
        val lastIndex = project.cards.lastIndex
        val cardX = spec.track("relationships.outro.card.x", frame) ?: when {
            local < 80 -> lerp(320f, 781f, smooth(local / 80f))
            else -> 781f
        }
        ledger.once("card.$lastIndex.body") { drawCardBody(canvas, last, cardX, spec, cfg, frame, lastIndex) }
        ledger.once("card.$lastIndex.badge") { drawBadge(canvas, project, lastIndex, cardX, frame, spec, cfg) }
''',
)

replace_once(
    renderer,
    '''        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f)
        paint.resetForShape()
        if (Color.alpha(shadowColor) > 0 && shadowRadius > 0f) {
            paint.color = spec.badgeColor
            paint.setShadowLayer(shadowRadius, cfg.float("badge.shadow.dx", 0f), cfg.float("badge.shadow.dy", 0f), shadowColor)
            canvas.drawPath(path, paint)
            paint.clearShadowLayer()
        }
''',
    '''        val shadowColor = cfg.color("badge.shadow.color", Color.TRANSPARENT)
        val shadowRadius = cfg.float("badge.shadow.radius", 0f)
        if (Color.alpha(shadowColor) > 0 && shadowRadius > 0f) {
            // Shadow-only pass: never repaint the badge fill as part of a shadow.
            paint.resetForShape()
            paint.style = Paint.Style.FILL
            paint.color = shadowColor
            paint.maskFilter = BlurMaskFilter(shadowRadius, BlurMaskFilter.Blur.NORMAL)
            canvas.save()
            canvas.translate(cfg.float("badge.shadow.dx", 0f), cfg.float("badge.shadow.dy", 0f))
            canvas.drawPath(path, paint)
            canvas.restore()
            paint.maskFilter = null
        }
''',
)

bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(
    bundle,
    '''        "relationships-rich-typography",
        "preview-frames",''',
    '''        "relationships-rich-typography",
        "relationships-shadow-mask-v1",
        "relationships-single-owner-pass-v1",
        "preview-frames",''',
)

test = "android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt"
text = Path(test).read_text()
if "renderPassLedgerRejectsDuplicateLogicalElements" not in text:
    insert = '''

    @Test
    fun renderPassLedgerRejectsDuplicateLogicalElements() {
        val ledger = RenderPassLedger()
        assertTrue(ledger.claim("card.0.body"))
        assertFalse(ledger.claim("card.0.body"))
        assertTrue(ledger.claim("card.0.badge"))
        assertFalse(ledger.claim("card.0.badge"))
        assertTrue(ledger.claim("intro"))
        assertFalse(ledger.claim("intro"))
    }

    @Test
    fun rendererAdvertisesGlobalAntiDuplicationCapabilities() {
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-mask-v1"))
        assertTrue(RendererCapabilities.features.contains("relationships-single-owner-pass-v1"))
    }
'''
    if not text.rstrip().endswith('}'):
        raise SystemExit('test class closing brace not found')
    text = text.rstrip()[:-1] + insert + '\n}\n'
    Path(test).write_text(text)

doc = Path("docs/wiki/Relationships-Exact-v2.md")
if doc.exists():
    text = doc.read_text().rstrip()
    if "relationships-single-owner-pass-v1" not in text:
        text += '''\n\n### Renderer-wide anti-duplication guarantee\n\nExact-v2 builds advertise both `relationships-shadow-mask-v1` and `relationships-single-owner-pass-v1`. Every logical frame element has one owning paint pass: intro, footer, content, each card body, each badge, front artwork, disclaimer, and outro. Intentional layers inside a single element (for example a gradient plus stroke plus shine) remain part of that one owner. Badge shadows use a blur-mask-only pass so the source badge fill is never painted twice. Preview and export both execute the same precision renderer, so the invariant applies to both paths.\n'''
        doc.write_text(text + "\n")
