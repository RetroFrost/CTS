package io.github.retrofrost.cts.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Renderer API v3 scene runtime.
 *
 * V3 is deliberately data-only: no dex/native/script payload is executed. A renderer
 * may be a raw CCRNDR03 container or a ZIP package containing one .renderer3 file and
 * sidecar resources. Sidecar bytes are addressable by their relative package path.
 */
data class RendererV3Canvas(
    val width: Int,
    val height: Int,
    val fps: Int,
)

data class RendererV3Timeline(
    val frames: Int,
    val clock: String = "absolute",
    val implicitAnimation: Boolean = false,
)

data class RendererV3Object(
    val id: String,
    val kind: String,
    val frame: Int,
    val resource: String?,
    val lifespanStart: Int,
    val lifespanEnd: Int,
    val properties: JSONObject,
    val raw: JSONObject,
)

data class RendererV3Selector(
    val rawSelector: String,
    val kind: String,
    val conditions: List<RendererV3Condition>,
    val specificity: Int,
    val sourceOrder: Int,
    val timeline: String,
    val properties: JSONObject,
)

data class RendererV3Condition(
    val key: String,
    val op: String,
    val value: Any,
)

data class RendererV3Scene(
    val api: Int,
    val id: String,
    val name: String,
    val author: String,
    val canvas: RendererV3Canvas,
    val timeline: RendererV3Timeline,
    val features: List<String>,
    val resources: JSONObject,
    val objects: List<RendererV3Object>,
    val selectors: List<RendererV3Selector>,
    val layers: List<String>,
    val checkpoints: List<Int>,
    val raw: JSONObject,
    val assets: Map<String, ByteArray> = emptyMap(),
    val originalBytes: ByteArray = ByteArray(0),
) {
    fun resource(id: String?): JSONObject? = id?.let { resources.optJSONObject(it) }

    fun asset(path: String?): ByteArray? {
        val normalized = path?.replace('\\', '/')?.removePrefix("./") ?: return null
        return assets[normalized]
            ?: assets.entries.firstOrNull { it.key.endsWith("/$normalized") }?.value
    }

    fun objectById(id: String): RendererV3Object? = objects.firstOrNull { it.id == id }
}

data class RendererV3ReadResult(
    val spec: RendererSpec,
    val scene: RendererV3Scene,
)

object RendererV3Runtime {
    private val scenes = ConcurrentHashMap<String, RendererV3Scene>()

    fun register(scene: RendererV3Scene) {
        scenes[scene.id] = scene
    }

    fun scene(id: String): RendererV3Scene? = scenes[id]
    fun scene(spec: RendererSpec): RendererV3Scene? = scene(spec.id)
    fun forget(id: String) { scenes.remove(id) }
}

object RendererV3Bundle {
    private const val MAGIC = "CCRNDR03"
    private const val CONTAINER_VERSION = 1
    const val MAX_FILE_BYTES = 128 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    private const val MAX_PACKAGE_ENTRIES = 2048

    fun accepts(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == MAGIC) return true
        return bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    }

    fun read(bytes: ByteArray): RendererV3ReadResult {
        require(bytes.size <= MAX_FILE_BYTES) { "Renderer v3 package is too large." }
        val result = if (bytes.size >= 8 && String(bytes, 0, 8, Charsets.US_ASCII) == MAGIC) {
            parseContainer(bytes, emptyMap(), bytes)
        } else {
            parsePackage(bytes)
        }
        RendererV3Runtime.register(result.scene)
        return result
    }

    fun write(scene: RendererV3Scene, output: OutputStream) {
        if (scene.originalBytes.isNotEmpty()) {
            output.write(scene.originalBytes)
            return
        }
        val raw = scene.raw.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_MANIFEST_BYTES) { "Renderer v3 manifest is too large." }
        val payload = ByteArrayOutputStream().use { destination ->
            GZIPOutputStream(destination).use { it.write(raw) }
            destination.toByteArray()
        }
        val crc = CRC32().apply { update(payload) }.value
        DataOutputStream(output).use { data ->
            data.write(MAGIC.toByteArray(Charsets.US_ASCII))
            data.writeInt(CONTAINER_VERSION)
            data.writeInt(payload.size)
            data.writeInt(crc.toInt())
            data.write(payload)
        }
    }

    private fun parsePackage(packageBytes: ByteArray): RendererV3ReadResult {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count += 1
                require(count <= MAX_PACKAGE_ENTRIES) { "Renderer v3 package contains too many files." }
                val name = safeEntryName(entry.name)
                val bytes = readLimited(zip, MAX_FILE_BYTES)
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        val sceneEntry = entries.entries.firstOrNull { it.key.equals("renderer.renderer3", true) }
            ?: entries.entries.firstOrNull { it.key.equals("manifest.renderer3", true) }
            ?: entries.entries.firstOrNull { it.key.endsWith(".renderer3", true) }
            ?: error("Renderer v3 ZIP has no .renderer3 scene file.")
        val sidecars = entries.filterKeys { it != sceneEntry.key }
        return parseContainer(sceneEntry.value, sidecars, packageBytes)
    }

    private fun safeEntryName(value: String): String {
        val normalized = value.replace('\\', '/').removePrefix("/")
        require(normalized.isNotBlank() && !normalized.split('/').any { it == ".." }) {
            "Unsafe renderer v3 package path."
        }
        return normalized
    }

    private fun parseContainer(
        container: ByteArray,
        assets: Map<String, ByteArray>,
        originalBytes: ByteArray,
    ): RendererV3ReadResult {
        val data = DataInputStream(ByteArrayInputStream(container))
        val magic = ByteArray(8)
        data.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not a Cubical Compare .renderer3 file." }
        require(data.readInt() == CONTAINER_VERSION) { "Unsupported .renderer3 container version." }
        val length = data.readInt()
        require(length in 1..MAX_FILE_BYTES) { "Invalid Renderer v3 payload length." }
        val expected = data.readInt().toLong() and 0xffffffffL
        val payload = ByteArray(length)
        data.readFully(payload)
        require(data.read() == -1) { "Unexpected trailing Renderer v3 data." }
        val actual = CRC32().apply { update(payload) }.value
        require(actual == expected) { "Renderer v3 checksum failed." }
        val manifest = GZIPInputStream(ByteArrayInputStream(payload)).use {
            readLimited(it, MAX_MANIFEST_BYTES)
        }
        val root = JSONObject(String(manifest, Charsets.UTF_8))
        val scene = parseScene(root, assets, originalBytes)
        return RendererV3ReadResult(specFor(scene), scene)
    }

    private fun parseScene(root: JSONObject, assets: Map<String, ByteArray>, originalBytes: ByteArray): RendererV3Scene {
        val release = root.optJSONObject("releaseTheme")
        val api = root.optInt("api", root.optInt("rendererApi", release?.optInt("apiVersion", 3) ?: 3))
        require(api == 3) { "Renderer scene requires API $api; this loader handles API 3." }
        val id = root.optString("id").ifBlank { error("Renderer v3 scene has no id.") }
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid Renderer v3 id." }
        val name = root.optString("name", id).ifBlank { id }
        val author = root.optString("author", "Cubical Compare")

        val canvasJson = root.optJSONObject("canvas")
        val referenceJson = root.optJSONObject("reference")
        val width = canvasJson?.optInt("width", referenceJson?.optInt("width", 1920) ?: 1920)
            ?: referenceJson?.optInt("width", 1920) ?: 1920
        val height = canvasJson?.optInt("height", referenceJson?.optInt("height", 1080) ?: 1080)
            ?: referenceJson?.optInt("height", 1080) ?: 1080
        val fps = canvasJson?.optInt("fps", referenceJson?.optInt("fps", 60) ?: 60)
            ?: referenceJson?.optInt("fps", 60) ?: 60
        require(width in 1..16384 && height in 1..16384 && fps in 1..240) { "Invalid Renderer v3 canvas." }

        val timelineJson = root.optJSONObject("timeline") ?: JSONObject()
        val frames = timelineJson.optInt("frames", referenceJson?.optInt("frameCount", 0) ?: 0)
        require(frames > 0) { "Renderer v3 timeline must declare frames." }
        val clock = timelineJson.optString("clock", "absolute")
        val implicit = timelineJson.optBoolean("implicitAnimation", false)
        require(clock == "absolute") { "Renderer v3 requires an absolute integer frame clock." }
        require(!implicit) { "Renderer v3 implicit animation must be disabled." }

        val features = stringArray(root.optJSONArray("features")) + stringArray(root.optJSONArray("requiredFeatures"))
        val resources = root.optJSONObject("resources") ?: JSONObject()
        val objectsJson = root.optJSONArray("objects") ?: JSONArray()
        val objects = List(objectsJson.length()) { index ->
            val item = objectsJson.getJSONObject(index)
            val frame = item.optInt("frame", 0)
            val life = item.optJSONObject("lifespan") ?: JSONObject()
            val start = life.optInt("start", frame)
            val end = life.optInt("end", frames - 1)
            require(start in 0 until frames && end in start until frames) { "Invalid Renderer v3 object lifespan." }
            RendererV3Object(
                id = item.optString("id").ifBlank { "object-$index" },
                kind = item.optString("kind").ifBlank { "custom" },
                frame = frame,
                resource = item.optString("resource").takeIf { it.isNotBlank() },
                lifespanStart = start,
                lifespanEnd = end,
                properties = item.optJSONObject("properties") ?: JSONObject(),
                raw = item,
            )
        }
        require(objects.map { it.id }.distinct().size == objects.size) { "Renderer v3 object ids must be unique." }

        val selectorsJson = root.optJSONArray("selectors") ?: JSONArray()
        val selectors = List(selectorsJson.length()) { index ->
            parseSelector(selectorsJson.getJSONObject(index), index)
        }
        val layers = stringArray(root.optJSONArray("layers"))
        val checkpointsJson = root.optJSONArray("checkpoints") ?: JSONArray()
        val checkpoints = buildList {
            repeat(checkpointsJson.length()) { index ->
                val item = checkpointsJson.opt(index)
                when (item) {
                    is Number -> add(item.toInt())
                    is JSONObject -> add(item.optInt("frame", -1))
                }
            }
        }.filter { it in 0 until frames }

        return RendererV3Scene(
            api = api,
            id = id,
            name = name,
            author = author,
            canvas = RendererV3Canvas(width, height, fps),
            timeline = RendererV3Timeline(frames, clock, implicit),
            features = features.distinct(),
            resources = resources,
            objects = objects,
            selectors = selectors,
            layers = layers,
            checkpoints = checkpoints,
            raw = root,
            assets = assets,
            originalBytes = originalBytes,
        )
    }

    private fun specFor(scene: RendererV3Scene): RendererSpec {
        val geometry = scene.raw.optJSONObject("geometry") ?: JSONObject()
        val minimum = scene.raw.optString("minAppVersion", "3.0.300")
        return RendererSpec(
            id = scene.id,
            name = scene.name,
            author = scene.author,
            formatVersion = 3,
            rendererApi = 3,
            engine = "scene-v3",
            precisionMode = "frame-exact",
            timelineUnit = "frames",
            minAppVersion = minimum,
            referenceWidth = scene.canvas.width,
            referenceHeight = scene.canvas.height,
            referenceFps = scene.canvas.fps,
            canonicalCardCount = scene.raw.optJSONObject("reference")?.optInt("cardCount", scene.objects.count { it.kind == "card" })
                ?: scene.objects.count { it.kind == "card" },
            canonicalFrameCount = scene.timeline.frames,
            requiredFeatures = scene.features,
            tags = listOf("renderer-api-v3", "scene-v3"),
            previewFrames = scene.checkpoints,
            slotPitch = geometry.optDouble("slotPitch", 476.0).toFloat(),
            bodyInset = geometry.optDouble("bodyInset", 9.0).toFloat(),
            bodyWidth = geometry.optDouble("bodyWidth", 471.0).toFloat(),
            imageHeight = geometry.optDouble("imageHeight", geometry.optDouble("topFieldHeight", 872.0)).toFloat(),
            descriptionTop = geometry.optDouble("descriptionTop", 965.0).toFloat(),
        )
    }

    private fun parseSelector(item: JSONObject, sourceOrder: Int): RendererV3Selector {
        val raw = item.optString("select").ifBlank { error("Renderer v3 selector is empty.") }
        val match = Regex("^([A-Za-z_][A-Za-z0-9_.-]*)\\[(.*)]$").matchEntire(raw)
            ?: error("Invalid Renderer v3 selector '$raw'.")
        val kind = match.groupValues[1]
        val body = match.groupValues[2].trim()
        val conditions = mutableListOf<RendererV3Condition>()
        var specificity = 100
        if (body.isNotBlank() && body != "*") {
            body.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach { token ->
                if (token.startsWith("frame=") && token.contains("..")) {
                    val range = token.removePrefix("frame=").split("..", limit = 2)
                    conditions += RendererV3Condition("frame", ">=", range[0].toInt())
                    conditions += RendererV3Condition("frame", "<=", range[1].toInt())
                    specificity = max(specificity, 320)
                } else {
                    val cond = Regex("^([A-Za-z_][A-Za-z0-9_.-]*)(>=|<=|!=|=|>|<)(.+)$").matchEntire(token)
                        ?: error("Invalid Renderer v3 selector condition '$token'.")
                    val key = cond.groupValues[1]
                    val op = cond.groupValues[2]
                    val value = scalar(cond.groupValues[3])
                    conditions += RendererV3Condition(key, op, value)
                    specificity = max(specificity, when {
                        key == "frame" && op == "=" -> 500
                        key in setOf("every", "from", "to") -> 360
                        key == "frame" -> 280
                        else -> 220
                    })
                }
            }
        }
        return RendererV3Selector(
            rawSelector = raw,
            kind = kind,
            conditions = conditions,
            specificity = specificity + conditions.size,
            sourceOrder = sourceOrder,
            timeline = item.optString("timeline", "relative"),
            properties = item.optJSONObject("properties") ?: JSONObject(),
        )
    }

    private fun scalar(value: String): Any {
        val v = value.trim()
        return v.toIntOrNull() ?: v.toDoubleOrNull() ?: when (v.lowercase()) {
            "true" -> true
            "false" -> false
            else -> v
        }
    }

    private fun stringArray(array: JSONArray?): List<String> = if (array == null) emptyList() else
        List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Renderer v3 data is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

/** Property cascade + frame evaluator shared by preview and export. */
object RendererV3Evaluator {
    private data class Winner(val specificity: Int, val order: Int, val value: Any?, val timeline: String)

    fun properties(scene: RendererV3Scene, obj: RendererV3Object, frame: Int): Map<String, Any?> {
        if (frame !in obj.lifespanStart..obj.lifespanEnd) return emptyMap()
        val winners = linkedMapOf<String, Winner>()

        scene.resource(obj.resource)?.optJSONObject("properties")?.let { props ->
            flatten(props).forEach { (key, value) -> winners[key] = Winner(0, -1, value, "absolute") }
        }

        scene.selectors.forEach { selector ->
            if (!matches(selector, obj)) return@forEach
            flatten(selector.properties).forEach { (key, value) ->
                val existing = winners[key]
                val candidate = Winner(selector.specificity, selector.sourceOrder, value, selector.timeline)
                if (existing == null || candidate.specificity > existing.specificity ||
                    (candidate.specificity == existing.specificity && candidate.order >= existing.order)) {
                    winners[key] = candidate
                }
            }
        }

        flatten(obj.properties).forEach { (key, value) ->
            winners[key] = Winner(1000, Int.MAX_VALUE, value, obj.raw.optString("timeline", "relative"))
        }

        return buildMap {
            winners.forEach { (key, winner) ->
                val evaluated = evaluate(winner.value, frame, obj.frame, winner.timeline)
                if (evaluated !== Unset) put(key, evaluated)
            }
        }
    }

    fun evaluate(value: Any?, globalFrame: Int, anchorFrame: Int, defaultTimeline: String = "absolute"): Any? {
        if (value !is JSONObject) return jsonValue(value)
        if (value.has("value") && !value.has("track") && !value.has("dense")) return jsonValue(value.opt("value"))
        if (!value.has("track") && !value.has("dense")) return jsonValue(value)

        val timeline = value.optString("timeline", defaultTimeline)
        val frame = if (timeline == "relative") globalFrame - anchorFrame else globalFrame
        val extrapolate = value.optString("extrapolate", "none")
        val interpolation = value.optString("interpolation", "raw")

        if (value.has("dense")) {
            val dense = value.opt("dense")
            val start: Int
            val values: JSONArray
            when (dense) {
                is JSONArray -> {
                    start = value.optInt("start", 0)
                    values = dense
                }
                is JSONObject -> {
                    start = dense.optInt("start", 0)
                    values = dense.optJSONArray("values") ?: return Unset
                }
                else -> return Unset
            }
            if (values.length() == 0) return Unset
            val index = frame - start
            if (index in 0 until values.length()) return jsonValue(values.opt(index))
            if (extrapolate == "hold") return jsonValue(values.opt(if (index < 0) 0 else values.length() - 1))
            return Unset
        }

        val track = value.optJSONArray("track") ?: return Unset
        if (track.length() == 0) return Unset
        val keys = List(track.length()) { index ->
            val pair = track.optJSONArray(index) ?: JSONArray()
            pair.optInt(0) to pair.opt(1)
        }.sortedBy { it.first }
        if (frame < keys.first().first) return if (extrapolate == "hold") jsonValue(keys.first().second) else Unset
        if (frame > keys.last().first) return if (extrapolate == "hold") jsonValue(keys.last().second) else Unset
        keys.firstOrNull { it.first == frame }?.let { return jsonValue(it.second) }
        if (interpolation == "raw") return Unset
        val rightIndex = keys.indexOfFirst { it.first > frame }
        if (rightIndex <= 0) return Unset
        val left = keys[rightIndex - 1]
        val right = keys[rightIndex]
        if (interpolation == "hold") return jsonValue(left.second)
        val lv = (left.second as? Number)?.toDouble() ?: return Unset
        val rv = (right.second as? Number)?.toDouble() ?: return Unset
        val raw = (frame - left.first).toDouble() / max(1, right.first - left.first)
        val p = when (interpolation) {
            "smoothstep" -> raw * raw * (3.0 - 2.0 * raw)
            "cubic-in" -> raw * raw * raw
            "cubic-out" -> 1.0 - (1.0 - raw) * (1.0 - raw) * (1.0 - raw)
            "cubic-in-out" -> if (raw < 0.5) 4.0 * raw * raw * raw else 1.0 - Math.pow(-2.0 * raw + 2.0, 3.0) / 2.0
            else -> raw
        }
        return lv + (rv - lv) * p
    }

    fun flatten(root: JSONObject, prefix: String = ""): Map<String, Any?> = buildMap {
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = root.opt(key)
            val path = if (prefix.isBlank()) key else "$prefix.$key"
            if (value is JSONObject && !isTrackDescriptor(value)) {
                putAll(flatten(value, path))
            } else {
                put(path, value)
            }
        }
    }

    private fun isTrackDescriptor(value: JSONObject): Boolean =
        value.has("track") || value.has("dense") || value.has("value")

    private fun matches(selector: RendererV3Selector, obj: RendererV3Object): Boolean {
        if (selector.kind != obj.kind) return false
        val every = selector.conditions.firstOrNull { it.key == "every" && it.op == "=" }?.value as? Number
        val from = (selector.conditions.firstOrNull { it.key == "from" && it.op == "=" }?.value as? Number)?.toInt() ?: 0
        val to = (selector.conditions.firstOrNull { it.key == "to" && it.op == "=" }?.value as? Number)?.toInt()
        if (every != null) {
            val step = every.toInt()
            if (step <= 0 || obj.frame < from || (to != null && obj.frame > to) || (obj.frame - from) % step != 0) return false
        }
        return selector.conditions.filterNot { it.key in setOf("every", "from", "to") }.all { condition ->
            val lhs: Any? = when (condition.key) {
                "frame" -> obj.frame
                "id" -> obj.id
                "kind" -> obj.kind
                else -> obj.raw.opt(condition.key)
            }
            compare(lhs, condition.op, condition.value)
        }
    }

    private fun compare(lhs: Any?, op: String, rhs: Any): Boolean {
        if (op == "=") return lhs.toString() == rhs.toString()
        if (op == "!=") return lhs.toString() != rhs.toString()
        val l = (lhs as? Number)?.toDouble() ?: return false
        val r = (rhs as? Number)?.toDouble() ?: return false
        return when (op) {
            ">=" -> l >= r
            "<=" -> l <= r
            ">" -> l > r
            "<" -> l < r
            else -> false
        }
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONArray -> List(value.length()) { jsonValue(value.opt(it)) }
        is JSONObject -> value
        else -> value
    }

    private object Unset
}
