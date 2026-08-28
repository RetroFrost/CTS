package dev.thedataguys.cc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sandboxed, binary renderer bundle used by Cubical Compare.
 *
 * Container v1:
 *   8 bytes  magic: CCRNDR01
 *   4 bytes  container version (big endian)
 *   4 bytes  compressed payload length
 *   4 bytes  CRC32 of compressed payload
 *   N bytes  GZIP-compressed UTF-8 JSON renderer manifest
 *
 * The manifest is declarative only. It cannot load dex/native code or execute commands.
 */
data class RendererKeyframe(
    val timeMs: Int,
    val value: Float,
    val easing: String = "linear"
)

data class RendererTrack(
    val target: String,
    val keyframes: List<RendererKeyframe>
) {
    fun valueAt(timeMs: Int): Float? {
        if (keyframes.isEmpty()) return null
        if (timeMs <= keyframes.first().timeMs) return keyframes.first().value
        if (timeMs >= keyframes.last().timeMs) return keyframes.last().value

        var left = keyframes.first()
        for (i in 1 until keyframes.size) {
            val right = keyframes[i]
            if (timeMs <= right.timeMs) {
                val span = (right.timeMs - left.timeMs).coerceAtLeast(1)
                val raw = ((timeMs - left.timeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                val p = applyEasing(raw, right.easing)
                return left.value + (right.value - left.value) * p
            }
            left = right
        }
        return keyframes.last().value
    }

    private fun applyEasing(x: Float, easing: String): Float = when (easing.lowercase()) {
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
    val id: String = "builtin.reference",
    val name: String = "Built-in Reference Renderer",
    val author: String = "Cubical Compare",
    val formatVersion: Int = 1,
    val backgroundColor: Int = 0xFFFCF4EE.toInt(),
    val headerColor: Int = 0xFF231C1A.toInt(),
    val subtitleColor: Int = 0xFF5C4A42.toInt(),
    val cardColor: Int = 0xFFFFFFFF.toInt(),
    val shadowColor: Int = 0x26000000,
    val badgeColor: Int = 0xDAD30909.toInt(),
    val badgeStrokeColor: Int = 0x52B10507,
    val badgeTextColor: Int = 0xFFFFFFFF.toInt(),
    val shineColor: Int = 0x70FFFFFF,
    val headerTitleSize: Float = 58f,
    val headerSubtitleSize: Float = 31f,
    val headerTitleY: Float = 125f,
    val headerSubtitleY: Float = 176f,
    val cardTop: Float = 360f,
    val cardSideMargin: Float = 88f,
    val cardSpacing: Float = 315f,
    val cardHeight: Float = 255f,
    val cardCornerRadius: Float = 44f,
    val cardShadowX: Float = 10f,
    val cardShadowY: Float = 12f,
    val cardTitleSize: Float = 39f,
    val cardSubtitleSize: Float = 27f,
    val cardTitleX: Float = 46f,
    val cardTitleY: Float = 75f,
    val cardSubtitleY: Float = 120f,
    val badgeRightInset: Float = 150f,
    val badgeTopInset: Float = 86f,
    val badgeRadius: Float = 78f,
    val badgeStrokeWidth: Float = 3f,
    val badgeTextSize: Float = 28f,
    val scrollStartMs: Int = 1100,
    val scrollEndPaddingMs: Int = 3000,
    val openingDurationMs: Int = 5333,
    val openingYOffset: Float = -70f,
    val specialEntryCard: Int = 3,
    val specialEntryXOffset: Float = 430f,
    val shineEnabled: Boolean = true,
    val shineStartMs: Int = 350,
    val shineDurationMs: Int = 900,
    val shineWidth: Float = 54f,
    val tracks: List<RendererTrack> = emptyList()
) {
    private val trackMap: Map<String, RendererTrack> by lazy { tracks.associateBy { it.target } }

    fun trackValue(target: String, timeMs: Int): Float? =
        trackMap[target]?.valueAt(timeMs)
            ?: wildcardTarget(target)?.let { trackMap[it]?.valueAt(timeMs) }

    private fun wildcardTarget(target: String): String? {
        val parts = target.split('.')
        if (parts.size < 3 || parts[0] != "card") return null
        return "card.*.${parts.drop(2).joinToString(".")}"
    }

    companion object {
        fun builtIn(): RendererSpec = RendererSpec()
    }
}

object RendererRuntime {
    @Volatile
    var activeSpec: RendererSpec = RendererSpec.builtIn()
}

class RendererStore(private val context: Context) {
    private val rendererDir = File(context.filesDir, "renderers")
    private val activeFile = File(rendererDir, "active.renderer")

    fun active(): RendererSpec {
        if (!activeFile.isFile) return RendererSpec.builtIn()
        return runCatching {
            FileInputStream(activeFile).use(RendererBundle::read)
        }.getOrElse { RendererSpec.builtIn() }
    }

    fun import(input: InputStream): RendererSpec {
        val bytes = input.readBytesLimited(RendererBundle.MAX_FILE_BYTES)
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        rendererDir.mkdirs()
        val temp = File(rendererDir, "active.renderer.tmp")
        FileOutputStream(temp).use { it.write(bytes) }
        check(temp.renameTo(activeFile) || temp.copyTo(activeFile, overwrite = true).exists()) {
            "Could not activate renderer"
        }
        temp.delete()
        return spec
    }

    fun reset(): RendererSpec {
        activeFile.delete()
        return RendererSpec.builtIn()
    }

    fun writeActive(output: OutputStream) {
        RendererBundle.write(active(), output)
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Renderer file is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

object RendererBundle {
    private const val MAGIC = "CCRNDR01"
    private const val VERSION = 1
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024 * 1024

    fun write(spec: RendererSpec, output: OutputStream) {
        val manifest = toJson(spec).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= MAX_MANIFEST_BYTES) { "Renderer manifest is too large" }

        val compressed = ByteArrayOutputStream().use { target ->
            GZIPOutputStream(target).use { it.write(manifest) }
            target.toByteArray()
        }
        require(compressed.size <= MAX_FILE_BYTES - 20) { "Renderer bundle is too large" }

        val crc = CRC32().apply { update(compressed) }.value
        DataOutputStream(output).use { data ->
            data.write(MAGIC.toByteArray(Charsets.US_ASCII))
            data.writeInt(VERSION)
            data.writeInt(compressed.size)
            data.writeInt(crc.toInt())
            data.write(compressed)
            data.flush()
        }
    }

    fun read(input: InputStream): RendererSpec {
        val data = DataInputStream(input)
        val magicBytes = ByteArray(8)
        data.readFully(magicBytes)
        require(String(magicBytes, Charsets.US_ASCII) == MAGIC) { "Not a Cubical Compare renderer" }

        val containerVersion = data.readInt()
        require(containerVersion == VERSION) { "Unsupported renderer container version $containerVersion" }

        val length = data.readInt()
        require(length in 1..(MAX_FILE_BYTES - 20)) { "Invalid renderer payload size" }
        val expectedCrc = data.readInt().toLong() and 0xFFFFFFFFL
        val payload = ByteArray(length)
        data.readFully(payload)
        require(data.read() == -1) { "Unexpected trailing data in renderer file" }

        val actualCrc = CRC32().apply { update(payload) }.value
        require(actualCrc == expectedCrc) { "Renderer file checksum failed" }

        val manifest = GZIPInputStream(ByteArrayInputStream(payload)).use { zipped ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = zipped.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_MANIFEST_BYTES) { "Renderer manifest expands beyond the limit" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

        val spec = fromJson(JSONObject(String(manifest, Charsets.UTF_8)))
        validate(spec)
        return spec
    }

    private fun validate(spec: RendererSpec) {
        require(spec.id.matches(Regex("[A-Za-z0-9._-]{1,96}"))) { "Invalid renderer id" }
        require(spec.name.isNotBlank() && spec.name.length <= 120) { "Invalid renderer name" }
        require(spec.author.length <= 120) { "Invalid renderer author" }
        require(spec.formatVersion == 1) { "Unsupported renderer schema ${spec.formatVersion}" }
        require(spec.cardHeight in 80f..1200f) { "Invalid card height" }
        require(spec.cardSpacing in 80f..1600f) { "Invalid card spacing" }
        require(spec.cardSideMargin in 0f..500f) { "Invalid card margin" }
        require(spec.badgeRadius in 10f..400f) { "Invalid badge radius" }
        require(spec.scrollStartMs >= 0 && spec.scrollEndPaddingMs >= 0) { "Invalid scroll timing" }
        require(spec.openingDurationMs in 0..120_000) { "Invalid opening timing" }
        require(spec.tracks.size <= 256) { "Too many animation tracks" }
        spec.tracks.forEach { track ->
            require(track.target.matches(Regex("[A-Za-z0-9.*_-]{1,96}"))) { "Invalid track target" }
            require(track.keyframes.size in 1..4096) { "Invalid keyframe count" }
            var previous = -1
            track.keyframes.forEach { frame ->
                require(frame.timeMs >= 0 && frame.timeMs >= previous) { "Keyframes must be sorted" }
                require(frame.value.isFinite()) { "Invalid keyframe value" }
                previous = frame.timeMs
            }
        }
    }

    private fun toJson(spec: RendererSpec): JSONObject = JSONObject().apply {
        put("id", spec.id)
        put("name", spec.name)
        put("author", spec.author)
        put("formatVersion", spec.formatVersion)
        put("backgroundColor", spec.backgroundColor)
        put("headerColor", spec.headerColor)
        put("subtitleColor", spec.subtitleColor)
        put("cardColor", spec.cardColor)
        put("shadowColor", spec.shadowColor)
        put("badgeColor", spec.badgeColor)
        put("badgeStrokeColor", spec.badgeStrokeColor)
        put("badgeTextColor", spec.badgeTextColor)
        put("shineColor", spec.shineColor)
        put("headerTitleSize", spec.headerTitleSize.toDouble())
        put("headerSubtitleSize", spec.headerSubtitleSize.toDouble())
        put("headerTitleY", spec.headerTitleY.toDouble())
        put("headerSubtitleY", spec.headerSubtitleY.toDouble())
        put("cardTop", spec.cardTop.toDouble())
        put("cardSideMargin", spec.cardSideMargin.toDouble())
        put("cardSpacing", spec.cardSpacing.toDouble())
        put("cardHeight", spec.cardHeight.toDouble())
        put("cardCornerRadius", spec.cardCornerRadius.toDouble())
        put("cardShadowX", spec.cardShadowX.toDouble())
        put("cardShadowY", spec.cardShadowY.toDouble())
        put("cardTitleSize", spec.cardTitleSize.toDouble())
        put("cardSubtitleSize", spec.cardSubtitleSize.toDouble())
        put("cardTitleX", spec.cardTitleX.toDouble())
        put("cardTitleY", spec.cardTitleY.toDouble())
        put("cardSubtitleY", spec.cardSubtitleY.toDouble())
        put("badgeRightInset", spec.badgeRightInset.toDouble())
        put("badgeTopInset", spec.badgeTopInset.toDouble())
        put("badgeRadius", spec.badgeRadius.toDouble())
        put("badgeStrokeWidth", spec.badgeStrokeWidth.toDouble())
        put("badgeTextSize", spec.badgeTextSize.toDouble())
        put("scrollStartMs", spec.scrollStartMs)
        put("scrollEndPaddingMs", spec.scrollEndPaddingMs)
        put("openingDurationMs", spec.openingDurationMs)
        put("openingYOffset", spec.openingYOffset.toDouble())
        put("specialEntryCard", spec.specialEntryCard)
        put("specialEntryXOffset", spec.specialEntryXOffset.toDouble())
        put("shineEnabled", spec.shineEnabled)
        put("shineStartMs", spec.shineStartMs)
        put("shineDurationMs", spec.shineDurationMs)
        put("shineWidth", spec.shineWidth.toDouble())
        put("tracks", JSONArray().apply {
            spec.tracks.forEach { track ->
                put(JSONObject().apply {
                    put("target", track.target)
                    put("keyframes", JSONArray().apply {
                        track.keyframes.forEach { frame ->
                            put(JSONObject().apply {
                                put("timeMs", frame.timeMs)
                                put("value", frame.value.toDouble())
                                put("easing", frame.easing)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun fromJson(json: JSONObject): RendererSpec {
        val defaults = RendererSpec.builtIn()
        fun f(key: String, fallback: Float): Float = json.optDouble(key, fallback.toDouble()).toFloat()
        fun i(key: String, fallback: Int): Int = json.optInt(key, fallback)
        fun c(key: String, fallback: Int): Int = if (json.has(key)) json.optLong(key, fallback.toLong()).toInt() else fallback

        val tracks = mutableListOf<RendererTrack>()
        val trackArray = json.optJSONArray("tracks") ?: JSONArray()
        for (index in 0 until trackArray.length()) {
            val trackJson = trackArray.getJSONObject(index)
            val frames = mutableListOf<RendererKeyframe>()
            val frameArray = trackJson.getJSONArray("keyframes")
            for (frameIndex in 0 until frameArray.length()) {
                val frameJson = frameArray.getJSONObject(frameIndex)
                frames += RendererKeyframe(
                    timeMs = frameJson.getInt("timeMs"),
                    value = frameJson.getDouble("value").toFloat(),
                    easing = frameJson.optString("easing", "linear")
                )
            }
            tracks += RendererTrack(trackJson.getString("target"), frames)
        }

        return RendererSpec(
            id = json.optString("id", defaults.id),
            name = json.optString("name", defaults.name),
            author = json.optString("author", defaults.author),
            formatVersion = i("formatVersion", defaults.formatVersion),
            backgroundColor = c("backgroundColor", defaults.backgroundColor),
            headerColor = c("headerColor", defaults.headerColor),
            subtitleColor = c("subtitleColor", defaults.subtitleColor),
            cardColor = c("cardColor", defaults.cardColor),
            shadowColor = c("shadowColor", defaults.shadowColor),
            badgeColor = c("badgeColor", defaults.badgeColor),
            badgeStrokeColor = c("badgeStrokeColor", defaults.badgeStrokeColor),
            badgeTextColor = c("badgeTextColor", defaults.badgeTextColor),
            shineColor = c("shineColor", defaults.shineColor),
            headerTitleSize = f("headerTitleSize", defaults.headerTitleSize),
            headerSubtitleSize = f("headerSubtitleSize", defaults.headerSubtitleSize),
            headerTitleY = f("headerTitleY", defaults.headerTitleY),
            headerSubtitleY = f("headerSubtitleY", defaults.headerSubtitleY),
            cardTop = f("cardTop", defaults.cardTop),
            cardSideMargin = f("cardSideMargin", defaults.cardSideMargin),
            cardSpacing = f("cardSpacing", defaults.cardSpacing),
            cardHeight = f("cardHeight", defaults.cardHeight),
            cardCornerRadius = f("cardCornerRadius", defaults.cardCornerRadius),
            cardShadowX = f("cardShadowX", defaults.cardShadowX),
            cardShadowY = f("cardShadowY", defaults.cardShadowY),
            cardTitleSize = f("cardTitleSize", defaults.cardTitleSize),
            cardSubtitleSize = f("cardSubtitleSize", defaults.cardSubtitleSize),
            cardTitleX = f("cardTitleX", defaults.cardTitleX),
            cardTitleY = f("cardTitleY", defaults.cardTitleY),
            cardSubtitleY = f("cardSubtitleY", defaults.cardSubtitleY),
            badgeRightInset = f("badgeRightInset", defaults.badgeRightInset),
            badgeTopInset = f("badgeTopInset", defaults.badgeTopInset),
            badgeRadius = f("badgeRadius", defaults.badgeRadius),
            badgeStrokeWidth = f("badgeStrokeWidth", defaults.badgeStrokeWidth),
            badgeTextSize = f("badgeTextSize", defaults.badgeTextSize),
            scrollStartMs = i("scrollStartMs", defaults.scrollStartMs),
            scrollEndPaddingMs = i("scrollEndPaddingMs", defaults.scrollEndPaddingMs),
            openingDurationMs = i("openingDurationMs", defaults.openingDurationMs),
            openingYOffset = f("openingYOffset", defaults.openingYOffset),
            specialEntryCard = i("specialEntryCard", defaults.specialEntryCard),
            specialEntryXOffset = f("specialEntryXOffset", defaults.specialEntryXOffset),
            shineEnabled = json.optBoolean("shineEnabled", defaults.shineEnabled),
            shineStartMs = i("shineStartMs", defaults.shineStartMs),
            shineDurationMs = i("shineDurationMs", defaults.shineDurationMs),
            shineWidth = f("shineWidth", defaults.shineWidth),
            tracks = tracks
        )
    }
}
