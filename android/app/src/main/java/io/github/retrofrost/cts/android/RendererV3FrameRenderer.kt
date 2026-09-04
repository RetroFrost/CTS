package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Generic Android renderer for Renderer API v3 scene IR.
 *
 * Preview and export call this same class, so selectors, resources and integer-frame
 * tracks are evaluated identically in both paths.
 */
class RendererV3FrameRenderer {
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    fun render(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap {
        val scene = requireNotNull(RendererV3Runtime.scene(spec)) {
            "Renderer v3 scene '${spec.id}' is not loaded. Re-import the renderer package."
        }
        val out = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val sx = out.width.toFloat() / scene.canvas.width.toFloat()
        val sy = out.height.toFloat() / scene.canvas.height.toFloat()
        canvas.scale(sx, sy)

        val background = parseColor(
            scene.raw.optString("background", scene.raw.optJSONObject("geometry")?.optString("background", "#000000") ?: "#000000"),
            Color.BLACK,
        )
        canvas.drawColor(background)

        // A v3 package can source-lock arbitrary rectangular frame regions from a
        // sidecar lossless video/image sequence. The asset decoder path is shared by
        // preview/export and is deliberately applied before analytic scene objects.
        drawStaticPixelLock(scene, frame, canvas)

        val objects = orderedObjects(scene)
        objects.forEach { obj ->
            if (frame !in obj.lifespanStart..obj.lifespanEnd) return@forEach
            val props = RendererV3Evaluator.properties(scene, obj, frame)
            if (!truthy(props["visible"], true)) return@forEach
            drawObject(scene, project, obj, props, frame, canvas)
        }
        return out
    }

    fun renderRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray {
        val bitmap = render(project, spec, frame, width, height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size * 4)
        var cursor = 0
        pixels.forEach { argb ->
            bytes[cursor++] = Color.red(argb).toByte()
            bytes[cursor++] = Color.green(argb).toByte()
            bytes[cursor++] = Color.blue(argb).toByte()
            bytes[cursor++] = Color.alpha(argb).toByte()
        }
        bitmap.recycle()
        return bytes
    }

    private fun orderedObjects(scene: RendererV3Scene): List<RendererV3Object> {
        if (scene.layers.isEmpty()) return scene.objects
        val rank = scene.layers.withIndex().associate { it.value to it.index }
        return scene.objects.withIndex().sortedWith(
            compareBy<IndexedValue<RendererV3Object>> { rank[it.value.id] ?: Int.MAX_VALUE }
                .thenBy { it.index },
        ).map { it.value }
    }

    private fun drawObject(
        scene: RendererV3Scene,
        project: StudioProject,
        obj: RendererV3Object,
        props: Map<String, Any?>,
        frame: Int,
        canvas: Canvas,
    ) {
        val resource = scene.resource(obj.resource) ?: JSONObject().put("type", obj.kind)
        val type = resource.optString("type", obj.kind).lowercase()
        val boundProps = props.mapValues { (_, value) -> bindProjectValue(value, project, obj) }
        val opacity = number(boundProps["opacity"], number(boundProps["material.alpha"], 1.0)).toFloat().coerceIn(0f, 1f)
        if (opacity <= 0.0001f) return

        canvas.save()
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
    }

    /** Dedicated source-baked text raster resource. No Android font shaping occurs here. */
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
        val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
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

    private fun drawResourceGroup(
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
                "text-raster", "source-text-raster" -> drawSourceBakedTextRaster(scene, child, defaults, opacity, canvas)
                "independent-shadow" -> drawIndependentShadow(scene, project, child, defaults, opacity, frame, canvas)
                "outro-overlay", "exact-outro-overlay" -> drawExactOutroOverlay(scene, child, defaults, opacity, frame, canvas)
                "group" -> drawResourceGroup(scene, project, bindingObject, child, defaults, opacity, frame, canvas)
            }
            canvas.restore()
        }
    }

    private fun drawPolygonLike(resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
        val points = points(props["geometry.points"] ?: props["points"] ?: jsonValue(resource.opt("points"))) ?: return
        if (points.size < 3) return
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (index in 1 until points.size) lineTo(points[index].first, points[index].second)
            close()
        }
        drawShadow(path, resource, props, opacity, canvas)
        val paint = materialPaint(resource, props, opacity, Paint.Style.FILL)
        canvas.drawPath(path, paint)
        val strokeWidth = number(props["strokeWidth"], resource.optDouble("strokeWidth", 0.0)).toFloat()
        if (strokeWidth > 0f) {
            val stroke = materialPaint(resource, props, opacity, Paint.Style.STROKE).apply {
                this.strokeWidth = strokeWidth
                color = colorValue(props["stroke"] ?: resource.opt("stroke"), Color.TRANSPARENT)
            }
            canvas.drawPath(path, stroke)
        }
    }

    private fun drawRect(resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
        val x = number(props["x"] ?: props["geometry.x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"] ?: props["geometry.y"], resource.optDouble("y", 0.0)).toFloat()
        val w = number(props["width"] ?: props["geometry.width"], resource.optDouble("width", 0.0)).toFloat()
        val h = number(props["height"] ?: props["geometry.height"], resource.optDouble("height", 0.0)).toFloat()
        if (w <= 0f || h <= 0f) return
        val rect = RectF(x, y, x + w, y + h)
        val radius = number(props["radius"], resource.optDouble("radius", 0.0)).toFloat()
        val paint = materialPaint(resource, props, opacity, Paint.Style.FILL)
        if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint) else canvas.drawRect(rect, paint)
    }

    private fun drawEllipse(resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
        val x = number(props["x"] ?: props["geometry.x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"] ?: props["geometry.y"], resource.optDouble("y", 0.0)).toFloat()
        val w = number(props["width"] ?: props["geometry.width"], resource.optDouble("width", 0.0)).toFloat()
        val h = number(props["height"] ?: props["geometry.height"], resource.optDouble("height", 0.0)).toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawOval(RectF(x, y, x + w, y + h), materialPaint(resource, props, opacity, Paint.Style.FILL))
    }

    private fun drawImage(
        scene: RendererV3Scene,
        project: StudioProject,
        resource: JSONObject,
        props: Map<String, Any?>,
        opacity: Float,
        canvas: Canvas,
    ) {
        val source = stringValue(
            props["source"] ?: props["image.source"] ?: props["asset"] ?: props["relativeAsset"]
                ?: resource.opt("source") ?: resource.opt("asset") ?: resource.opt("relativeAsset"),
        )
        val bitmap = decodeBitmap(scene, source, resource) ?: return
        val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
        val w = number(props["width"], resource.optDouble("width", bitmap.width.toDouble())).toFloat()
        val h = number(props["height"], resource.optDouble("height", bitmap.height.toDouble())).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (255f * opacity).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + w, y + h), paint)
    }

    private fun drawText(resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
        val text = stringValue(props["text.value"] ?: props["value"] ?: resource.opt("text") ?: resource.opt("value")) ?: return
        val x = number(props["x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"], resource.optDouble("y", 0.0)).toFloat()
        val size = number(props["text.size"] ?: props["fontSize"] ?: props["size"], resource.optDouble("fontSize", 40.0)).toFloat()
        val paint = materialPaint(resource, props, opacity, Paint.Style.FILL).apply {
            textSize = size
            isSubpixelText = true
            isLinearText = true
            textAlign = when (stringValue(props["text.align"] ?: resource.opt("align"))?.lowercase()) {
                "center" -> Paint.Align.CENTER
                "right", "end" -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }
            isFakeBoldText = truthy(props["text.bold"] ?: resource.opt("bold"), false)
        }
        canvas.drawText(text, x, y, paint)
    }

    private fun materialPaint(resource: JSONObject, props: Map<String, Any?>, opacity: Float, style: Paint.Style): Paint {
        val fill = props["fill"] ?: props["material.fill"] ?: props["color"] ?: resource.opt("fill") ?: resource.opt("color")
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = style
            color = colorValue(fill, Color.WHITE)
            alpha = (Color.alpha(color) * opacity).toInt().coerceIn(0, 255)
            val blur = number(props["filter.blur"] ?: props["blur"], resource.optDouble("blur", 0.0)).toFloat()
            if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            if (Build.VERSION.SDK_INT >= 29) {
                blendMode = when (stringValue(props["blend"] ?: resource.opt("blend"))?.lowercase()) {
                    "multiply" -> BlendMode.MULTIPLY
                    "screen" -> BlendMode.SCREEN
                    "add", "plus" -> BlendMode.PLUS
                    "overlay" -> BlendMode.OVERLAY
                    "darken" -> BlendMode.DARKEN
                    "lighten" -> BlendMode.LIGHTEN
                    "difference" -> BlendMode.DIFFERENCE
                    else -> BlendMode.SRC_OVER
                }
            }
        }
    }

    private fun drawShadow(path: Path, resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
        val alpha = number(props["shadow.alpha"], resource.optJSONObject("shadow")?.optDouble("alpha", 0.0) ?: 0.0).toFloat()
        if (alpha <= 0f) return
        val dx = number(props["shadow.offsetX"], resource.optJSONObject("shadow")?.optDouble("offsetX", 0.0) ?: 0.0).toFloat()
        val dy = number(props["shadow.offsetY"], resource.optJSONObject("shadow")?.optDouble("offsetY", 0.0) ?: 0.0).toFloat()
        val blur = number(props["shadow.blur"], resource.optJSONObject("shadow")?.optDouble("blur", 0.0) ?: 0.0).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb((255f * alpha * opacity).toInt().coerceIn(0, 255), 0, 0, 0)
            if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun applyObjectTransform(props: Map<String, Any?>, canvas: Canvas) {
        val homography = numberList(props["transform.homography"])
        if (homography != null && homography.size == 9) {
            canvas.concat(Matrix().apply { setValues(homography.toFloatArray()) })
        }
        val affine = numberList(props["transform.affine"])
        if (affine != null && affine.size == 6) {
            val matrix = Matrix().apply {
                setValues(floatArrayOf(
                    affine[0], affine[1], affine[4],
                    affine[2], affine[3], affine[5],
                    0f, 0f, 1f,
                ))
            }
            canvas.concat(matrix)
        }
        val x = number(props["position.x"], 0.0).toFloat() + number(props["movement.x"], 0.0).toFloat()
        val y = number(props["position.y"], 0.0).toFloat() + number(props["movement.y"], 0.0).toFloat()
        if (x != 0f || y != 0f) canvas.translate(x, y)
        val rotation = number(props["transform.rotation"], 0.0).toFloat()
        if (rotation != 0f) canvas.rotate(rotation)
        val sx = number(props["transform.scaleX"], number(props["transform.scale"], 1.0)).toFloat()
        val sy = number(props["transform.scaleY"], number(props["transform.scale"], 1.0)).toFloat()
        if (sx != 1f || sy != 1f) canvas.scale(sx, sy)
    }

    private fun clipMask(scene: RendererV3Scene, props: Map<String, Any?>, canvas: Canvas) {
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

    private fun decodeBitmap(scene: RendererV3Scene, source: String?, resource: JSONObject): Bitmap? {
        val inline = resource.optString("base64").takeIf { it.isNotBlank() }
        val cacheKey = source ?: inline?.hashCode()?.toString() ?: return null
        bitmapCache[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }
        if (source != null) {
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
    }

    /**
     * Small static pixel-lock resources (PNG/WebP) can be attached directly to a v3
     * package. Lossless video override resources are retained by the package loader
     * for dedicated decoders introduced by source-specific renderer features.
     */
    private fun drawStaticPixelLock(scene: RendererV3Scene, frame: Int, canvas: Canvas) {
        val lock = scene.raw.optJSONObject("pixelLock") ?: return
        val entries = lock.optJSONArray("absoluteImageOverrides") ?: return
        repeat(entries.length()) { index ->
            val item = entries.optJSONObject(index) ?: return@repeat
            if (frame !in item.optInt("startFrame", -1)..item.optInt("endFrame", -1)) return@repeat
            val asset = item.optString("relativeAsset")
            val bitmap = decodeBitmap(scene, asset, item) ?: return@repeat
            val x = item.optDouble("x", 0.0).toFloat()
            val y = item.optDouble("y", 0.0).toFloat()
            val width = item.optDouble("width", bitmap.width.toDouble()).toFloat()
            val height = item.optDouble("height", bitmap.height.toDouble()).toFloat()
            canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    /**
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
        if (!token.startsWith("\$")) return null
        if (token.startsWith("\$project.")) {
            return when (token.removePrefix("\$project.")) {
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
        if (!token.startsWith("\$card.")) return null
        val explicitIndex = when {
            obj.raw.has("cardIndex") -> obj.raw.optInt("cardIndex", -1)
            obj.raw.has("dataIndex") -> obj.raw.optInt("dataIndex", -1)
            else -> -1
        }
        if (explicitIndex !in project.cards.indices) return null
        val card = project.cards[explicitIndex]
        return when (token.removePrefix("\$card.")) {
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

    private fun points(value: Any?): List<Pair<Float, Float>>? {
        val list = value as? List<*> ?: return null
        return list.mapNotNull { point ->
            val pair = point as? List<*> ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val x = (pair[0] as? Number)?.toFloat() ?: return@mapNotNull null
            val y = (pair[1] as? Number)?.toFloat() ?: return@mapNotNull null
            x to y
        }.takeIf { it.isNotEmpty() }
    }

    private fun numberList(value: Any?): List<Float>? = (value as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
    private fun number(value: Any?, fallback: Double): Double = (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: fallback
    private fun stringValue(value: Any?): String? = value?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }
    private fun truthy(value: Any?, fallback: Boolean): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() > 0.0
        is String -> value.equals("true", true) || value == "1"
        else -> fallback
    }

    private fun colorValue(value: Any?, fallback: Int): Int = when (value) {
        is Number -> value.toInt()
        is String -> parseColor(value, fallback)
        else -> fallback
    }

    private fun parseColor(value: String, fallback: Int): Int = runCatching {
        if (value.startsWith("#")) Color.parseColor(value) else value.toLong().toInt()
    }.getOrDefault(fallback)

    private fun jsonValue(value: Any?): Any? = when (value) {
        is JSONArray -> List(value.length()) { jsonValue(value.opt(it)) }
        is JSONObject -> value
        JSONObject.NULL -> null
        else -> value
    }
}
