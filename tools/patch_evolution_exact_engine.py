from pathlib import Path

p = Path('android/app/src/main/java/io/github/retrofrost/cts/android/RibbonFrameRenderer.kt')
s = p.read_text(encoding='utf-8')

old = '''private fun motionTrack(spec: RendererSpec, target: String, frame: Int): Float? {
    val centre = spec.track(target, frame) ?: return null
    val previous = spec.track(target, frame - 1) ?: centre
    val next = spec.track(target, frame + 1) ?: centre
    return previous * 0.20f + centre * 0.60f + next * 0.20f
}'''
new = '''private fun motionTrack(spec: RendererSpec, target: String, frame: Int): Float? {
    val centre = spec.track(target, frame) ?: return null
    // Source-measured frame tracks must be consumed verbatim. Filtering them here
    // changes the measured motion and makes an exact bundle non-exact.
    if (spec.tags.contains("ribbon-raw-frame-tracks-v1")) return centre
    val previous = spec.track(target, frame - 1) ?: centre
    val next = spec.track(target, frame + 1) ?: centre
    return previous * 0.20f + centre * 0.60f + next * 0.20f
}

private fun rendererTag(spec: RendererSpec, key: String): String? =
    spec.tags.firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')
        ?.replace("\\\\n", "\\n")'''
assert old in s, 'motionTrack block not found'
s = s.replace(old, new, 1)

old = '''            "The values presented are estimates\\nfrom publicly available\\nsources. Individual results may\\nvary depending\\non concentration, temperature,\\nexposure time, and\\nother factors. Do not attempt\\nany experiments.",'''
new = '''            rendererTag(spec, "ribbon.credits.blurb")
                ?: "The values presented are estimates\\nfrom publicly available\\nsources. Individual results may\\nvary depending\\non concentration, temperature,\\nexposure time, and\\nother factors. Do not attempt\\nany experiments.",'''
assert old in s, 'credits blurb block not found'
s = s.replace(old, new, 1)

old = '''        if (index < 4) {
            if (local < OPENING_BADGE_FIRST_FRAME) return
            age = ((local.coerceAtMost(OPENING_BADGE_FINAL_FRAME) - OPENING_BADGE_FIRST_FRAME).toFloat() /
                (OPENING_BADGE_FINAL_FRAME - OPENING_BADGE_FIRST_FRAME)) * BADGE_ENTRY_AGE

            // Corrected hand-dissolve bundles provide a different measured affine'''
new = '''        if (index < 4) {
            val firstFrame = spec.track("ribbon.open.$index.entry.first", 0)?.roundToInt() ?: OPENING_BADGE_FIRST_FRAME
            val finalFrame = spec.track("ribbon.open.$index.entry.final", 0)?.roundToInt() ?: OPENING_BADGE_FINAL_FRAME
            if (local < firstFrame) return
            age = ((local.coerceAtMost(finalFrame) - firstFrame).toFloat() /
                (finalFrame - firstFrame).coerceAtLeast(1)) * BADGE_ENTRY_AGE

            // Exact bundles can provide a different measured affine'''
assert old in s, 'opening badge block not found'
s = s.replace(old, new, 1)

old = '''        val exactProgress = if (index < 4) motionTrack(spec, "ribbon.open.$index.shine.progress", local) else null
        val exactAlpha = if (index < 4) motionTrack(spec, "ribbon.open.$index.shine.alpha", local) else null'''
new = '''        val exactProgress = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.progress", local)
        } else {
            motionTrack(spec, "ribbon.later.shine.progress", local)
        }
        val exactAlpha = if (index < 4) {
            motionTrack(spec, "ribbon.open.$index.shine.alpha", local)
        } else {
            motionTrack(spec, "ribbon.later.shine.alpha", local)
        }'''
assert old in s, 'shine block not found'
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('patched', p)
