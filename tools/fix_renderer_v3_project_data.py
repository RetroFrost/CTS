#!/usr/bin/env python3
"""Make Renderer v3 motion scenes obey the active StudioProject data.

This is intentionally a runtime fix, not a source-video rewrite. The scene remains
responsible for measured frame timing, transforms, clipping, badge geometry, shine
and layer order. Card content is composed from StudioProject at render time.

The legacy Puberty 1.4 package is explicitly migrated in-place by ID, so users who
already installed it stop seeing the baked source cards after updating the app. New
packages can opt in with the project-card-data capability.
"""
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
# RendererV3FrameRenderer.kt: route source slots to live project compositing.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererV3FrameRenderer.kt"
text = path.read_text()

old_render_loop = '''        val objects = orderedObjects(scene)
        objects.forEach { obj ->
            if (frame !in obj.lifespanStart..obj.lifespanEnd) return@forEach
            val props = RendererV3Evaluator.properties(scene, obj, frame)
            if (!truthy(props["visible"], true)) return@forEach
            drawObject(scene, project, obj, props, frame, canvas)
        }
        return out
'''
new_render_loop = '''        val objects = orderedObjects(scene)
        objects.forEach { obj ->
            if (frame !in obj.lifespanStart..obj.lifespanEnd) return@forEach
            if (!RendererV3ProjectData.shouldRender(scene, project, obj)) return@forEach
            val props = RendererV3Evaluator.properties(scene, obj, frame)
            if (!truthy(props["visible"], true)) return@forEach
            drawObject(scene, project, obj, props, frame, canvas)
        }
        RendererV3ProjectData.drawEndFade(scene, project, frame, canvas)
        return out
'''
text = replace_once(text, old_render_loop, new_render_loop, "project-data render gate")

ordered_anchor = '''    private fun orderedObjects(scene: RendererV3Scene): List<RendererV3Object> {
'''
timeline_helper = '''    fun timelineFrameCount(project: StudioProject, spec: RendererSpec): Int {
        val scene = RendererV3Runtime.scene(spec) ?: return spec.canonicalFrameCount.coerceAtLeast(1)
        return RendererV3ProjectData.timelineFrameCount(scene, project)
    }

'''
if timeline_helper not in text:
    if text.count(ordered_anchor) != 1:
        raise SystemExit("timelineFrameCount insertion anchor changed")
    text = text.replace(ordered_anchor, timeline_helper + ordered_anchor, 1)

old_dispatch = '''            when (type) {
                "polygon", "path", "shadow", "shine", "material", "filter", "custom" ->
                    drawPolygonLike(resource, boundProps, opacity, canvas)
                "rect" -> drawRect(resource, boundProps, opacity, canvas)
                "ellipse" -> drawEllipse(resource, boundProps, opacity, canvas)
                "image" -> drawImage(scene, project, resource, boundProps, opacity, canvas)
                "text" -> drawText(resource, boundProps, opacity, canvas)
                "text-raster", "source-text-raster" ->
                    drawSourceBakedTextRaster(scene, resource, boundProps, opacity, canvas)
                "independent-shadow" ->
                    drawIndependentShadow(scene, project, resource, boundProps, opacity, frame, canvas)
                "outro-overlay", "exact-outro-overlay" ->
                    drawExactOutroOverlay(scene, resource, boundProps, opacity, frame, canvas)
                "group" -> drawResourceGroup(scene, project, obj, resource, boundProps, opacity, frame, canvas)
                else -> drawPolygonLike(resource, boundProps, opacity, canvas)
            }
'''
new_dispatch = '''            when {
                type == "project-card" ||
                    (RendererV3ProjectData.enabled(scene) && RendererV3ProjectData.isCardBody(obj)) ->
                    RendererV3ProjectData.drawCard(project, obj, resource, opacity, canvas)
                type == "project-badge-text" ||
                    (RendererV3ProjectData.enabled(scene) && RendererV3ProjectData.isBadgeText(obj)) ->
                    RendererV3ProjectData.drawBadgeText(project, obj, resource, boundProps, opacity, canvas)
                else -> when (type) {
                    "polygon", "path", "shadow", "shine", "material", "filter", "custom" ->
                        drawPolygonLike(resource, boundProps, opacity, canvas)
                    "rect" -> drawRect(resource, boundProps, opacity, canvas)
                    "ellipse" -> drawEllipse(resource, boundProps, opacity, canvas)
                    "image" -> drawImage(scene, project, resource, boundProps, opacity, canvas)
                    "text" -> drawText(resource, boundProps, opacity, canvas)
                    "text-raster", "source-text-raster" ->
                        drawSourceBakedTextRaster(scene, resource, boundProps, opacity, canvas)
                    "independent-shadow" ->
                        drawIndependentShadow(scene, project, resource, boundProps, opacity, frame, canvas)
                    "outro-overlay", "exact-outro-overlay" ->
                        drawExactOutroOverlay(scene, resource, boundProps, opacity, frame, canvas)
                    "group" -> drawResourceGroup(scene, project, obj, resource, boundProps, opacity, frame, canvas)
                    else -> drawPolygonLike(resource, boundProps, opacity, canvas)
                }
            }
'''
text = replace_once(text, old_dispatch, new_dispatch, "project-data object dispatcher")

old_index = '''        val explicitIndex = when {
            obj.raw.has("cardIndex") -> obj.raw.optInt("cardIndex", -1)
            obj.raw.has("dataIndex") -> obj.raw.optInt("dataIndex", -1)
            else -> -1
        }
'''
new_index = '''        val explicitIndex = RendererV3ProjectData.cardIndex(obj) ?: -1
'''
text = replace_once(text, old_index, new_index, "project binding card-index inference")

path.write_text(text)


# ---------------------------------------------------------------------------
# RendererBridge.kt: automatic duration follows the active project card count.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBridge.kt"
text = path.read_text()
text = replace_once(
    text,
    '            "scene-v3" -> RendererV3Runtime.scene(spec)?.timeline?.frames ?: spec.canonicalFrameCount\n',
    '            "scene-v3" -> rendererV3.timelineFrameCount(project, spec)\n',
    "Renderer v3 dynamic frame count",
)
path.write_text(text)


# ---------------------------------------------------------------------------
# RendererBundle.kt: advertise the data-binding contract to package validation.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
feature_anchor = '        "renderer-v3-zip-package",\n'
feature_line = '        "project-card-data",\n'
if feature_line not in text:
    if text.count(feature_anchor) != 1:
        raise SystemExit("Renderer capability insertion anchor changed")
    text = text.replace(feature_anchor, feature_anchor + feature_line, 1)
path.write_text(text)


# ---------------------------------------------------------------------------
# RendererProjectGuard.kt: variable card counts are valid up to the measured model's
# capacity; never silently ignore overflow cards.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererProjectGuard.kt"
text = path.read_text()
text = text.replace(
    '        "$rendererName is source-locked and cannot be applied to this project: ${issues.joinToString()}."\n',
    '        "$rendererName cannot be applied to this project: ${issues.joinToString()}."\n',
    1,
)
old_guard = '''    fun check(project: StudioProject, spec: RendererSpec): RendererProjectCompatibility {
        if (!isSourceLocked(spec)) return RendererProjectCompatibility(true, emptyList())

        val issues = mutableListOf<String>()
'''
new_guard = '''    fun check(project: StudioProject, spec: RendererSpec): RendererProjectCompatibility {
        if (RendererV3ProjectData.enabled(spec)) {
            val issues = mutableListOf<String>()
            if (spec.canonicalCardCount > 0 && project.cards.size > spec.canonicalCardCount) {
                issues += "card count is ${project.cards.size}; this measured motion model supports at most ${spec.canonicalCardCount}"
            }
            return RendererProjectCompatibility(issues.isEmpty(), issues)
        }
        if (!isSourceLocked(spec)) return RendererProjectCompatibility(true, emptyList())

        val issues = mutableListOf<String>()
'''
text = replace_once(text, old_guard, new_guard, "project-data compatibility guard")
path.write_text(text)


# ---------------------------------------------------------------------------
# Compatibility test: the application must acknowledge the new package feature.
# ---------------------------------------------------------------------------
path = ROOT / "android/app/src/test/java/io/github/retrofrost/cts/android/RendererV3CompatibilityTest.kt"
text = path.read_text()
feature_test_anchor = '                "preview-export-identical-path",\n'
feature_test_line = '                "project-card-data",\n'
if feature_test_line not in text:
    if text.count(feature_test_anchor) != 1:
        raise SystemExit("Renderer v3 compatibility test insertion anchor changed")
    text = text.replace(feature_test_anchor, feature_test_anchor + feature_test_line, 1)
path.write_text(text)

print("Renderer v3 project-data runtime wired: live cards, dynamic duration, no baked-content fallback")
