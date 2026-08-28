package io.github.retrofrost.cts.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Declarative renderer package. It never loads executable code. */
data class RendererKeyframe(
    val timeMs: Int,
    val value: Float,
    val easing: String = "linear",
)

data class RendererTrack(
    val target: String,
    val keyframes: List<RendererKeyframe>,
) {
    fun valueAt(timeMs: Int): Float? {
        if (keyframes.isEmpty()) return null
        if (timeMs <= keyframes.first().timeMs) return keyframes.first().value
        if (timeMs >= keyframes.last().timeMs) return keyframes.last().value
        for (index in 1 until keyframes.size) {
            val right = keyframes[index]
            if (timeMs <= right.timeMs) {
                val left = keyframes[index - 1]
                val span = (right.timeMs - left.timeMs).coerceAtLeast(1)
                val raw = ((timeMs - left.timeMs).toFloat() / span).coerceIn(0f, 1f)
                val p = easing(raw, right.easing)
                return left.value + (right.value - left.value) * p
            }
        }
        return keyframes.last().value
    }

    private fun easing(x: Float, name: String): Float = when (name.lowercase()) {
        "ease-in", "easein" -> x * x
        "ease-out", "easeout" -> 1f - (1f - x) * (1f - x)
        "ease-in-out", "easeinout", "smoothstep" -> x * x * (3f - 2f * x)
        "cubic-in" -> x * x * x
        "cubic-out" -> 1f - (1f - x) * (1f - x) * (1f - x)
        "hold", "step" -> if (x < 1f) 0f else 1f
        else -> x
    }
}

data class RendererSpec(
    val id: String = "cubical.2.0.7.native",
    val name: String = "Cubical Compare 2.0.7 Native",
    val author: String = "Cubical Compare",
    val formatVersion: Int = 1,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val titleBackgroundColor: Int = 0xFFF2F2F2.toInt(),
    val descriptionBackgroundColor: Int = 0xFF635E57.toInt(),
    val titleTextColor: Int = 0xFF161616.toInt(),
    val descriptionTextColor: Int = 0xFFFAFAF8.toInt(),
    val badgeColor: Int = 0xFFD3070D.toInt(),
    val badgeDarkColor: Int = 0xFFA60008.toInt(),
    val badgeTextColor: Int = 0xFFFFFFFF.toInt(),
    val shineColor: Int = 0x70FFFFFF,
    val slotPitch: Float = 476f,
    val bodyInset: Float = 9f,
    val bodyWidth: Float = 471f,
    val imageHeight: Float = 872f,
    val titleHeight: Float = 93f,
    val descriptionTop: Float = 965f,
    val titleTextSize: Float = 45f,
    val descriptionTextSize: Float = 26f,
    val badgeCenterX: Float = 240f,
    val badgeCenterY: Float = 198f,
    val badgeScale: Float = 1f,
    val badgeHeaderSize: Float = 22f,
    val badgeValueSize: Float = 40f,
    val badgeUnitSize: Float = 24f,
    val openingStarts: List<Int> = listOf(0, 120, 240, 360),
    val openingEnds: List<Int> = listOf(120, 240, 360, 528),
    val continuousStartFrame: Int = 528,
    val continuousStepFrames: Int = 214,
    val bodySlideFrames: Int = 80,
    val laterBadgeFallStartFrame: Int = 122,
    val laterBadgeFallEndFrame: Int = 206,
    val shineStartFrame: Int = 131,
    val shineFrames: Int = 43,
    val endWipeFrames: Int = 43,
    val endRiseFrames: Int = 11,
    val endHoldFrames: Int = 268,
    val fadeFrames: Int = 79,
    val blackTailFrames: Int = 8,
    val tracks: List<RendererTrack> = emptyList(),
) {
    private val tracksByTarget by lazy { tracks.associateBy { it.target } }

    fun track(target: String, timeMs: Int): Float? {
        tracksByTarget[target]?.valueAt(timeMs)?.let { return it }
        val pieces = target.split('.')
        if (pieces.size >= 3 && pieces[0] == "card") {
            tracksByTarget["card.*.${pieces.drop(2).joinToString(".")}"]?.valueAt(timeMs)?.let { return it }
        }
        return null
    }

    val outroFrames: Int
        get() = endWipeFrames + endRiseFrames + endHoldFrames + fadeFrames + blackTailFrames

    companion object {
        fun builtIn(): RendererSpec = RendererSpec()
    }
}

object RendererRuntime {
    @Volatile var active: RendererSpec = RendererSpec.builtIn()
}

class RendererStore(private val context: Context) {
    private val dir = File(context.filesDir, "renderers")
    private val activeFile = File(dir, "active.renderer")

    fun active(): RendererSpec = if (activeFile.isFile) {
        runCatching { activeFile.inputStream().use(RendererBundle::read) }
            .getOrElse { RendererSpec.builtIn() }
    } else {
        RendererSpec.builtIn()
    }

    fun import(input: InputStream): RendererSpec {
        val bytes = readLimited(input, RendererBundle.MAX_FILE_BYTES)
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        dir.mkdirs()
        val tmp = File(dir, "active.renderer.tmp")
        tmp.outputStream().use { it.write(bytes) }
        if (!tmp.renameTo(activeFile)) tmp.copyTo(activeFile, overwrite = true)
        tmp.delete()
        RendererRuntime.active = spec
        return spec
    }

    fun reset(): RendererSpec {
        activeFile.delete()
        return RendererSpec.builtIn().also { RendererRuntime.active = it }
    }

    fun export(output: OutputStream) = RendererBundle.write(active(), output)

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Renderer file is too large." }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
}

object RendererBundle {
    private const val MAGIC = "CCRNDR01"
    private const val CONTAINER_VERSION = 1
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024

    fun write(spec: RendererSpec, output: OutputStream) {
        validate(spec)
        val manifest = toJson(spec).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Renderer manifest is too large." }
        val payload = ByteArrayOutputStream().use { destination ->
            GZIPOutputStream(destination).use { it.write(manifest) }
            destination.toByteArray()
        }
        require(payload.size + 20 <= MAX_FILE_BYTES) { "Renderer bundle is too large." }
        val crc = CRC32().apply { update(payload) }.value
        DataOutputStream(output).use { data ->
            data.write(MAGIC.toByteArray(Charsets.US_ASCII))
            data.writeInt(CONTAINER_VERSION)
            data.writeInt(payload.size)
            data.writeInt(crc.toInt())
            data.write(payload)
        }
    }

    fun read(input: InputStream): RendererSpec {
        val data = DataInputStream(input)
        val magic = ByteArray(8)
        data.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not a Cubical Compare .renderer file." }
        require(data.readInt() == CONTAINER_VERSION) { "Unsupported .renderer container version." }
        val length = data.readInt()
        require(length in 1..(MAX_FILE_BYTES - 20)) { "Invalid renderer payload length." }
        val expected = data.readInt().toLong() and 0xffffffffL
        val payload = ByteArray(length)
        data.readFully(payload)
        require(data.read() == -1) { "Unexpected trailing renderer data." }
        val actual = CRC32().apply { update(payload) }.value
        require(actual == expected) { "Renderer checksum failed." }
        val manifest = GZIPInputStream(ByteArrayInputStream(payload)).use { zipped ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val count = zipped.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_MANIFEST_BYTES) { "Renderer manifest is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return fromJson(JSONObject(String(manifest, Charsets.UTF_8))).also(::validate)
    }

    private fun validate(spec: RendererSpec) {
        require(spec.id.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Invalid renderer id." }
        require(spec.name.isNotBlank() && spec.name.length <= 120) { "Invalid renderer name." }
        require(spec.author.length <= 120) { "Invalid renderer author." }
        require(spec.formatVersion == 1) { "Unsupported renderer schema." }
        require(spec.slotPitch in 100f..2000f && spec.bodyWidth in 100f..2000f) { "Invalid card geometry." }
        require(spec.imageHeight in 0f..2000f && spec.descriptionTop in 0f..2000f) { "Invalid card layout." }
        require(spec.badgeScale in 0.1f..4f) { "Invalid badge scale." }
        require(spec.bodySlideFrames in 1..7200) { "Invalid body timing." }
        require(spec.continuousStepFrames in 1..7200) { "Invalid conveyor timing." }
        require(spec.tracks.size <= 256) { "Too many renderer tracks." }
        spec.tracks.forEach { track ->
            require(track.target.matches(Regex("[A-Za-z0-9.*_-]{1,96}"))) { "Invalid renderer track target." }
            require(track.keyframes.size in 1..4096) { "Invalid renderer keyframe count." }
            var previous = -1
            track.keyframes.forEach { frame ->
                require(frame.timeMs >= previous && frame.value.isFinite()) { "Invalid renderer keyframe." }
                previous = frame.timeMs
            }
        }
    }

    private fun toJson(s: RendererSpec) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("author", s.author); put("formatVersion", s.formatVersion)
        put("backgroundColor", s.backgroundColor); put("titleBackgroundColor", s.titleBackgroundColor)
        put("descriptionBackgroundColor", s.descriptionBackgroundColor); put("titleTextColor", s.titleTextColor)
        put("descriptionTextColor", s.descriptionTextColor); put("badgeColor", s.badgeColor)
        put("badgeDarkColor", s.badgeDarkColor); put("badgeTextColor", s.badgeTextColor); put("shineColor", s.shineColor)
        put("slotPitch", s.slotPitch.toDouble()); put("bodyInset", s.bodyInset.toDouble()); put("bodyWidth", s.bodyWidth.toDouble())
        put("imageHeight", s.imageHeight.toDouble()); put("titleHeight", s.titleHeight.toDouble()); put("descriptionTop", s.descriptionTop.toDouble())
        put("titleTextSize", s.titleTextSize.toDouble()); put("descriptionTextSize", s.descriptionTextSize.toDouble())
        put("badgeCenterX", s.badgeCenterX.toDouble()); put("badgeCenterY", s.badgeCenterY.toDouble()); put("badgeScale", s.badgeScale.toDouble())
        put("badgeHeaderSize", s.badgeHeaderSize.toDouble()); put("badgeValueSize", s.badgeValueSize.toDouble()); put("badgeUnitSize", s.badgeUnitSize.toDouble())
        put("openingStarts", JSONArray(s.openingStarts)); put("openingEnds", JSONArray(s.openingEnds))
        put("continuousStartFrame", s.continuousStartFrame); put("continuousStepFrames", s.continuousStepFrames); put("bodySlideFrames", s.bodySlideFrames)
        put("laterBadgeFallStartFrame", s.laterBadgeFallStartFrame); put("laterBadgeFallEndFrame", s.laterBadgeFallEndFrame)
        put("shineStartFrame", s.shineStartFrame); put("shineFrames", s.shineFrames)
        put("endWipeFrames", s.endWipeFrames); put("endRiseFrames", s.endRiseFrames); put("endHoldFrames", s.endHoldFrames)
        put("fadeFrames", s.fadeFrames); put("blackTailFrames", s.blackTailFrames)
        put("tracks", JSONArray().apply {
            s.tracks.forEach { track ->
                put(JSONObject().apply {
                    put("target", track.target)
                    put("keyframes", JSONArray().apply {
                        track.keyframes.forEach { key ->
                            put(JSONObject().put("timeMs", key.timeMs).put("value", key.value.toDouble()).put("easing", key.easing))
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(j: JSONObject): RendererSpec {
        val d = RendererSpec.builtIn()
        fun f(key: String, fallback: Float) = j.optDouble(key, fallback.toDouble()).toFloat()
        fun c(key: String, fallback: Int) = if (j.has(key)) j.optLong(key, fallback.toLong()).toInt() else fallback
        fun ints(key: String, fallback: List<Int>): List<Int> {
            val array = j.optJSONArray(key) ?: return fallback
            return List(array.length()) { array.optInt(it) }
        }
        val tracks = mutableListOf<RendererTrack>()
        val trackArray = j.optJSONArray("tracks") ?: JSONArray()
        repeat(trackArray.length()) { index ->
            val item = trackArray.getJSONObject(index)
            val frames = item.optJSONArray("keyframes") ?: JSONArray()
            val keys = List(frames.length()) { k ->
                val frame = frames.getJSONObject(k)
                RendererKeyframe(frame.getInt("timeMs"), frame.getDouble("value").toFloat(), frame.optString("easing", "linear"))
            }
            tracks += RendererTrack(item.getString("target"), keys)
        }
        return RendererSpec(
            id = j.optString("id", d.id), name = j.optString("name", d.name), author = j.optString("author", d.author),
            formatVersion = j.optInt("formatVersion", d.formatVersion), backgroundColor = c("backgroundColor", d.backgroundColor),
            titleBackgroundColor = c("titleBackgroundColor", d.titleBackgroundColor), descriptionBackgroundColor = c("descriptionBackgroundColor", d.descriptionBackgroundColor),
            titleTextColor = c("titleTextColor", d.titleTextColor), descriptionTextColor = c("descriptionTextColor", d.descriptionTextColor),
            badgeColor = c("badgeColor", d.badgeColor), badgeDarkColor = c("badgeDarkColor", d.badgeDarkColor), badgeTextColor = c("badgeTextColor", d.badgeTextColor), shineColor = c("shineColor", d.shineColor),
            slotPitch = f("slotPitch", d.slotPitch), bodyInset = f("bodyInset", d.bodyInset), bodyWidth = f("bodyWidth", d.bodyWidth),
            imageHeight = f("imageHeight", d.imageHeight), titleHeight = f("titleHeight", d.titleHeight), descriptionTop = f("descriptionTop", d.descriptionTop),
            titleTextSize = f("titleTextSize", d.titleTextSize), descriptionTextSize = f("descriptionTextSize", d.descriptionTextSize),
            badgeCenterX = f("badgeCenterX", d.badgeCenterX), badgeCenterY = f("badgeCenterY", d.badgeCenterY), badgeScale = f("badgeScale", d.badgeScale),
            badgeHeaderSize = f("badgeHeaderSize", d.badgeHeaderSize), badgeValueSize = f("badgeValueSize", d.badgeValueSize), badgeUnitSize = f("badgeUnitSize", d.badgeUnitSize),
            openingStarts = ints("openingStarts", d.openingStarts), openingEnds = ints("openingEnds", d.openingEnds),
            continuousStartFrame = j.optInt("continuousStartFrame", d.continuousStartFrame), continuousStepFrames = j.optInt("continuousStepFrames", d.continuousStepFrames), bodySlideFrames = j.optInt("bodySlideFrames", d.bodySlideFrames),
            laterBadgeFallStartFrame = j.optInt("laterBadgeFallStartFrame", d.laterBadgeFallStartFrame), laterBadgeFallEndFrame = j.optInt("laterBadgeFallEndFrame", d.laterBadgeFallEndFrame),
            shineStartFrame = j.optInt("shineStartFrame", d.shineStartFrame), shineFrames = j.optInt("shineFrames", d.shineFrames),
            endWipeFrames = j.optInt("endWipeFrames", d.endWipeFrames), endRiseFrames = j.optInt("endRiseFrames", d.endRiseFrames), endHoldFrames = j.optInt("endHoldFrames", d.endHoldFrames),
            fadeFrames = j.optInt("fadeFrames", d.fadeFrames), blackTailFrames = j.optInt("blackTailFrames", d.blackTailFrames), tracks = tracks,
        )
    }
}
