#!/usr/bin/env python3
"""Make the Renderer v3 capability strings correspond to real runtime features.

Adds dedicated source-baked text raster rendering, independently layered shadow
resources, exact frame-addressed outro overlays, strict feature-contract validation,
and fail-loud object rendering. This patch runs after the other Renderer v3 patches.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"
V3 = ANDROID / "RendererV3.kt"
V3R = ANDROID / "RendererV3FrameRenderer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Parse-time feature contracts. A capability is not accepted just because its
# string appears in the manifest: its dedicated resource/track structure must
# actually exist and be valid.
# ---------------------------------------------------------------------------
text = V3.read_text()

validation_anchor = '''        val checkpoints = buildList {
            repeat(checkpointsJson.length()) { index ->
                val item = checkpointsJson.opt(index)
                when (item) {
                    is Number -> add(item.toInt())
                    is JSONObject -> add(item.optInt("frame", -1))
                }
            }
        }.filter { it in 0 until frames }

        return RendererV3Scene(
'''
validation_insert = '''        val checkpoints = buildList {
            repeat(checkpointsJson.length()) { index ->
                val item = checkpointsJson.opt(index)
                when (item) {
                    is Number -> add(item.toInt())
                    is JSONObject -> add(item.optInt("frame", -1))
                }
            }
        }.filter { it in 0 until frames }

        validateDedicatedFeatureContracts(
            root = root,
            features = features.distinct(),
            resources = resources,
            objects = objects,
            selectors = selectors,
            assets = assets,
            frames = frames,
        )

        return RendererV3Scene(
'''
text = replace_once(text, validation_anchor, validation_insert, "Renderer v3 dedicated contract call")

spec_anchor = '''    private fun specFor(scene: RendererV3Scene): RendererSpec {
'''
validation_helpers = '''    private fun validateDedicatedFeatureContracts(
        root: JSONObject,
        features: List<String>,
        resources: JSONObject,
        objects: List<RendererV3Object>,
        selectors: List<RendererV3Selector>,
        assets: Map<String, ByteArray>,
        frames: Int,
    ) {
        val resourceEntries = buildList<Pair<String, JSONObject>> {
            val keys = resources.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                resources.optJSONObject(id)?.let { add(id to it) }
            }
        }

        fun typeCount(vararg names: String): Int = resourceEntries.count { (_, resource) ->
            resource.optString("type").lowercase() in names
        }
        fun requireFeatureForTypes(feature: String, vararg names: String) {
            val count = typeCount(*names)
            if (count > 0) require(feature in features) {
                "Renderer v3 resource type ${names.joinToString("/")} requires feature '$feature'."
            }
            if (feature in features) require(count > 0) {
                "Renderer v3 declares '$feature' but provides no dedicated ${names.joinToString("/")} resource."
            }
        }
        fun assetExists(path: String): Boolean {
            val normalized = path.replace('\\\\', '/').removePrefix("./")
            return assets.containsKey(normalized) || assets.keys.any { it.endsWith("/$normalized") }
        }
        fun staticAsset(resource: JSONObject): String? = sequenceOf("source", "asset", "relativeAsset")
            .map { resource.optString(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("$") }

        requireFeatureForTypes("source-baked-text-raster", "text-raster", "source-text-raster")
        requireFeatureForTypes("independent-shadow-resource", "independent-shadow")
        requireFeatureForTypes("exact-outro-overlay", "outro-overlay", "exact-outro-overlay")

        resourceEntries.forEach { (id, resource) ->
            when (resource.optString("type").lowercase()) {
                "text-raster", "source-text-raster" -> {
                    val inline = resource.optString("base64").isNotBlank()
                    val asset = staticAsset(resource)
                    require(inline || asset != null) {
                        "Source-baked text raster '$id' must contain base64 pixels or a sidecar asset."
                    }
                    if (!inline && asset != null) require(assetExists(asset)) {
                        "Source-baked text raster '$id' is missing sidecar asset '$asset'."
                    }
                    val sampling = resource.optString("sampling", "nearest").lowercase()
                    require(sampling in setOf("nearest", "linear")) {
                        "Source-baked text raster '$id' has unsupported sampling '$sampling'."
                    }
                }
                "independent-shadow" -> {
                    val target = resource.optString("target").ifBlank {
                        resource.optString("sourceObject")
                    }
                    require(target.isNotBlank()) { "Independent shadow '$id' has no target object." }
                    val targetObject = objects.firstOrNull { it.id == target }
                        ?: error("Independent shadow '$id' targets missing object '$target'.")
                    val targetType = resources.optJSONObject(targetObject.resource ?: "")
                        ?.optString("type", targetObject.kind)?.lowercase()
                        ?: targetObject.kind.lowercase()
                    require(targetType in setOf("polygon", "path", "rect", "ellipse", "custom")) {
                        "Independent shadow '$id' target '$target' is not a supported silhouette type."
                    }
                }
                "outro-overlay", "exact-outro-overlay" -> {
                    val start = resource.optInt("startFrame", -1)
                    val end = resource.optInt("endFrame", -1)
                    require(start in 0 until frames && end in start until frames) {
                        "Exact outro overlay '$id' has invalid frame window $start..$end."
                    }
                    val frameMap = resource.optJSONObject("frames")
                    val frameArray = resource.optJSONArray("frames")
                    val pattern = resource.optString("assetPattern")
                    require(frameMap != null || frameArray != null || pattern.isNotBlank()) {
                        "Exact outro overlay '$id' must provide frames or assetPattern."
                    }
                    if (frameArray != null) require(frameArray.length() >= end - start + 1) {
                        "Exact outro overlay '$id' frame array is shorter than its declared window."
                    }
                    if (pattern.isNotBlank()) require(
                        pattern.contains("{frame}") || pattern.contains("{local}") || pattern.contains("{index}")
                    ) { "Exact outro overlay '$id' assetPattern needs {frame}, {local}, or {index}." }
                }
            }
        }

        if ("raw-frame-tracks" in features) {
            require(containsRawFrameTrack(root)) {
                "Renderer v3 declares 'raw-frame-tracks' but contains no dense/raw frame track."
            }
        }
        if ("frame-addressed-selectors" in features) {
            require(selectors.any { selector -> selector.conditions.any { it.key == "frame" || it.key in setOf("every", "from", "to") } }) {
                "Renderer v3 declares 'frame-addressed-selectors' but contains no frame-addressed selector."
            }
        }
        // preview-export-identical-path is an engine invariant: RendererV3FrameRenderer.renderRgba()
        // always derives its bytes from render(), so there is no second implementation to drift.
    }

    private fun containsRawFrameTrack(value: Any?): Boolean = when (value) {
        is JSONObject -> {
            if (value.has("dense") || (value.optString("interpolation").equals("raw", true) && value.has("track"))) true
            else {
                val keys = value.keys()
                var found = false
                while (keys.hasNext() && !found) found = containsRawFrameTrack(value.opt(keys.next()))
                found
            }
        }
        is JSONArray -> (0 until value.length()).any { containsRawFrameTrack(value.opt(it)) }
        else -> false
    }

'''
if validation_helpers not in text:
    if text.count(spec_anchor) != 1:
        raise SystemExit("Renderer v3 validation helper anchor changed")
    text = text.replace(spec_anchor, validation_helpers + spec_anchor, 1)

V3.write_text(text)


# ---------------------------------------------------------------------------
# Dedicated Android rendering implementations.
# ---------------------------------------------------------------------------
text = V3R.read_text()

if 'import android.graphics.PorterDuff\n' not in text:
    text = text.replace('import android.graphics.Path\n', 'import android.graphics.Path\nimport android.graphics.PorterDuff\n', 1)

old_dispatch = '''        canvas.save()
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
        canvas.restore()
'''
new_dispatch = '''        canvas.save()
        try {
            clipMask(scene, boundProps, canvas)
            applyObjectTransform(boundProps, canvas)
            when (type) {
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
        } finally {
            canvas.restore()
        }
'''
text = replace_once(text, old_dispatch, new_dispatch, "Renderer v3 dedicated feature dispatch")

helper_anchor = '''    private fun drawResourceGroup(
'''
helpers = '''    /** Dedicated source-baked text raster resource. No Android font shaping occurs here. */
    private fun drawSourceBakedTextRaster(
        scene: RendererV3Scene,
        resource: JSONObject,
        props: Map<String, Any?>,
        opacity: Float,
        canvas: Canvas,
    ) {
        val source = stringValue(
            props["source"] ?: props["asset"] ?: props["relativeAsset"]
                ?: resource.opt("source") ?: resource.opt("asset") ?: resource.opt("relativeAsset"),
        )
        val bitmap = decodeBitmap(scene, source, resource)
            ?: error("Source-baked text raster is missing its pixels: ${source ?: "inline"}")
        val sampling = stringValue(props["sampling"] ?: resource.opt("sampling"))?.lowercase() ?: "nearest"
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = sampling == "linear"
            alpha = (255f * opacity).toInt().coerceIn(0, 255)
        }
        val quad = points(props["geometry.quad"] ?: props["quad"] ?: jsonValue(resource.opt("quad")))
        if (quad != null && quad.size == 4) {
            val src = floatArrayOf(
                0f, 0f,
                bitmap.width.toFloat(), 0f,
                bitmap.width.toFloat(), bitmap.height.toFloat(),
                0f, bitmap.height.toFloat(),
            )
            val dst = FloatArray(8)
            quad.forEachIndexed { index, point ->
                dst[index * 2] = point.first
                dst[index * 2 + 1] = point.second
            }
            val matrix = Matrix()
            check(matrix.setPolyToPoly(src, 0, dst, 0, 4)) { "Could not map source-baked text raster quad." }
            canvas.drawBitmap(bitmap, matrix, paint)
            return
        }
        val x = number(props["x"] ?: props["position.x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"] ?: props["position.y"], resource.optDouble("y", 0.0)).toFloat()
        val width = number(props["width"], resource.optDouble("width", bitmap.width.toDouble())).toFloat()
        val height = number(props["height"], resource.optDouble("height", bitmap.height.toDouble())).toFloat()
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)
    }

    /** A separately layered/animated shadow that borrows the target object's silhouette. */
    private fun drawIndependentShadow(
        scene: RendererV3Scene,
        project: StudioProject,
        resource: JSONObject,
        props: Map<String, Any?>,
        opacity: Float,
        frame: Int,
        canvas: Canvas,
    ) {
        val targetId = stringValue(props["shadow.target"] ?: props["target"] ?: resource.opt("target") ?: resource.opt("sourceObject"))
            ?: error("Independent shadow has no target object.")
        val target = scene.objectById(targetId) ?: error("Independent shadow target '$targetId' does not exist.")
        if (frame !in target.lifespanStart..target.lifespanEnd) return
        val targetProps = RendererV3Evaluator.properties(scene, target, frame)
            .mapValues { (_, value) -> bindProjectValue(value, project, target) }
        val targetResource = scene.resource(target.resource) ?: JSONObject().put("type", target.kind)
        val path = silhouettePath(targetResource, targetProps)
            ?: error("Independent shadow target '$targetId' has no drawable silhouette.")
        val alpha = number(props["shadow.alpha"] ?: props["alpha"], resource.optDouble("alpha", 0.22)).toFloat().coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val dx = number(props["shadow.offsetX"] ?: props["offsetX"], resource.optDouble("offsetX", 0.0)).toFloat()
        val dy = number(props["shadow.offsetY"] ?: props["offsetY"], resource.optDouble("offsetY", 0.0)).toFloat()
        val blur = number(props["shadow.blur"] ?: props["blur"], resource.optDouble("blur", 0.0)).toFloat().coerceAtLeast(0f)
        val color = colorValue(props["shadow.color"] ?: props["color"] ?: resource.opt("color"), Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
            this.alpha = (Color.alpha(color) * alpha * opacity).toInt().coerceIn(0, 255)
            if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        try {
            applyObjectTransform(targetProps, canvas)
            canvas.translate(dx, dy)
            canvas.drawPath(path, paint)
        } finally {
            canvas.restore()
        }
    }

    /** Exact frame-addressed outro image sequence. Each frame comes from a package sidecar. */
    private fun drawExactOutroOverlay(
        scene: RendererV3Scene,
        resource: JSONObject,
        props: Map<String, Any?>,
        opacity: Float,
        frame: Int,
        canvas: Canvas,
    ) {
        val start = number(props["startFrame"], resource.optDouble("startFrame", -1.0)).toInt()
        val end = number(props["endFrame"], resource.optDouble("endFrame", -1.0)).toInt()
        if (frame !in start..end) return
        val local = frame - start
        val source = exactFrameAsset(resource, props, frame, local)
            ?: error("Exact outro overlay has no asset for frame $frame.")
        val bitmap = decodeBitmap(scene, source, JSONObject())
            ?: error("Exact outro overlay frame asset '$source' is missing or undecodable.")
        if (truthy(props["replaceCanvas"] ?: resource.opt("replaceCanvas"), false)) {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        val sampling = stringValue(props["sampling"] ?: resource.opt("sampling"))?.lowercase() ?: "nearest"
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = sampling == "linear"
            alpha = (255f * opacity).toInt().coerceIn(0, 255)
        }
        val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
        val width = number(props["width"], resource.optDouble("width", scene.canvas.width.toDouble())).toFloat()
        val height = number(props["height"], resource.optDouble("height", scene.canvas.height.toDouble())).toFloat()
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), paint)
    }

    private fun exactFrameAsset(resource: JSONObject, props: Map<String, Any?>, frame: Int, local: Int): String? {
        val frameValue = props["frames"]
        if (frameValue is List<*>) return frameValue.getOrNull(local)?.toString()?.takeIf { it.isNotBlank() }
        val framesObject = resource.optJSONObject("frames")
        framesObject?.optString(frame.toString())?.takeIf { it.isNotBlank() }?.let { return it }
        framesObject?.optString(local.toString())?.takeIf { it.isNotBlank() }?.let { return it }
        resource.optJSONArray("frames")?.optString(local)?.takeIf { it.isNotBlank() }?.let { return it }
        val pattern = stringValue(props["assetPattern"] ?: resource.opt("assetPattern")) ?: return null
        return pattern
            .replace("{frame}", frame.toString())
            .replace("{local}", local.toString())
            .replace("{index}", local.toString())
    }

    private fun silhouettePath(resource: JSONObject, props: Map<String, Any?>): Path? {
        val type = resource.optString("type", "polygon").lowercase()
        if (type in setOf("polygon", "path", "custom")) {
            val pts = points(props["geometry.points"] ?: props["points"] ?: jsonValue(resource.opt("points"))) ?: return null
            if (pts.size < 3) return null
            return Path().apply {
                moveTo(pts[0].first, pts[0].second)
                for (index in 1 until pts.size) lineTo(pts[index].first, pts[index].second)
                close()
            }
        }
        val x = number(props["x"] ?: props["geometry.x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"] ?: props["geometry.y"], resource.optDouble("y", 0.0)).toFloat()
        val width = number(props["width"] ?: props["geometry.width"], resource.optDouble("width", 0.0)).toFloat()
        val height = number(props["height"] ?: props["geometry.height"], resource.optDouble("height", 0.0)).toFloat()
        if (width <= 0f || height <= 0f) return null
        return Path().apply {
            if (type == "ellipse") addOval(RectF(x, y, x + width, y + height), Path.Direction.CW)
            else addRect(RectF(x, y, x + width, y + height), Path.Direction.CW)
        }
    }

'''
if helpers not in text:
    if text.count(helper_anchor) != 1:
        raise SystemExit("Renderer v3 dedicated helper anchor changed")
    text = text.replace(helper_anchor, helpers + helper_anchor, 1)

# Resource groups must get the same dedicated semantics as top-level objects.
old_group_dispatch = '''            when (childType) {
                "polygon", "path", "shadow", "shine", "material", "filter", "custom" -> drawPolygonLike(child, defaults, opacity, canvas)
                "rect" -> drawRect(child, defaults, opacity, canvas)
                "ellipse" -> drawEllipse(child, defaults, opacity, canvas)
                "image" -> drawImage(scene, project, child, defaults, opacity, canvas)
                "text" -> drawText(child, defaults, opacity, canvas)
                "group" -> drawResourceGroup(scene, project, bindingObject, child, defaults, opacity, frame, canvas)
            }
'''
new_group_dispatch = '''            when (childType) {
                "polygon", "path", "shadow", "shine", "material", "filter", "custom" -> drawPolygonLike(child, defaults, opacity, canvas)
                "rect" -> drawRect(child, defaults, opacity, canvas)
                "ellipse" -> drawEllipse(child, defaults, opacity, canvas)
                "image" -> drawImage(scene, project, child, defaults, opacity, canvas)
                "text" -> drawText(child, defaults, opacity, canvas)
                "text-raster", "source-text-raster" -> drawSourceBakedTextRaster(scene, child, defaults, opacity, canvas)
                "independent-shadow" -> drawIndependentShadow(scene, project, child, defaults, opacity, frame, canvas)
                "outro-overlay", "exact-outro-overlay" -> drawExactOutroOverlay(scene, child, defaults, opacity, frame, canvas)
                "group" -> drawResourceGroup(scene, project, bindingObject, child, defaults, opacity, frame, canvas)
            }
'''
text = replace_once(text, old_group_dispatch, new_group_dispatch, "Renderer v3 group dedicated dispatch")

V3R.write_text(text)
print("Applied real Renderer v3 dedicated feature contracts")
