#!/usr/bin/env python3
"""Add Cubical Compare project/card data binding to Renderer API v3 objects."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android/RendererV3FrameRenderer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


text = PATH.read_text()

# File-path project images are common after Cubical Compare materializes imports.
if 'import java.io.File\n' not in text:
    text = text.replace('import java.io.ByteArrayInputStream\n', 'import java.io.ByteArrayInputStream\nimport java.io.File\n', 1)

old = '''        val resource = scene.resource(obj.resource) ?: JSONObject().put("type", obj.kind)
        val type = resource.optString("type", obj.kind).lowercase()
        val opacity = number(props["opacity"], number(props["material.alpha"], 1.0)).toFloat().coerceIn(0f, 1f)
        if (opacity <= 0.0001f) return

        canvas.save()
        runCatching {
            clipMask(scene, props, canvas)
            applyObjectTransform(props, canvas)
            when (type) {
                "polygon", "path", "shadow", "shine", "material", "filter", "custom" ->
                    drawPolygonLike(resource, props, opacity, canvas)
                "rect" -> drawRect(resource, props, opacity, canvas)
                "ellipse" -> drawEllipse(resource, props, opacity, canvas)
                "image" -> drawImage(scene, project, resource, props, opacity, canvas)
                "text" -> drawText(resource, props, opacity, canvas)
                "group" -> Unit // Children are ordinary scene objects with explicit layer order.
                else -> drawPolygonLike(resource, props, opacity, canvas)
            }
        }
'''
new = '''        val resource = scene.resource(obj.resource) ?: JSONObject().put("type", obj.kind)
        val type = resource.optString("type", obj.kind).lowercase()
        val boundProps = props.mapValues { (_, value) -> bindProjectValue(value, project, obj) }
        val opacity = number(boundProps["opacity"], number(boundProps["material.alpha"], 1.0)).toFloat().coerceIn(0f, 1f)
        if (opacity <= 0.0001f) return

        canvas.save()
        runCatching {
            clipMask(scene, boundProps, canvas)
            applyObjectTransform(boundProps, canvas)
            when (type) {
                "polygon", "path", "shadow", "shine", "material", "filter", "custom" ->
                    drawPolygonLike(resource, boundProps, opacity, canvas)
                "rect" -> drawRect(resource, boundProps, opacity, canvas)
                "ellipse" -> drawEllipse(resource, boundProps, opacity, canvas)
                "image" -> drawImage(scene, project, resource, boundProps, opacity, canvas)
                "text" -> drawText(resource, boundProps, opacity, canvas)
                "group" -> drawResourceGroup(scene, project, obj, resource, boundProps, opacity, frame, canvas)
                else -> drawPolygonLike(resource, boundProps, opacity, canvas)
            }
        }
'''
text = replace_once(text, old, new, "v3 project property binding")

# Add a usable resource-group implementation. Children inherit the parent's
# cardIndex/dataIndex, so `$card.*` bindings work inside reusable group resources.
helper_anchor = '''    private fun drawPolygonLike(resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
'''
helper = '''    private fun drawResourceGroup(
        scene: RendererV3Scene,
        project: StudioProject,
        bindingObject: RendererV3Object,
        resource: JSONObject,
        props: Map<String, Any?>,
        opacity: Float,
        frame: Int,
        canvas: Canvas,
    ) {
        val children = resource.optJSONArray("children") ?: return
        repeat(children.length()) { index ->
            val childId = children.optString(index)
            val child = scene.resource(childId) ?: return@repeat
            val childType = child.optString("type", "custom").lowercase()
            val defaults = RendererV3Evaluator.flatten(child.optJSONObject("properties") ?: JSONObject())
                .mapValues { (_, value) -> bindProjectValue(value, project, bindingObject) }
            canvas.save()
            applyObjectTransform(defaults, canvas)
            when (childType) {
                "polygon", "path", "shadow", "shine", "material", "filter", "custom" -> drawPolygonLike(child, defaults, opacity, canvas)
                "rect" -> drawRect(child, defaults, opacity, canvas)
                "ellipse" -> drawEllipse(child, defaults, opacity, canvas)
                "image" -> drawImage(scene, project, child, defaults, opacity, canvas)
                "text" -> drawText(child, defaults, opacity, canvas)
                "group" -> drawResourceGroup(scene, project, bindingObject, child, defaults, opacity, frame, canvas)
            }
            canvas.restore()
        }
    }

'''
if helper not in text:
    if text.count(helper_anchor) != 1:
        raise SystemExit("v3 resource-group insertion anchor changed")
    text = text.replace(helper_anchor, helper + helper_anchor, 1)

# Perspective/homography is part of the v3 compiler contract, in addition to affine.
old_transform = '''    private fun applyObjectTransform(props: Map<String, Any?>, canvas: Canvas) {
        val affine = numberList(props["transform.affine"])
        if (affine != null && affine.size == 6) {
'''
new_transform = '''    private fun applyObjectTransform(props: Map<String, Any?>, canvas: Canvas) {
        val homography = numberList(props["transform.homography"])
        if (homography != null && homography.size == 9) {
            canvas.concat(Matrix().apply { setValues(homography.toFloatArray()) })
        }
        val affine = numberList(props["transform.affine"])
        if (affine != null && affine.size == 6) {
'''
text = replace_once(text, old_transform, new_transform, "v3 homography transform")

# Decode project card images that resolve to materialized local file paths.
old_decode = '''        val bytes = when {
            inline != null -> runCatching { Base64.getDecoder().decode(inline) }.getOrNull()
            source != null -> scene.asset(source)
            else -> null
        } ?: return null
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes))?.also { bitmapCache[cacheKey] = it }
'''
new_decode = '''        if (source != null) {
            val local = File(source)
            if (local.isFile) {
                return BitmapFactory.decodeFile(local.absolutePath)?.also { bitmapCache[cacheKey] = it }
            }
        }
        val bytes = when {
            inline != null -> runCatching { Base64.getDecoder().decode(inline) }.getOrNull()
            source != null -> scene.asset(source)
            else -> null
        } ?: return null
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes))?.also { bitmapCache[cacheKey] = it }
'''
text = replace_once(text, old_decode, new_decode, "v3 project image file binding")

binding_anchor = '''    private fun points(value: Any?): List<Pair<Float, Float>>? {
'''
binding_helpers = '''    /**
     * Bind renderer values to the current Cubical Compare project. Objects opt into a
     * card with `cardIndex` (or `dataIndex`) and can then use values such as
     * `$card.title`, `$card.value`, `$card.badgeHeader`, `$card.description`,
     * `$card.image`, `$card.imageX`, `$card.imageY`, `$card.imageScale`,
     * `$card.imageRotation`, and `$card.index`. Project values use `$project.*`.
     */
    private fun bindProjectValue(value: Any?, project: StudioProject, obj: RendererV3Object): Any? = when (value) {
        is List<*> -> value.map { bindProjectValue(it, project, obj) }
        is String -> projectBinding(value, project, obj) ?: value
        else -> value
    }

    private fun projectBinding(token: String, project: StudioProject, obj: RendererV3Object): Any? {
        if (!token.startsWith("$")) return null
        if (token.startsWith("$project.")) {
            return when (token.removePrefix("$project.")) {
                "name" -> project.name
                "width" -> project.width
                "height" -> project.height
                "fps" -> project.fps
                "showBadges" -> project.showBadges
                "creditsEnabled" -> project.creditsEnabled
                "fontFamily" -> project.fontFamily
                "fontFile" -> project.fontFile
                else -> null
            }
        }
        if (!token.startsWith("$card.")) return null
        val explicitIndex = when {
            obj.raw.has("cardIndex") -> obj.raw.optInt("cardIndex", -1)
            obj.raw.has("dataIndex") -> obj.raw.optInt("dataIndex", -1)
            else -> -1
        }
        if (explicitIndex !in project.cards.indices) return null
        val card = project.cards[explicitIndex]
        return when (token.removePrefix("$card.")) {
            "index" -> explicitIndex
            "id" -> card.id
            "title" -> card.title
            "value" -> card.value
            "badgeHeader" -> card.badgeHeader
            "description" -> card.description
            "image" -> card.image
            "imageX" -> card.imageX
            "imageY" -> card.imageY
            "imageScale" -> card.imageScale
            "imageRotation" -> card.imageRotation
            "imageCropLeft" -> card.imageCropLeft
            "imageCropTop" -> card.imageCropTop
            "imageCropRight" -> card.imageCropRight
            "imageCropBottom" -> card.imageCropBottom
            "imageLayer" -> card.imageLayer
            else -> null
        }
    }

'''
if binding_helpers not in text:
    if text.count(binding_anchor) != 1:
        raise SystemExit("v3 project-binding helper anchor changed")
    text = text.replace(binding_anchor, binding_helpers + binding_anchor, 1)

# Kotlin string templates require a backslash for literal renderer binding tokens.
text = text.replace('token.startsWith("$")', r'token.startsWith("\$")')
text = text.replace('token.startsWith("$project.")', r'token.startsWith("\$project.")')
text = text.replace('token.removePrefix("$project.")', r'token.removePrefix("\$project.")')
text = text.replace('token.startsWith("$card.")', r'token.startsWith("\$card.")')
text = text.replace('token.removePrefix("$card.")', r'token.removePrefix("\$card.")')

PATH.write_text(text)
print("Applied Renderer API v3 project/card bindings, inheritable groups, homography and project images")
