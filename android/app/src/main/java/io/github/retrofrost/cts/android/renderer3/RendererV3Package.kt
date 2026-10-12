package io.github.retrofrost.cts.android.renderer3

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Native Cubical Compare Renderer3 format: CCRNDR03 + version, length, CRC32 and gzipped JSON. */
internal object RendererV3Container {
    private const val HEADER = "CCRNDR03"
    private const val MAX_CONTAINER = 128L * 1024 * 1024
    private const val MAX_JSON = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 65536
    private const val MAX_UNPACKED = 512L * 1024 * 1024
    private const val MAX_ENTRY = 64L * 1024 * 1024

    fun safeName(raw: String): String {
        val name = raw.replace('\\', '/')
        require(name.isNotBlank() && !name.startsWith("/") && !name.contains('\u0000')) {
            "Invalid Renderer3 asset path"
        }
        require(name.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            !Regex("^[A-Za-z]:").containsMatchIn(name)) { "Unsafe Renderer3 asset path" }
        return name
    }

    fun decode(input: ByteArray): String {
        require(input.size >= 20 && input.size.toLong() <= MAX_CONTAINER) { "Renderer3 exceeds its size limit" }
        require(String(input, 0, 8, StandardCharsets.US_ASCII) == HEADER) {
            "Not a Renderer3 container (CCRNDR03 required)"
        }
        val bytes = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
        require(bytes.getInt(8) == 1) { "Unsupported Renderer3 container version" }
        val length = bytes.getInt(12)
        require(length > 0 && length == input.size - 20) { "Invalid Renderer3 payload length" }
        val checksum = bytes.getInt(16).toLong() and 0xffffffffL
        val payload = input.copyOfRange(20, input.size)
        val crc = CRC32().apply { update(payload) }.value
        require(crc == checksum) { "Renderer3 checksum mismatch" }
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(payload)).use { stream ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_JSON) { "Renderer3 scene JSON is too large" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    fun boundedRead(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        input.use { stream ->
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "Renderer3 entry exceeds its safety limit" }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    fun open(file: File): RendererV3Package {
        require(file.exists() && file.length() in 1..MAX_CONTAINER) { "Invalid or oversized Renderer3 file" }
        val signature = file.inputStream().use { it.read() to it.read() }
        var archive: ZipFile? = null
        try {
            var entries: Map<String, ZipEntry> = emptyMap()
            val bytes: ByteArray
            if (signature.first == 'P'.code && signature.second == 'K'.code) {
                archive = ZipFile(file)
                val members = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
                require(members.isNotEmpty() && members.size <= MAX_ENTRIES) { "Renderer3 ZIP has too many entries" }
                val paths = LinkedHashMap<String, ZipEntry>()
                var expanded = 0L
                for (entry in members) {
                    val name = safeName(entry.name)
                    require(entry.size in 0..MAX_ENTRY) { "Oversized Renderer3 asset: " + name }
                    expanded += entry.size
                    require(expanded <= MAX_UNPACKED) { "Renderer3 archive has too much expanded data" }
                    require(paths.keys.none { it.equals(name, ignoreCase = true) }) { "Duplicate Renderer3 asset: " + name }
                    paths[name] = entry
                }
                val scene = paths.entries.firstOrNull { it.key.equals("renderer.renderer3", true) }
                    ?: paths.entries.firstOrNull { it.key.equals("manifest.renderer3", true) }
                    ?: paths.entries.firstOrNull { it.key.endsWith(".renderer3", true) }
                    ?: error("Renderer3 ZIP does not contain a .renderer3 scene")
                bytes = boundedRead(archive.getInputStream(scene.value), MAX_CONTAINER)
                entries = paths.filterKeys { it != scene.key }
            } else {
                bytes = boundedRead(file.inputStream(), MAX_CONTAINER)
            }
            val json = JSONObject(decode(bytes))
            val api = json.optInt("api", json.optInt("rendererApi", 3))
            require(api == 3) { "This file uses renderer API " + api + ", not Renderer3" }
            val id = json.optString("id")
            require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Renderer3 has an invalid ID" }
            val engine = json.optString("engine", "scene-v3")
            require(engine == "scene-v3") { "Renderer3 engine " + engine + " is not supported on Android" }
            require(json.optString("wrappedRenderer").isBlank()) {
                "This Renderer3 wraps a legacy Windows renderer and needs its desktop engine"
            }
            val clock = json.optJSONObject("timeline") ?: error("Renderer3 has no timeline")
            require(clock.optString("clock", "absolute") == "absolute" &&
                !clock.optBoolean("implicitAnimation", false)) { "Renderer3 requires an absolute explicit frame clock" }
            val reference = json.optJSONObject("reference")
            val canvas = json.optJSONObject("canvas")
            val fps = canvas?.optInt("fps", reference?.optInt("fps", 60) ?: 60) ?: (reference?.optInt("fps", 60) ?: 60)
            val width = canvas?.optInt("width", reference?.optInt("width", 1920) ?: 1920) ?: (reference?.optInt("width", 1920) ?: 1920)
            val height = canvas?.optInt("height", reference?.optInt("height", 1080) ?: 1080) ?: (reference?.optInt("height", 1080) ?: 1080)
            val frames = clock.optInt("frames", reference?.optInt("frameCount", 0) ?: 0)
            require(fps in 1..240 && width in 1..8192 && height in 1..8192 &&
                frames in 1..2_000_000) { "Invalid Renderer3 frame clock or canvas" }

            val supported = setOf("rect", "ellipse", "polygon", "text", "image",
                "text-raster", "source-text-raster", "source-card-raster",
                "source-component-raster", "component-raster")
            val objects = json.optJSONArray("objects") ?: error("Renderer3 has no scene objects")
            require(objects.length() in 1..65536) { "Renderer3 has no scene objects or too many objects" }
            val resources = json.optJSONObject("resources") ?: JSONObject()
            val unsupported = LinkedHashSet<String>()
            for (featureList in listOf("features", "requiredFeatures")) {
                val features = json.optJSONArray(featureList) ?: continue
                for (i in 0 until features.length()) {
                    val feature = features.optString(i)
                    if (feature in setOf("project-card-data", "project-badge-text",
                            "smart-badge-animation", "smart-card-animation", "smartbadge-v2"))
                        unsupported += feature
                }
            }
            for (i in 0 until objects.length()) {
                val obj = objects.optJSONObject(i) ?: error("Malformed Renderer3 scene object")
                val resourceId = obj.optString("resource")
                val resource = resources.optJSONObject(resourceId)
                val type = (resource?.optString("type") ?: obj.optString("kind")).lowercase()
                if (type !in supported) {
                    unsupported += type.ifBlank { "(unknown resource)" }
                    continue
                }
                if (type.contains("raster") || type == "image") {
                    val properties = obj.optJSONObject("properties")
                    val source = properties?.optString("source")?.takeIf { it.isNotBlank() }
                        ?: resource?.optString("source")?.takeIf { it.isNotBlank() }
                        ?: resource?.optString("asset")?.takeIf { it.isNotBlank() }
                        ?: resource?.optString("relativeAsset").orEmpty()
                    if (source.isNotBlank()) {
                        val safe = safeName(source)
                        if (entries.keys.none { it.equals(safe, true) || it.endsWith("/" + safe, true) }) {
                            unsupported += "missing artwork asset " + safe
                        }
                    }
                }
            }
            require(unsupported.isEmpty()) {
                "Renderer3 uses features not yet supported on Android: " + unsupported.take(6).joinToString(", ")
            }
            return RendererV3Package(json, archive, entries, RendererV3Selection(
                filePath = file.absolutePath, id = id,
                name = json.optString("name", id).take(160),
                width = width, height = height, fps = fps, frames = frames,
            ))
        } catch (error: Exception) {
            archive?.close()
            throw error
        }
    }

    fun install(context: Context, source: Uri): RendererV3Selection {
        val directory = File(context.filesDir, "renderer3").apply { mkdirs() }
        val staged = File(directory, UUID.randomUUID().toString() + ".renderer3")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: error("Cannot read selected Renderer3 file")
            input.use { stream ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_CONTAINER) { "Renderer3 file exceeds 128 MiB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val selection = open(staged).use { it.selection }
            return selection
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    fun checkedProjectFile(context: Context, selection: RendererV3Selection): File {
        val directory = File(context.filesDir, "renderer3").canonicalFile
        val file = File(selection.filePath).canonicalFile
        require(file.parentFile == directory && file.isFile) {
            "Renderer3 is missing from app storage; import the .renderer3 again"
        }
        return file
    }
}

data class RendererV3Selection(
    val filePath: String,
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frames: Int,
) {
    val durationSeconds: Float get() = frames.toFloat() / fps.coerceAtLeast(1)
}

internal class RendererV3Package(
    val root: JSONObject,
    private val zip: ZipFile?,
    private val entries: Map<String, ZipEntry>,
    val selection: RendererV3Selection,
) : Closeable {
    fun asset(path: String): ByteArray? {
        val safe = RendererV3Container.safeName(path)
        val match = entries.entries.firstOrNull { (name, _) ->
            name.equals(safe, true) || name.endsWith("/" + safe, true)
        } ?: return null
        return zip?.getInputStream(match.value)?.let { RendererV3Container.boundedRead(it, 64L * 1024 * 1024) }
    }

    override fun close() { zip?.close() }
}
