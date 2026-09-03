#!/usr/bin/env python3
"""Add a generic animated rectangular clip primitive to Renderer API v3.

Puberty's opening cards do not scale in from the left: their final geometry is
already in place and a left-to-right clip reveals it.  A tracked clip rectangle
keeps that behaviour declarative and useful for other renderers too.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"
RB = ANDROID / "RendererBundle.kt"
V3R = ANDROID / "RendererV3FrameRenderer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# Advertise the primitive only in builds that actually implement it.
text = RB.read_text()
marker = '        "arbitrary-masks",\n'
addition = marker + '        "animated-rect-clip",\n'
if '"animated-rect-clip"' not in text:
    if marker not in text:
        raise SystemExit("Renderer capability insertion marker changed")
    text = text.replace(marker, addition, 1)
RB.write_text(text)


text = V3R.read_text()
old = '''    private fun clipMask(scene: RendererV3Scene, props: Map<String, Any?>, canvas: Canvas) {
        val id = stringValue(props["mask"] ?: props["mask.resource"]) ?: return
        val mask = scene.resource(id) ?: return
        val pts = points(jsonValue(mask.opt("points"))) ?: return
        if (pts.size < 3) return
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
            close()
        }
        canvas.clipPath(path)
    }
'''
new = '''    private fun clipMask(scene: RendererV3Scene, props: Map<String, Any?>, canvas: Canvas) {
        // Tracked rectangular clips are evaluated by RendererV3Evaluator just like
        // any other property, so a dense/raw clip.width produces a true frame-exact
        // wipe instead of scaling the contents to a smaller destination rectangle.
        val clipWidth = (props["clip.width"] as? Number)?.toFloat()
        val clipHeight = (props["clip.height"] as? Number)?.toFloat()
        if (clipWidth != null || clipHeight != null) {
            val x = number(props["clip.x"], 0.0).toFloat()
            val y = number(props["clip.y"], 0.0).toFloat()
            val width = clipWidth ?: scene.canvas.width.toFloat()
            val height = clipHeight ?: scene.canvas.height.toFloat()
            if (width <= 0f || height <= 0f) {
                canvas.clipRect(0f, 0f, 0f, 0f)
                return
            }
            canvas.clipRect(x, y, x + width, y + height)
        }

        val id = stringValue(props["mask"] ?: props["mask.resource"]) ?: return
        val mask = scene.resource(id) ?: return
        val pts = points(jsonValue(mask.opt("points"))) ?: return
        if (pts.size < 3) return
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) lineTo(pts[i].first, pts[i].second)
            close()
        }
        canvas.clipPath(path)
    }
'''
text = replace_once(text, old, new, "Renderer v3 animated rectangular clip")
V3R.write_text(text)
print("Added Renderer v3 animated rectangular clip tracks")
