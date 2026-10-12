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

    fun decode(input: ByteArray, expectedMagic: String = HEADER): String {
        require(input.size >= 20 && input.size.toLong() <= MAX_CONTAINER) { "Renderer3 exceeds its size limit" }
        require(String(input, 0, 8, StandardCharsets.US_ASCII) == expectedMagic) {
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

    fun open(file: File, validateAnimations: Boolean = true): RendererV3Package {
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

            val wrappedPath = json.optString("wrappedRenderer")
            val wrapped = if (wrappedPath.isNotBlank()) {
                val name = safeName(wrappedPath)
                val entry = entries.entries.firstOrNull { it.key.equals(name, true) }
                    ?: error("Renderer3 package is missing its wrapped legacy engine: " + name)
                val data = boundedRead(archive!!.getInputStream(entry.value), MAX_CONTAINER)
                val legacy = JSONObject(decode(data, "CCRNDR01"))
                require(legacy.optInt("rendererApi", -1) == 2 &&
                    legacy.optString("engine") == "ribbon-exact") {
                    "Wrapped renderer uses an unsupported legacy engine (only ribbon-exact is portable)"
                }
                legacy
            } else null
            val badgeConfig = if (wrapped != null) {
                entries.entries.firstOrNull { it.key.equals("smartbadge-v2.json", true) }?.let {
                    JSONObject(boundedRead(archive!!.getInputStream(it.value), MAX_ENTRY)
                        .toString(StandardCharsets.UTF_8))
                }
            } else null
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

            val supported = if (wrapped != null) {
                setOf("smart-card-animation", "smart-badge-animation",
                    "rect", "ellipse", "polygon", "text", "image", "audio")
            } else setOf("rect", "ellipse", "polygon", "text", "image",
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
                    if (wrapped == null && feature in setOf("project-card-data", "project-badge-text",
                            "smart-badge-animation", "smart-card-animation", "smartbadge-v2"))
                        unsupported += feature
                }
            }
            for (i in 0 until objects.length()) {
                val obj = objects.optJSONObject(i) ?: error("Malformed Renderer3 scene object")
                if (wrapped != null && obj.optString("kind") !in setOf("smartCard", "ribbonOverlay")) {
                    unsupported += "unsupported ribbon layer " + obj.optString("kind")
                }
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
            if (wrapped != null) {
                val legacyFeatures = wrapped.optJSONArray("requiredFeatures")
                val allowedFeatures = setOf("frame-exact", "exact-scroll-track", "affine-badge-transform",
                    "per-frame-keyframes", "frame-addressed-shine", "custom-outro", "preview-frames",
                    "ribbon-artwork-region-v1", "ribbon-smart-card-v1", "ribbon-smart-badge-v2",
                    "ribbon-badge-text-v1", "ribbon-background-track-v1",
                    "ribbon-card-motion-v1", "ribbon-scroll-track-v1",
                    "ribbon-scene-overlays-v1")
                // Unknown required features must not disappear silently in an Android export.
                // The explicitly ported renderer covers the ribbon/SmartBadge tracks.
                require(badgeConfig == null || badgeConfig.optInt("version") == 2) {
                    "Unsupported SmartBadge manifest version"
                }
                for (i in 0 until (legacyFeatures?.length() ?: 0)) {
                    val feature = legacyFeatures!!.optString(i)
                    if (feature.startsWith("ribbon-") && feature !in allowedFeatures) {
                        // A known ribbon function may be implemented through authored tracks;
                        // unrecognised renderer functions need explicit support.
                        if (feature.contains("overlay") || feature.contains("group"))
                            unsupported += feature
                    }
                }
                if (badgeConfig != null) {
                    val packs = badgeConfig.optJSONObject("packs")
                        ?: error("SmartBadge v2 manifest has no packs")
                    for (key in packs.keys()) {
                        val pack = packs.opt(key)
                        val asset = if (pack is JSONObject) pack.optString("asset") else pack?.toString().orEmpty()
                        val safe = safeName(asset)
                        if (entries.keys.none { it.equals(safe, true) })
                            unsupported += "missing SmartBadge ZIP " + safe
                    }
                }
            }
            require(unsupported.isEmpty()) {
                "Renderer3 uses features not yet supported on Android: " + unsupported.take(6).joinToString(", ")
            }
            val result = RendererV3Package(json, archive, entries, RendererV3Selection(
                filePath = file.absolutePath, id = id,
                name = json.optString("name", id).take(160),
                width = width, height = height, fps = fps, frames = frames,
                wrappedEngine = wrapped?.optString("engine"),
                canonicalCards = wrapped?.optInt("canonicalCardCount", 0) ?: 0,
                ribbonStart = wrapped?.optInt("continuousStartFrame", 0) ?: 0,
                ribbonStep = wrapped?.optInt("continuousStepFrames", 0) ?: 0,
                outroFrames = wrapped?.let {
                    it.optInt("endWipeFrames") + it.optInt("endRiseFrames") +
                    it.optInt("endHoldFrames") + it.optInt("fadeFrames") +
                    it.optInt("blackTailFrames")
                } ?: 0,
                openingEnds = wrapped?.optJSONArray("openingEnds")?.let { array ->
                    (0 until array.length()).map { array.optInt(it) }
                } ?: emptyList(),
            ), wrapped, badgeConfig)
            if (wrapped != null && validateAnimations) {
                val sequenceRoots = LinkedHashSet<String>()
                for (key in resources.keys()) {
                    val resource = resources.optJSONObject(key) ?: continue
                    if (resource.optString("type") in setOf("smart-card-animation", "smart-badge-animation")) {
                        val folder = safeName(resource.optString("sequenceRoot"))
                        sequenceRoots += folder
                    }
                }
                require(sequenceRoots.isNotEmpty()) { "Ribbon Renderer3 has no SmartCard animation" }
                for (folder in sequenceRoots) SmartSequenceV3.fromSidecars(result, folder)
                val packs = badgeConfig?.optJSONObject("packs")
                if (packs != null) {
                    for (key in packs.keys()) {
                        val pack = packs.opt(key)
                        val path = if (pack is JSONObject) pack.optString("asset") else pack?.toString().orEmpty()
                        SmartSequenceV3.fromNestedZip(result.asset(path)
                            ?: error("Missing SmartBadge v2 pack " + path))
                    }
                }
            }
            return result
        } catch (error: Exception) {
            archive?.close()
            throw error
        }
    }

    /**
     * Extract renderer-authored audio into private app storage for the existing
     * MediaExtractor/AAC soundtrack path. This does not change the user's selection
     * when a soundtrack is already present and never trusts arbitrary asset paths.
     */
    fun embeddedAudio(context: Context, selection: RendererV3Selection): String? {
        open(checkedProjectFile(context, selection), validateAnimations = false).use { renderer ->
            val audio = renderer.root.optJSONObject("audio") ?: return null
            val resourceId = audio.optString("source")
            val source = audio.optString("asset").ifBlank {
                renderer.root.optJSONObject("resources")
                    ?.optJSONObject(resourceId)?.optString("path").orEmpty()
            }
            if (source.isBlank()) return null
            val asset = renderer.asset(safeName(source)) ?: return null
            require(asset.size in 1..(32 * 1024 * 1024)) {
                "Renderer3 embedded audio exceeds 32 MiB safety limit"
            }
            val destination = File(File(context.filesDir, "renderer3"),
                File(selection.filePath).nameWithoutExtension + "-soundtrack.m4a")
            destination.outputStream().use { it.write(asset) }
            return Uri.fromFile(destination).toString()
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
    val wrappedEngine: String? = null,
    val canonicalCards: Int = 0,
    val ribbonStart: Int = 0,
    val ribbonStep: Int = 0,
    val outroFrames: Int = 0,
    val openingEnds: List<Int> = emptyList(),
) {
    val durationSeconds: Float get() = frames.toFloat() / fps.coerceAtLeast(1)
    fun frameCountForCards(cardCount: Int): Int {
        if (wrappedEngine != "ribbon-exact" || canonicalCards < 4 || ribbonStep <= 0)
            return frames
        if (cardCount <= 0) return 1
        val padding = (frames.toLong() - outroFrames - ribbonStart -
            (canonicalCards - 4).toLong() * ribbonStep).coerceAtLeast(0)
        val opening = if (cardCount < 4) openingEnds.getOrNull(cardCount - 1) ?: ribbonStart
            else ribbonStart
        return (opening + maxOf(0, cardCount-4).toLong() * ribbonStep + padding + outroFrames)
            .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    fun durationForCards(cardCount: Int): Float =
        frameCountForCards(cardCount).toFloat() / fps.coerceAtLeast(1)
}

internal class RendererV3Package(
    val root: JSONObject,
    private val zip: ZipFile?,
    private val entries: Map<String, ZipEntry>,
    val selection: RendererV3Selection,
    val wrappedRibbon: JSONObject? = null,
    val smartBadge: JSONObject? = null,
) : Closeable {
    fun assetPaths(): List<String> = entries.keys.toList()
    fun asset(path: String): ByteArray? {
        val safe = RendererV3Container.safeName(path)
        val match = entries.entries.firstOrNull { (name, _) ->
            name.equals(safe, true) || name.endsWith("/" + safe, true)
        } ?: return null
        return zip?.getInputStream(match.value)?.let { RendererV3Container.boundedRead(it, 64L * 1024 * 1024) }
    }

    override fun close() { zip?.close() }
}
