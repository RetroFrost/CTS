from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
renderer = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RelationshipsPrecisionFrameRenderer.kt"
text = renderer.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Patch point not found: {label}")
    text = text.replace(old, new, 1)


# The opening source card scales uniformly around its centre before reaching 1.0.
# Keep separate X/Y tracks as well so a measured source is never forced to be uniform.
replace_once(
    '''        positions.forEach { (index, x) ->
            val y = spec.track("card.$index.y", frame) ?: 0f
            canvas.save(); canvas.translate(0f, y)
            drawCardBody(canvas, project.cards[index], x, spec, cfg, frame, index)
            canvas.restore()
        }
''',
    '''        positions.forEach { (index, x) ->
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
''',
    "opening body scale",
)

replace_once(
    '''        positions.forEach { (index, x) ->
            if (project.cards[index].imageLayer.equals("front", true)) drawFrontArtwork(canvas, project.cards[index], x, spec, cfg)
        }
''',
    '''        positions.forEach { (index, x) ->
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
''',
    "front artwork body scale",
)

# The source disclaimer has a subtly graded dark panel and a thin separator at its leading edge.
replace_once(
    '''        paint.resetForShape(); paint.color = withAlpha(cfg.color("disclaimer.background", Color.rgb(22, 22, 22)), alpha)
        canvas.drawRect(x, 0f, 1920f, 1080f, paint)
''',
    '''        paint.resetForShape()
        val background = withAlpha(cfg.color("disclaimer.background", Color.rgb(22, 22, 22)), alpha)
        val gradientStart = withAlpha(cfg.color("disclaimer.gradient.startColor", background), alpha)
        val gradientEnd = withAlpha(cfg.color("disclaimer.gradient.endColor", background), alpha)
        if (cfg.has("disclaimer.gradient.startColor") || cfg.has("disclaimer.gradient.endColor")) {
            paint.shader = LinearGradient(
                cfg.float("disclaimer.gradient.startX", x),
                cfg.float("disclaimer.gradient.startY", 0f),
                cfg.float("disclaimer.gradient.endX", 1920f),
                cfg.float("disclaimer.gradient.endY", 0f),
                gradientStart,
                gradientEnd,
                Shader.TileMode.CLAMP,
            )
        } else {
            paint.color = background
        }
        canvas.drawRect(x, 0f, 1920f, 1080f, paint)
        paint.shader = null
        val borderWidth = cfg.float("disclaimer.border.width", 0f)
        if (borderWidth > 0f) {
            paint.resetForShape()
            paint.color = withAlpha(cfg.color("disclaimer.border.color", Color.rgb(74, 74, 74)), alpha)
            canvas.drawRect(x, 0f, x + borderWidth, 1080f, paint)
        }
''',
    "disclaimer gradient and separator",
)

# Allow the badge shine to have measured soft/feathered edges instead of forcing a hard solid strip.
replace_once(
    '''        canvas.save(); canvas.clipPath(badgePath)
        paint.resetForShape(); paint.color = color
        canvas.drawPath(shine, paint)
        canvas.restore()
''',
    '''        canvas.save(); canvas.clipPath(badgePath)
        paint.resetForShape(); paint.color = color
        val feather = cfg.float("badge.shine.feather", 0f).coerceIn(0f, 0.49f)
        if (feather > 0f) {
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            paint.shader = LinearGradient(
                x - width / 2f,
                cfg.float("badge.shine.gradientStartY", 0f),
                x + width / 2f,
                cfg.float("badge.shine.gradientEndY", 0f),
                intArrayOf(transparent, color, color, transparent),
                floatArrayOf(0f, feather, 1f - feather, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(shine, paint)
        paint.shader = null
        canvas.restore()
''',
    "feathered badge shine",
)

renderer.write_text(text)
print("Relationships exact-v2 measured extras applied.")
