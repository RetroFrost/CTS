from pathlib import Path
import re

path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt')
text = path.read_text()


def sub(pattern: str, replacement: str, name: str, count: int = 1) -> None:
    global text
    updated, n = re.subn(pattern, replacement, text, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f'{name}: expected {count} replacement(s), got {n}')
    text = updated


sub(
    r'private fun motionTrack\(spec: RendererSpec, target: String, frame: Int\): Float\? \{\s*val centre = spec\.track\(target, frame\) \?: return null\s*val previous = spec\.track\(target, frame - 1\) \?: centre\s*val next = spec\.track\(target, frame \+ 1\) \?: centre\s*return previous \* 0\.20f \+ centre \* 0\.60f \+ next \* 0\.20f\s*\}',
    '''private fun motionTrack(spec: RendererSpec, target: String, frame: Int): Float? {
    val centre = spec.track(target, frame) ?: return null
    if (spec.precisionMode == "frame-exact") return centre
    val previous = spec.track(target, frame - 1) ?: centre
    val next = spec.track(target, frame + 1) ?: centre
    return previous * 0.20f + centre * 0.60f + next * 0.20f
}''',
    'frame-exact smoothing removal',
)

sub(
    r'val local = frame - RibbonTimeline\.cardStartFrame\(project, spec, active\)\s*val progress = bodyProgress\(spec, local\)\s*result\[active\] = if \(active == 0\) \{\s*lerp\(-spec\.slotPitch, 0f, progress\)\s*\} else \{\s*lerp\(\(active - 1\) \* spec\.slotPitch, active \* spec\.slotPitch, progress\)\s*\}',
    '''val local = frame - RibbonTimeline.cardStartFrame(project, spec, active)
        val exactX = motionTrack(spec, "ribbon.open.$active.card.x", local)
        val progress = bodyProgress(spec, local)
        result[active] = exactX ?: if (active == 0) {
            lerp(-spec.slotPitch, 0f, progress)
        } else {
            lerp((active - 1) * spec.slotPitch, active * spec.slotPitch, progress)
        }''',
    'per-opening-card X',
)

sub(
    r'val x = when \(active\) \{\s*0 -> lerp\(REFERENCE_WIDTH\.toFloat\(\), 1440f, p\)\s*1, 2 -> 1440f\s*3 -> lerp\(1440f, REFERENCE_WIDTH\.toFloat\(\), p\)\s*else -> return\s*\}',
    '''val x = motionTrack(spec, "ribbon.credits.x", frame) ?: when (active) {
            0 -> lerp(REFERENCE_WIDTH.toFloat(), 1440f, p)
            1, 2 -> 1440f
            3 -> lerp(1440f, REFERENCE_WIDTH.toFloat(), p)
            else -> return
        }''',
    'credits X track',
)

old_blurb = '"The values presented are estimates\\nfrom publicly available\\nsources. Individual results may\\nvary depending\\non concentration, temperature,\\nexposure time, and\\nother factors. Do not attempt\\nany experiments.",'
new_blurb = 'tagText(spec, "ribbon.credits.blurb", "The values presented are estimates\\nfrom publicly available\\nsources. Individual results may\\nvary depending\\non concentration, temperature,\\nexposure time, and\\nother factors. Do not attempt\\nany experiments."),'
if old_blurb not in text:
    raise SystemExit('credits blurb replacement target not found')
text = text.replace(old_blurb, new_blurb, 1)

old_y = 'matrix.setTranslate(0f, motionTrack(spec, "ribbon.later.badge.y", local) ?: 0f)'
new_y = 'matrix.setTranslate(0f, motionTrack(spec, "ribbon.card.$index.badge.y", local) ?: motionTrack(spec, "ribbon.later.badge.y", local) ?: 0f)'
if old_y not in text:
    raise SystemExit('per-card badge Y replacement target not found')
text = text.replace(old_y, new_y, 1)

old_scale = 'val stageScale = badgeDeemphasisScale(project, index, globalFrame, spec) * spec.badgeScale'
new_scale = 'val stageScale = (motionTrack(spec, "ribbon.card.$index.badge.scale", local) ?: badgeDeemphasisScale(project, index, globalFrame, spec)) * spec.badgeScale'
if old_scale not in text:
    raise SystemExit('per-card badge scale replacement target not found')
text = text.replace(old_scale, new_scale, 1)

sub(
    r'val exactProgress = if \(index < 4\) motionTrack\(spec, "ribbon\.open\.\$index\.shine\.progress", local\) else null\s*val exactAlpha = if \(index < 4\) motionTrack\(spec, "ribbon\.open\.\$index\.shine\.alpha", local\) else null',
    '''val exactProgress = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.progress", local)
        } else {
            motionTrack(spec, "ribbon.card.$index.shine.progress", local) ?: motionTrack(spec, "ribbon.later.shine.progress", local)
        }
        val exactAlpha = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.alpha", local)
        } else {
            motionTrack(spec, "ribbon.card.$index.shine.alpha", local) ?: motionTrack(spec, "ribbon.later.shine.alpha", local)
        }''',
    'per-card exact shine',
)

marker = '    private fun interpolatePoint(local: Int, start: Int, end: Int, x0: Float, y0: Float, x1: Float, y1: Float): Pair<Float, Float> {'
helper = '''    private fun tagText(spec: RendererSpec, key: String, fallback: String): String =
        spec.tags.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.replace("\\\\n", "\\n")
            ?: fallback

'''
if marker not in text:
    raise SystemExit('tagText insertion marker not found')
text = text.replace(marker, helper + marker, 1)

path.write_text(text)
