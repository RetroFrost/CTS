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
import android.graphics.RectF
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
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
        canvas.restore()
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
        val x = number(props["x"] ?: props["position.x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"] ?: props["position.y"], resource.optDouble("y", 0.0)).toFloat()
        val w = number(props["width"], resource.optDouble("width", bitmap.width.toDouble())).toFloat()
        val h = number(props["height"], resource.optDouble("height", bitmap.height.toDouble())).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (255f * opacity).toInt().coerceIn(0, 255)
        }
        canvas.drawBitmap(bitmap, null, RectF(x, y, x + w, y + h), paint)
    }

    private fun drawText(resource: JSONObject, props: Map<String, Any?>, opacity: Float, canvas: Canvas) {
        val text = stringValue(props["text.value"] ?: props["value"] ?: resource.opt("text") ?: resource.opt("value")) ?: return
        val x = number(props["x"] ?: props["position.x"], resource.optDouble("x", 0.0)).toFloat()
        val y = number(props["y"] ?: props["position.y"], resource.optDouble("y", 0.0)).toFloat()
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
