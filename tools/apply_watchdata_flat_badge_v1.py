#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"
FEATURE = "ribbon-flat-badge-polygon-v1"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# A source-exact Ribbon renderer must be allowed to own the actual flat badge
# silhouette.  The old engine hard-coded one six-point polygon and then tried
# to compensate with scale; that cannot reproduce WatchData references whose
# top/bottom vertices and side shoulders differ by several source pixels.
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
if f'"{FEATURE}"' not in text:
    markers = (
        '        "ribbon-glass-shine-v1",\n',
        '        "ribbon-text-wipe-v1",\n',
        '        "ribbon-artwork-region-v1",\n',
        '        "artwork-transform",\n',
    )
    marker = next((value for value in markers if value in text), None)
    if marker is None:
        raise SystemExit("renderer feature insertion marker changed")
    text = text.replace(marker, marker + f'        "{FEATURE}",\n', 1)
path.write_text(text)


path = ANDROID / "RibbonFrameRenderer.kt"
text = path.read_text()

old_path = '''        val path = if (index >= 4 && spec.tags.contains("puberty-badge-source-lock-v3")) pubertyLaterBadgePath else badgePath
'''
new_path = '''        val fallbackPath = if (index >= 4 && spec.tags.contains("puberty-badge-source-lock-v3")) pubertyLaterBadgePath else badgePath
        val path = rendererBadgePath(spec, fallbackPath)
'''
text = replace_once(text, old_path, new_path, "renderer-owned flat badge path")

# Keep the badge face completely flat.  Only the separate cast shadow and the
# transient shine may alter apparent brightness.  The renderer may also own the
# tiny source outline instead of being forced to use the legacy 2 px / 145 alpha.
text = replace_once(
    text,
    '''        paint.strokeWidth = 2f
        paint.color = Color.argb(
            145,
''',
    '''        paint.strokeWidth = rendererFloatTag(spec, "ribbon.badge.outline.width") ?: 2f
        paint.color = Color.argb(
            (rendererFloatTag(spec, "ribbon.badge.outline.alpha") ?: 145f).roundToInt().coerceIn(0, 255),
''',
    "renderer-owned badge outline",
)

helper_marker = '''    private fun valueLines(value: String): List<String> {'''
helper = '''    private fun rendererFloatTag(spec: RendererSpec, key: String): Float? =
        spec.tags.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.toFloatOrNull()

    private fun rendererBadgePath(spec: RendererSpec, fallback: Path): Path {
        if ("ribbon-flat-badge-polygon-v1" !in spec.requiredFeatures) return fallback
        val points = (0 until 6).map { index ->
            val x = rendererFloatTag(spec, "ribbon.badge.p$index.x") ?: return fallback
            val y = rendererFloatTag(spec, "ribbon.badge.p$index.y") ?: return fallback
            x to y
        }
        return Path().apply {
            moveTo(points[0].first, points[0].second)
            for (index in 1 until points.size) lineTo(points[index].first, points[index].second)
            close()
        }
    }

'''
if helper not in text:
    if text.count(helper_marker) != 1:
        raise SystemExit("renderer badge helper insertion marker changed")
    text = text.replace(helper_marker, helper + helper_marker, 1)
path.write_text(text)

print("Added renderer-owned flat Ribbon badge polygon and source outline controls")
