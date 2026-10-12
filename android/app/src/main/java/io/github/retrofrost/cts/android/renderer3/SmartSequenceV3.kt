package io.github.retrofrost.cts.android.renderer3

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** Renderer3 bootanimation sequence, preserving the authored per-frame text geometry. */
internal class SmartSequenceV3 private constructor(
    val width: Int,
    val height: Int,
    val fps: Int,
    val fields: List<JSONObject>,
    val artwork: JSONObject?,
    private val parts: List<Part>,
    private val overlay: List<String>,
    private val assets: (String) -> ByteArray?,
) {
    internal data class Frame(val asset: String, val template: Int)
    private data class Part(val frames: List<String>, val count: Int, val pause: Int, val offset: Int)

    fun select(frame: Int, settledHold: Boolean = true): Frame? {
        if (frame < 0) return null
        var remaining = frame
        var last: Frame? = null
        for (part in parts) {
            val length = part.frames.size + part.pause
            if (length == 0) continue
            if (part.count == 0 || remaining < length.toLong() * part.count) {
                val index = (remaining % length).coerceAtMost(part.frames.lastIndex)
                return Frame(part.frames[index], part.offset + index)
            }
            remaining -= (length.toLong() * part.count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            last = Frame(part.frames.last(), part.offset + part.frames.lastIndex)
        }
        return if (settledHold) last else null
    }
    fun finalFrame(): Frame? = parts.lastOrNull()?.let { Frame(it.frames.last(), it.offset + it.frames.lastIndex) }
    fun frameAsset(path: String): ByteArray? = assets(path)
    fun overlayAsset(templateFrame: Int): String? = overlay.getOrNull(templateFrame)

    companion object {
        private fun validPath(s: String) = RendererV3Container.safeName(s)
        fun fromSidecars(bundle: RendererV3Package, root: String): SmartSequenceV3 {
            val folder = validPath(root).trimEnd('/')
            val desc = bundle.asset("$folder/desc.txt")?.toString(Charsets.UTF_8)
                ?: error("Renderer3 sequence $folder is missing desc.txt")
            val smart = bundle.asset("$folder/smart.json")?.let { JSONObject(it.toString(Charsets.UTF_8)) }
                ?: error("Renderer3 sequence $folder is missing smart.json")
            return parse(desc, smart, { path -> bundle.asset("$folder/$path") },
                bundle.assetPaths().filter { it.startsWith("$folder/", true) }
                    .map { it.substring(folder.length + 1) })
        }
        fun fromNestedZip(bytes: ByteArray): SmartSequenceV3 {
            require(bytes.size <= 64 * 1024 * 1024) { "SmartBadge ZIP is too large" }
            val assets = LinkedHashMap<String, ByteArray>()
            var expanded = 0L
            var count = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
                while (true) {
                    val entry = stream.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = validPath(entry.name)
                    require(++count <= 65536) { "Too many SmartBadge animation frames" }
                    require(assets.keys.none { it.equals(name, true) }) { "Duplicate SmartBadge frame path" }
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var member = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        member += n
                        expanded += n
                        require(member <= 32L * 1024 * 1024 && expanded <= 64L * 1024 * 1024) {
                            "SmartBadge ZIP expanded beyond memory safety limit"
                        }
                        out.write(buffer, 0, n)
                    }
                    assets[name] = out.toByteArray()
                }
            }
            val desc = assets["desc.txt"]?.toString(Charsets.UTF_8)
                ?: error("SmartBadge ZIP is missing desc.txt")
            val smart = assets["smart.json"]?.let { JSONObject(it.toString(Charsets.UTF_8)) }
                ?: error("SmartBadge ZIP is missing smart.json")
            return parse(desc, smart, { key -> assets[key] }, assets.keys.toList())
        }
        private fun parse(
            desc: String, smart: JSONObject, getter: (String) -> ByteArray?,
            paths: List<String>,
        ): SmartSequenceV3 {
            require(smart.optString("marker") == "jsparse" &&
                smart.optBoolean("transparentBackground", true) &&
                smart.optBoolean("emptyText", true)) { "Invalid SmartBadge jsparse manifest" }
            val lines = desc.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            val header = lines.firstOrNull()?.split(Regex("\\s+")) ?: emptyList()
            require(header.size >= 3) { "Invalid animation desc.txt header" }
            val w = header[0].toIntOrNull() ?: 0
            val h = header[1].toIntOrNull() ?: 0
            val fps = header[2].toIntOrNull() ?: 0
            require(w in 1..8192 && h in 1..8192 && fps in 1..240) {
                "Invalid animation desc.txt dimensions or FPS"
            }
            var offset = 0
            val parts = lines.drop(1).map { line ->
                val t = line.split(Regex("\\s+"))
                require(t.size >= 4 && t[0] in listOf("p", "c")) { "Unsupported animation part: $line" }
                val count = t[1].toIntOrNull() ?: -1
                val pause = t[2].toIntOrNull() ?: -1
                require(count in 0..10000 && pause in 0..10000) { "Bad animation playback count" }
                val folder = validPath(t[3]).trimEnd('/')
                val frames = paths.filter {
                    it.startsWith("$folder/", true) &&
                        it.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) in
                        setOf("webp", "png", "jpg", "jpeg")
                }.sortedWith(compareBy<String> { it.substringAfterLast('/').substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE }
                    .thenBy { it })
                require(frames.isNotEmpty()) { "Animation folder $folder contains no frames" }
                val part = Part(frames, count, pause, offset)
                offset += frames.size
                part
            }
            require(parts.isNotEmpty() && offset <= 65536) { "Invalid animation parts" }
            val overlayFolder = smart.optString("overlayFolder")
            val overlay = if (overlayFolder.isBlank()) emptyList() else {
                val folder = validPath(overlayFolder)
                paths.filter { it.startsWith("$folder/", true) &&
                    it.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("png","webp","jpg","jpeg")
                }.sortedWith(compareBy<String> { it.substringAfterLast('/').substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
            }
            if (overlayFolder.isNotBlank()) require(overlay.size == offset) {
                "SmartBadge overlay and base frame counts differ"
            }
            val fieldsJson = smart.optJSONArray("fields") ?: error("SmartBadge has no live text fields")
            val fields = (0 until fieldsJson.length()).mapNotNull { fieldsJson.optJSONObject(it) }
            require(fields.isNotEmpty() && fields.all { it.optString("token") == "jsparse" }) {
                "SmartBadge fields must be live jsparse fields"
            }
            return SmartSequenceV3(w,h,fps,fields,smart.optJSONObject("artwork"),parts,overlay,getter)
        }
    }
}
