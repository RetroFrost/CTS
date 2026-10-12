package io.github.retrofrost.cts.android.renderer3

import android.content.Context
import io.github.retrofrost.cts.android.model.CtsProject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A deterministic integer-frame, Android Canvas evaluator for Renderer3 scene-v3
 * raster/vector/text objects. One instance is used by both export and live preview.
 * Complex Windows-only SmartBadge and source-specific engines fail validation at import,
 * rather than silently producing an inaccurate video.
 */
internal class RendererV3Player(context: Context, selection: RendererV3Selection, project: CtsProject) : Closeable {
    private val bundle = RendererV3Container.open(RendererV3Container.checkedProjectFile(context, selection), validateAnimations = false)
    private val ribbon = if (bundle.wrappedRibbon != null) RendererV3RibbonPlayer(context, bundle, project) else null
    private val root = bundle.root
    private val width = bundle.selection.width
    private val height = bundle.selection.height
    private val fps = bundle.selection.fps
    private val frames = bundle.selection.frames
    private val resources = root.optJSONObject("resources") ?: JSONObject()
    private val selectors = parseSelectors(root.optJSONArray("selectors"))
    private val layers = root.optJSONArray("layers")
    private val nodes = parseNodes(root.optJSONArray("objects") ?: JSONArray())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val images = object : LinkedHashMap<String, Bitmap>(6, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val overflow = size > 4
            if (overflow) eldest?.value?.takeUnless(Bitmap::isRecycled)?.recycle()
            return overflow
        }
    }
    @Synchronized
    fun render(canvas: Canvas, seconds: Float, outputWidth: Int, outputHeight: Int) {
        if (ribbon != null) {
            ribbon.render(canvas, seconds, outputWidth, outputHeight)
            return
        }
        val frame = ((seconds.coerceAtLeast(0f) * fps).toInt()).coerceIn(0, frames - 1)
        canvas.drawColor(color(root.optString("background", "#000000"), Color.BLACK))
        canvas.save()
        try {
            canvas.scale(outputWidth.toFloat() / width, outputHeight.toFloat() / height)
            for (node in nodes) {
                if (frame < node.start || frame > node.end) continue
                val resource = resources.optJSONObject(node.resource) ?: JSONObject()
                val props = properties(node, resource, frame)
                if (!asBool(props["visible"], true)) continue
                val opacity = asFloat(props["opacity"] ?: props["material.alpha"], 1f).coerceIn(0f, 1f)
                if (opacity <= 0f) continue
                canvas.save()
                try {
                    val localClip = asBool(props["clip.local"] ?: props["clip.afterTransform"], false)
                    if (!localClip) clip(canvas, props)
                    transform(canvas, props)
                    if (localClip) clip(canvas, props)
                    val type = resource.optString("type", node.kind).lowercase()
                    when (type) {
                        "rect", "ellipse", "polygon" -> vector(canvas, type, props, resource, opacity)
                        "text" -> text(canvas, props, resource, opacity)
                        "image", "source-card-raster", "source-component-raster", "component-raster",
                        "text-raster", "source-text-raster" -> bitmap(canvas, props, resource, opacity)
                    }
                } finally {
                    canvas.restore()
                }
            }
        } finally {
            canvas.restore()
        }
    }

    @Synchronized
    override fun close() {
        ribbon?.close()
        images.values.forEach { if (!it.isRecycled) it.recycle() }
        images.clear()
        bundle.close()
    }

    private data class Node(
        val id: String,
        val kind: String,
        val frame: Int,
        val start: Int,
        val end: Int,
        val resource: String,
        val source: JSONObject,
        val properties: JSONObject,
        val order: Int,
        val rank: Int,
        val z: Int,
    )

    private data class Selector(
        val kind: String, val constraints: String, val timeline: String,
        val specificity: Int, val order: Int, val properties: JSONObject,
    )

    private fun parseNodes(objects: JSONArray): List<Node> {
        val output = ArrayList<Node>(objects.length())
        for (index in 0 until objects.length()) {
            val item = objects.optJSONObject(index) ?: continue
            val frame = item.optInt("frame", 0)
            val lifespan = item.optJSONObject("lifespan")
            val id = item.optString("id", "object-" + index)
            val rank = (0 until (layers?.length() ?: 0))
                .firstOrNull { layers?.optString(it) == id } ?: Int.MAX_VALUE
            output += Node(
                id = id, kind = item.optString("kind", "custom"),
                frame = frame,
                start = lifespan?.optInt("start", frame) ?: frame,
                end = lifespan?.optInt("end", frames - 1) ?: frames - 1,
                resource = item.optString("resource"),
                source = item,
                properties = item.optJSONObject("properties") ?: JSONObject(),
                order = index, rank = rank,
                z = item.optInt("zIndex", item.optJSONObject("properties")?.optInt("zIndex", 0) ?: 0),
            )
        }
        return output.sortedWith(compareBy<Node> { it.rank }.thenBy { it.z }.thenBy { it.order })
    }

    private fun parseSelectors(array: JSONArray?): List<Selector> {
        val results = ArrayList<Selector>()
        if (array == null) return results
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val raw = item.optString("select")
            val matcher = Regex("^([A-Za-z_][A-Za-z0-9_.-]*)\\[(.*)]$").matchEntire(raw)
                ?: error("Unsupported Renderer3 selector: " + raw)
            val tokens = matcher.groupValues[2].split(',').map { it.trim() }.filter { it.isNotBlank() && it != "*" }
            val specificity = 100 + tokens.size + if (tokens.any { it.startsWith("frame=") }) 400
                else if (tokens.any { it.startsWith("every=") }) 260 else if (tokens.isNotEmpty()) 220 else 0
            results += Selector(
                kind = matcher.groupValues[1], constraints = matcher.groupValues[2],
                timeline = item.optString("timeline", "relative"),
                specificity = specificity, order = i,
                properties = item.optJSONObject("properties") ?: JSONObject(),
            )
        }
        return results.sortedWith(compareBy<Selector> { it.specificity }.thenBy { it.order })
    }

    private fun matches(sel: Selector, node: Node): Boolean {
        if (sel.kind != node.kind) return false
        val tests = sel.constraints.split(',').map { it.trim() }.filter { it.isNotBlank() && it != "*" }
        val values = LinkedHashMap<String, String>()
        for (token in tests) {
            val match = Regex("^([A-Za-z_][A-Za-z0-9_.-]*)(>=|<=|!=|=|>|<)(.+)$")
                .matchEntire(token) ?: return false
            val key = match.groupValues[1]
            val operation = match.groupValues[2]
            val expected = match.groupValues[3]
            if (key == "frame" && operation == "=" && expected.contains("..")) {
                val range = expected.split("..")
                if (range.size != 2 || node.frame !in (range[0].toIntOrNull() ?: return false)..(range[1].toIntOrNull() ?: return false)) return false
                continue
            }
            if (key in setOf("every", "from", "to")) {
                values[key] = expected
                continue
            }
            val actual: Any? = when (key) {
                "frame" -> node.frame
                "id" -> node.id
                "kind" -> node.kind
                else -> node.source.opt(key)
            }
            val left = actual?.toString().orEmpty()
            val passed = when (operation) {
                "=" -> left == expected
                "!=" -> left != expected
                else -> {
                    val l = left.toDoubleOrNull() ?: return false
                    val r = expected.toDoubleOrNull() ?: return false
                    when (operation) {
                        ">" -> l > r
                        "<" -> l < r
                        ">=" -> l >= r
                        "<=" -> l <= r
                        else -> false
                    }
                }
            }
            if (!passed) return false
        }
        val every = values["every"]?.toIntOrNull()
        if (every != null) {
            val start = values["from"]?.toIntOrNull() ?: 0
            val end = values["to"]?.toIntOrNull() ?: Int.MAX_VALUE
            if (every <= 0 || node.frame !in start..end || (node.frame - start) % every != 0) return false
        }
        return true
    }

    private fun properties(node: Node, resource: JSONObject, frame: Int): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        fun merge(source: JSONObject?, timeline: String) {
            if (source == null) return
            for ((key, descriptor) in flatten(source)) {
                val evaluated = evaluate(descriptor, frame, node.frame, timeline)
                if (evaluated != null) result[key] = evaluated
            }
        }
        merge(resource.optJSONObject("properties"), "absolute")
        for (selector in selectors) if (matches(selector, node)) {
            merge(selector.properties, selector.timeline)
        }
        merge(node.properties, node.source.optString("timeline", "relative"))
        val baseX = asFloat(result["sourceExact.baseX"], Float.NaN)
        val scroll = asFloat(result["sourceExact.scroll"], Float.NaN)
        if (baseX.isFinite() && scroll.isFinite()) result["x"] = baseX - scroll
        val movementX = result["movement.x"]
        val movementY = result["movement.y"]
        if (movementX != null) result["x"] = asFloat(result["x"]) + asFloat(movementX)
        if (movementY != null) result["y"] = asFloat(result["y"]) + asFloat(movementY)
        return result
    }

    private fun flatten(obj: JSONObject, prefix: String = ""): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        for (key in obj.keys()) {
            val name = if (prefix.isEmpty()) key else prefix + "." + key
            val value = obj.opt(key) ?: continue
            if (value is JSONObject && !value.has("value") && !value.has("track") && !value.has("dense"))
                result.putAll(flatten(value, name))
            else result[name] = value
        }
        return result
    }

    private fun evaluate(value: Any?, absolute: Int, anchor: Int, defaultTimeline: String): Any? {
        if (value !is JSONObject) return if (value == JSONObject.NULL) null else value
        if (!value.has("track") && !value.has("dense"))
            return if (value.has("value")) value.opt("value") else value
        val frame = (if (value.optString("timeline", defaultTimeline) == "relative") absolute - anchor else absolute) +
            value.optInt("frameOffset", 0)
        val interpolation = value.optString("interpolation", "raw")
        val extrapolate = value.optString("extrapolate", "none") == "hold"
        if (value.has("dense")) {
            val dense = value.opt("dense")
            val array = if (dense is JSONArray) dense else (dense as? JSONObject)?.optJSONArray("values")
            if (array == null || array.length() == 0) return null
            val start = (dense as? JSONObject)?.optInt("start", value.optInt("start", 0))
                ?: value.optInt("start", 0)
            val stride = max(1, value.optInt("stride", (dense as? JSONObject)?.optInt("stride", 1) ?: 1))
            val rel = frame - start
            if (rel < 0 || rel / stride >= array.length()) {
                return if (extrapolate) array.opt(if (rel < 0) 0 else array.length() - 1) else null
            }
            val index = rel / stride
            val frac = rel % stride
            if (frac == 0 || stride == 1 || interpolation in setOf("hold", "step")) return array.opt(index)
            if (interpolation == "raw") return null
            val left = array.opt(index)
            val right = array.opt(min(index + 1, array.length() - 1))
            return interpolate(left, right, frac.toFloat() / stride, interpolation) ?: left
        }
        val track = value.optJSONArray("track") ?: return null
        val keys = ArrayList<Pair<Int, Any>>()
        for (i in 0 until track.length()) {
            val pair = track.optJSONArray(i)
            if (pair != null && pair.length() >= 2) {
                keys += pair.optInt(0) to (pair.opt(1) ?: continue)
            } else {
                val key = track.optJSONObject(i)
                if (key != null) keys += key.optInt("frame", key.optInt("time", 0)) to (key.opt("value") ?: continue)
            }
        }
        if (keys.isEmpty()) return null
        keys.sortBy { it.first }
        if (frame < keys.first().first || frame > keys.last().first)
            return if (extrapolate) (if (frame < keys.first().first) keys.first().second else keys.last().second) else null
        val rightIndex = keys.indexOfFirst { it.first >= frame }
        val right = keys[rightIndex]
        if (right.first == frame) return right.second
        if (interpolation == "raw") return null
        val left = keys[rightIndex - 1]
        if (interpolation in setOf("hold", "step")) return left.second
        return interpolate(left.second, right.second,
            (frame - left.first).toFloat() / max(1, right.first - left.first), interpolation)
    }

    private fun interpolate(left: Any?, right: Any?, input: Float, style: String): Any? {
        val a = asFloat(left, Float.NaN)
        val b = asFloat(right, Float.NaN)
        if (!a.isFinite() || !b.isFinite()) return null
        val t = input.coerceIn(0f, 1f)
        val eased = when (style) {
            "smoothstep", "cubic-in-out" -> t * t * (3f - 2f * t)
            "cubic-in" -> t * t * t
            "cubic-out" -> 1f - (1f - t).pow(3)
            else -> t
        }
        return a + (b - a) * eased
    }

    private fun transform(canvas: Canvas, props: Map<String, Any?>) {
        if (props.containsKey("matrix.m00") || props.containsKey("m00")) {
            val matrix = Matrix()
            matrix.setValues(floatArrayOf(
                asFloat(props["matrix.m00"] ?: props["m00"], 1f),
                asFloat(props["matrix.m01"] ?: props["m01"]),
                asFloat(props["matrix.tx"] ?: props["tx"]),
                asFloat(props["matrix.m10"] ?: props["m10"]),
                asFloat(props["matrix.m11"] ?: props["m11"], 1f),
                asFloat(props["matrix.ty"] ?: props["ty"]),
                0f, 0f, 1f,
            ))
            canvas.concat(matrix)
            return
        }
        val x = asFloat(props["x"] ?: props["transform.x"] ?: props["translateX"])
        val y = asFloat(props["y"] ?: props["transform.y"] ?: props["translateY"])
        val sx = asFloat(props["scaleX"] ?: props["transform.scaleX"] ?: props["scale"], 1f)
        val sy = asFloat(props["scaleY"] ?: props["transform.scaleY"] ?: props["scale"], 1f)
        val angle = asFloat(props["rotation"] ?: props["transform.rotation"])
        val originX = asFloat(props["transform.originX"] ?: props["originX"])
        val originY = asFloat(props["transform.originY"] ?: props["originY"])
        canvas.translate(x + originX, y + originY)
        if (angle != 0f) canvas.rotate(angle)
        if (sx != 1f || sy != 1f) canvas.scale(sx, sy)
        if (originX != 0f || originY != 0f) canvas.translate(-originX, -originY)
    }

    private fun clip(canvas: Canvas, props: Map<String, Any?>) {
        val points = props["clip.points"] ?: props["mask.points"]
        if (points is JSONArray) {
            val path = pointsPath(points)
            if (path != null) { canvas.clipPath(path); return }
        }
        if (props.containsKey("clip.left") || props.containsKey("clip.right")) {
            canvas.clipRect(
                asFloat(props["clip.left"]), asFloat(props["clip.top"]),
                asFloat(props["clip.right"], width.toFloat()),
                asFloat(props["clip.bottom"], height.toFloat()),
            )
        } else if (props.containsKey("clip.width") && props.containsKey("clip.height")) {
            val x = asFloat(props["clip.x"]); val y = asFloat(props["clip.y"])
            canvas.clipRect(x, y, x + asFloat(props["clip.width"]), y + asFloat(props["clip.height"]))
        }
    }

    private fun vector(canvas: Canvas, type: String, props: Map<String, Any?>, resource: JSONObject, opacity: Float) {
        paint.reset()
        paint.isAntiAlias = asBool(props["antialias"], true)
        val c = color(props["color"]?.toString() ?: props["fill"]?.toString()
            ?: resource.optString("color", resource.optString("fill", "#ffffff")), Color.WHITE)
        paint.color = c
        paint.alpha = (Color.alpha(c) * opacity).toInt().coerceIn(0, 255)
        paint.style = if (asBool(props["stroke"], false) || resource.optString("style") == "stroke") Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = asFloat(props["strokeWidth"], resource.optDouble("strokeWidth", 1.0).toFloat())
        val x = resource.optDouble("x", 0.0).toFloat()
        val y = resource.optDouble("y", 0.0).toFloat()
        val w = asFloat(props["width"] ?: props["geometry.width"], resource.optDouble("width", 0.0).toFloat())
        val h = asFloat(props["height"] ?: props["geometry.height"], resource.optDouble("height", 0.0).toFloat())
        when (type) {
            "rect" -> {
                val radius = asFloat(props["radius"] ?: props["cornerRadius"], resource.optDouble("radius", 0.0).toFloat())
                if (radius > 0f) canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, paint)
                else canvas.drawRect(x, y, x + w, y + h, paint)
            }
            "ellipse" -> canvas.drawOval(RectF(x, y, x + w, y + h), paint)
            "polygon" -> {
                val path = pointsPath(props["points"] as? JSONArray ?: props["geometry.points"] as? JSONArray
                    ?: resource.optJSONArray("points"))
                if (path != null) canvas.drawPath(path, paint)
            }
        }
    }

    private fun pointsPath(points: JSONArray?): Path? {
        if (points == null || points.length() < 3) return null
        val path = Path()
        for (i in 0 until points.length()) {
            val point = points.opt(i)
            val x: Float; val y: Float
            if (point is JSONArray) { x = point.optDouble(0).toFloat(); y = point.optDouble(1).toFloat() }
            else if (point is JSONObject) { x = point.optDouble("x").toFloat(); y = point.optDouble("y").toFloat() }
            else return null
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun text(canvas: Canvas, props: Map<String, Any?>, resource: JSONObject, opacity: Float) {
        val value = props["text"]?.toString() ?: props["value"]?.toString() ?: resource.optString("text")
        if (value.isBlank()) return
        val c = color(props["color"]?.toString() ?: props["material.color"]?.toString()
            ?: resource.optString("color", "#ffffff"), Color.WHITE)
        paint.reset()
        paint.isAntiAlias = true
        paint.color = c
        paint.alpha = (Color.alpha(c) * opacity).toInt().coerceIn(0, 255)
        paint.textSize = max(1f, asFloat(props["size"] ?: props["textSize"], resource.optDouble("size", 32.0).toFloat()))
        if (asBool(props["bold"], resource.optBoolean("bold", false))) paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText(value, resource.optDouble("x", 0.0).toFloat(), resource.optDouble("y", 0.0).toFloat(), paint)
    }

    private fun bitmap(canvas: Canvas, props: Map<String, Any?>, resource: JSONObject, opacity: Float) {
        val source = props["source"]?.toString() ?: props["asset"]?.toString()
            ?: props["relativeAsset"]?.toString() ?: resource.optString("source",
                resource.optString("asset", resource.optString("relativeAsset")))
        if (source.isBlank()) return
        val image = getImage(source) ?: error("Renderer3 asset unavailable: " + source)
        val left = asFloat(props["source.left"] ?: props["crop.left"], resource.optDouble("sourceLeft", 0.0).toFloat()).toInt().coerceIn(0, image.width)
        val top = asFloat(props["source.top"] ?: props["crop.top"], resource.optDouble("sourceTop", 0.0).toFloat()).toInt().coerceIn(0, image.height)
        val right = asFloat(props["source.right"] ?: props["crop.right"], resource.optDouble("sourceRight", image.width.toDouble()).toFloat()).toInt().coerceIn(left, image.width)
        val bottom = asFloat(props["source.bottom"] ?: props["crop.bottom"], resource.optDouble("sourceBottom", image.height.toDouble()).toFloat()).toInt().coerceIn(top, image.height)
        if (left == right || top == bottom) return
        val x = resource.optDouble("x", 0.0).toFloat()
        val y = resource.optDouble("y", 0.0).toFloat()
        val w = asFloat(props["width"], resource.optDouble("width", (right - left).toDouble()).toFloat())
        val h = asFloat(props["height"], resource.optDouble("height", (bottom - top).toDouble()).toFloat())
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.alpha = (255 * opacity).toInt().coerceIn(0, 255)
        canvas.drawBitmap(image, Rect(left, top, right, bottom), RectF(x, y, x + w, y + h), paint)
    }

    private fun getImage(path: String): Bitmap? {
        images[path]?.let { if (!it.isRecycled) return it }
        val bytes = bundle.asset(path) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Invalid Renderer3 image: " + path }
        var sample = 1
        while (max(options.outWidth, options.outHeight) / (sample * 2) >= 1536) sample *= 2
        val actual = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, actual)
            ?: error("Renderer3 image could not be decoded: " + path)
        images[path] = decoded
        return decoded
    }

    private fun color(raw: String, fallback: Int): Int = runCatching { Color.parseColor(raw) }.getOrDefault(fallback)
    private fun asFloat(value: Any?, fallback: Float = 0f): Float {
        val parsed = when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }
        return parsed?.takeIf { it.isFinite() } ?: fallback
    }
    private fun asBool(value: Any?, fallback: Boolean): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", true)
        else -> fallback
    }
}
