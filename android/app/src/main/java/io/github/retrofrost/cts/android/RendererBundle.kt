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
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.OutputStream
import java.security.MessageDigest
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
        val first = keyframes.first()
        val last = keyframes.last()
        if (timeMs <= first.timeMs) return first.value
        if (timeMs >= last.timeMs) return last.value

        // Frame-exact bundles commonly contain one keyframe for every frame. In that
        // hot path the timestamp offset is also the list index, so avoid searching.
        val denseIndex = timeMs - first.timeMs
        if (denseIndex in keyframes.indices) {
            val dense = keyframes[denseIndex]
            if (dense.timeMs == timeMs) return dense.value
        }

        // Sparse tracks use lower-bound binary search instead of scanning thousands
        // of earlier keyframes for every rendered frame.
        var low = 1
        var high = keyframes.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (keyframes[mid].timeMs < timeMs) low = mid + 1 else high = mid
        }
        val right = keyframes[low]
        val left = keyframes[low - 1]
        val span = (right.timeMs - left.timeMs).coerceAtLeast(1)
        val raw = ((timeMs - left.timeMs).toFloat() / span).coerceIn(0f, 1f)
        val p = easing(raw, right.easing)
        return left.value + (right.value - left.value) * p
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
    val rendererApi: Int = 1,
    val engine: String = "native-standard",
    val precisionMode: String = "interpolated",
    val timelineUnit: String = "frames",
    val minAppVersion: String = "2.0.7",
    val referenceWidth: Int = 1920,
    val referenceHeight: Int = 1080,
    val referenceFps: Int = 60,
    val canonicalCardCount: Int = 0,
    val canonicalFrameCount: Int = 0,
    val requiredFeatures: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val previewFrames: List<Int> = emptyList(),
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

data class RendererValidationReport(
    val errors: List<String>,
    val warnings: List<String>,
) {
    val compatible: Boolean get() = errors.isEmpty()
    fun summary(): String = buildString {
        append(if (compatible) "Fully compatible" else "Not compatible")
        if (warnings.isNotEmpty()) append(" • ${warnings.size} warning${if (warnings.size == 1) "" else "s"}")
    }
}

data class RendererCandidate(
    val bytes: ByteArray,
    val spec: RendererSpec,
    val sha256: String,
    val report: RendererValidationReport,
)

data class InstalledRenderer(
    val spec: RendererSpec,
    val file: File,
    val active: Boolean,
)

object RendererCapabilities {
    const val APP_VERSION = "2.0.7"
    const val RENDERER_API = 2

    val engines = setOf(
        "native-standard",
        "ribbon-exact",
        "relationships-exact",
    )

    val features = setOf(
        "exact-scroll-track",
        "frame-exact",
        "affine-badge-transform",
        "layered-artwork",
        "artwork-transform",
        "custom-outro",
        "custom-intro",
        "per-frame-keyframes",
        "per-badge-affine-transform",
        "frame-addressed-shine",
        "relationships-exact-v2",
        "relationships-footer-waveform",
        "relationships-rich-typography",
        "relationships-shadow-mask-v1",
        "relationships-shadow-outside-v2",
        "relationships-single-owner-pass-v1",
        "preview-frames",
    )

    fun report(spec: RendererSpec): RendererValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (spec.rendererApi > RENDERER_API) errors += "Requires renderer API ${spec.rendererApi}; this build supports API $RENDERER_API."
        if (spec.engine !in engines) errors += "Renderer engine '${spec.engine}' is not available in this build."
        val missing = spec.requiredFeatures.filterNot { it in features }
        if (missing.isNotEmpty()) errors += "Unsupported renderer features: ${missing.joinToString()}"
        if (compareVersions(APP_VERSION, spec.minAppVersion) < 0) errors += "Requires Cubical Compare ${spec.minAppVersion} or newer."
        if (spec.referenceWidth != 1920 || spec.referenceHeight != 1080) warnings += "Reference canvas is ${spec.referenceWidth}×${spec.referenceHeight}; exports may be scaled."
        if (spec.referenceFps != 60) warnings += "Reference frame rate is ${spec.referenceFps} fps."
        if (spec.precisionMode == "frame-exact" && spec.timelineUnit != "frames") errors += "Frame-exact renderers must use frame timeline units."
        if (spec.precisionMode == "frame-exact" && spec.referenceFps <= 0) errors += "Frame-exact renderer has no valid reference FPS."
        return RendererValidationReport(errors, warnings)
    }

    private fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val aa = parts(a)
        val bb = parts(b)
        for (index in 0 until maxOf(aa.size, bb.size)) {
            val av = aa.getOrElse(index) { 0 }
            val bv = bb.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}

class RendererStore(private val context: Context) {
    private val dir = File(context.filesDir, "renderers")
    private val libraryDir = File(dir, "library")
    private val activeFile = File(dir, "active.renderer")
    private val previousFile = File(dir, "previous.renderer")

    fun active(): RendererSpec = if (activeFile.isFile) {
        runCatching { activeFile.inputStream().use(RendererBundle::read) }
            .getOrElse { RendererSpec.builtIn() }
    } else {
        RendererSpec.builtIn()
    }

    fun inspect(input: InputStream): RendererCandidate {
        val bytes = readLimited(input, RendererBundle.MAX_FILE_BYTES)
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        val structural = RendererBundle.validateDetailed(spec)
        val compatibility = RendererCapabilities.report(spec)
        val report = RendererValidationReport(
            structural.errors + compatibility.errors,
            structural.warnings + compatibility.warnings,
        )
        return RendererCandidate(bytes, spec, sha256(bytes), report)
    }

    fun install(candidate: RendererCandidate): InstalledRenderer {
        require(candidate.report.compatible) { candidate.report.errors.joinToString("\n") }
        libraryDir.mkdirs()
        val destination = File(libraryDir, "${candidate.spec.id}.renderer")
        atomicWrite(destination, candidate.bytes)
        return InstalledRenderer(candidate.spec, destination, active().id == candidate.spec.id)
    }

    fun activate(id: String): RendererSpec {
        val file = File(libraryDir, "$id.renderer")
        require(file.isFile) { "Renderer '$id' is not installed." }
        val bytes = file.readBytes()
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\n") }
        // Renderer compatibility is a project-quality diagnostic, never an import
        // gate. A renderer is a reusable visual/timing profile and must be installable
        // on a new, empty or differently-sized project. Exact-v2 still forces its
        // reference output size/FPS in the render/export path.
        dir.mkdirs()
        if (activeFile.isFile) atomicWrite(previousFile, activeFile.readBytes())
        atomicWrite(activeFile, bytes)
        RendererBridge.setRuntimeActive(spec)
        return spec
    }

    fun rollback(): RendererSpec {
        require(previousFile.isFile) { "There is no previous renderer to restore." }
        val bytes = previousFile.readBytes()
        val spec = RendererBundle.read(ByteArrayInputStream(bytes))
        val current = if (activeFile.isFile) activeFile.readBytes() else null
        atomicWrite(activeFile, bytes)
        if (current != null) atomicWrite(previousFile, current) else previousFile.delete()
        RendererBridge.setRuntimeActive(spec)
        return spec
    }

    fun listInstalled(): List<InstalledRenderer> {
        val activeId = active().id
        if (!libraryDir.isDirectory) return emptyList()
        return libraryDir.listFiles { file -> file.isFile && file.extension.equals("renderer", true) }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val spec = file.inputStream().use(RendererBundle::read)
                    InstalledRenderer(spec, file, spec.id == activeId)
                }.getOrNull()
            }
            .sortedBy { it.spec.name.lowercase() }
    }

    fun uninstall(id: String) {
        require(id != active().id) { "Activate another renderer before deleting the active renderer." }
        File(libraryDir, "$id.renderer").delete()
    }

    /** Backwards-compatible one-call import used by legacy entry points. */
    fun import(input: InputStream): RendererSpec {
        val candidate = inspect(input)
        install(candidate)
        return activate(candidate.spec.id)
    }

    fun reset(): RendererSpec {
        if (activeFile.isFile) atomicWrite(previousFile, activeFile.readBytes())
        activeFile.delete()
        return RendererSpec.builtIn().also(RendererBridge::setRuntimeActive)
    }

    fun export(output: OutputStream) = RendererBundle.write(active(), output)

    private fun atomicWrite(destination: File, bytes: ByteArray) {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        FileOutputStream(tmp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

object RendererBundle {
    private const val MAGIC = "CCRNDR01"
    private const val CONTAINER_VERSION = 1
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

    fun write(spec: RendererSpec, output: OutputStream) {
        val report = validateDetailed(spec)
        require(report.compatible) { report.errors.joinToString("\n") }
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
        val spec = fromJson(JSONObject(String(manifest, Charsets.UTF_8)))
        val report = validateDetailed(spec)
        require(report.compatible) { report.errors.joinToString("\n") }
        return spec
    }

    fun validateDetailed(spec: RendererSpec): RendererValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        fun errorIf(condition: Boolean, message: String) { if (condition) errors += message }
        errorIf(!spec.id.matches(Regex("[A-Za-z0-9._-]{1,96}")), "Invalid renderer id.")
        errorIf(spec.name.isBlank() || spec.name.length > 120, "Invalid renderer name.")
        errorIf(spec.author.length > 120, "Invalid renderer author.")
        errorIf(spec.formatVersion !in 1..2, "Unsupported renderer schema ${spec.formatVersion}.")
        errorIf(spec.rendererApi !in 1..32, "Invalid renderer API.")
        errorIf(!spec.engine.matches(Regex("[A-Za-z0-9._-]{1,64}")), "Invalid renderer engine.")
        errorIf(spec.precisionMode !in setOf("interpolated", "frame-exact"), "Invalid precision mode.")
        errorIf(spec.timelineUnit !in setOf("frames", "milliseconds", "normalized"), "Invalid timeline unit.")
        errorIf(spec.referenceWidth !in 1..16384 || spec.referenceHeight !in 1..16384, "Invalid reference resolution.")
        errorIf(spec.referenceFps !in 1..240, "Invalid reference FPS.")
        errorIf(spec.canonicalCardCount !in 0..10000, "Invalid canonical card count.")
        errorIf(spec.canonicalFrameCount !in 0..10_000_000, "Invalid canonical frame count.")
        errorIf(spec.requiredFeatures.size > 128 || spec.tags.size > 128 || spec.previewFrames.size > 128, "Renderer metadata contains too many entries.")
        errorIf(spec.slotPitch !in 100f..2000f || spec.bodyWidth !in 100f..2000f, "Invalid card geometry.")
        errorIf(spec.imageHeight !in 0f..2000f || spec.descriptionTop !in 0f..2000f, "Invalid card layout.")
        errorIf(spec.badgeScale !in 0.1f..4f, "Invalid badge scale.")
        errorIf(spec.bodySlideFrames !in 1..7200, "Invalid body timing.")
        errorIf(spec.continuousStepFrames !in 1..7200, "Invalid conveyor timing.")
        errorIf(spec.tracks.size > 256, "Too many renderer tracks.")
        val seenTargets = mutableSetOf<String>()
        spec.tracks.forEach { track ->
            if (!seenTargets.add(track.target)) errors += "Duplicate renderer track '${track.target}'."
            errorIf(!track.target.matches(Regex("[A-Za-z0-9.*_-]{1,96}")), "Invalid renderer track target '${track.target}'.")
            errorIf(track.keyframes.size !in 1..4096, "Invalid keyframe count for '${track.target}'.")
            var previous = -1
            val seenTimes = mutableSetOf<Int>()
            track.keyframes.forEach { frame ->
                errorIf(frame.timeMs < previous || !frame.value.isFinite(), "Invalid keyframe in '${track.target}'.")
                if (!seenTimes.add(frame.timeMs)) warnings += "Track '${track.target}' contains duplicate time ${frame.timeMs}."
                previous = frame.timeMs
            }
        }
        if (spec.precisionMode == "frame-exact" && spec.canonicalFrameCount == 0) warnings += "Frame-exact renderer does not declare a canonical frame count."
        if (spec.precisionMode == "frame-exact" && spec.previewFrames.isEmpty()) warnings += "Frame-exact renderer has no preview checkpoints."
        return RendererValidationReport(errors.distinct(), warnings.distinct())
    }

    private fun toJson(s: RendererSpec) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("author", s.author); put("formatVersion", s.formatVersion)
        put("rendererApi", s.rendererApi); put("engine", s.engine); put("precisionMode", s.precisionMode); put("timelineUnit", s.timelineUnit)
        put("minAppVersion", s.minAppVersion); put("referenceWidth", s.referenceWidth); put("referenceHeight", s.referenceHeight); put("referenceFps", s.referenceFps)
        put("canonicalCardCount", s.canonicalCardCount); put("canonicalFrameCount", s.canonicalFrameCount)
        put("requiredFeatures", JSONArray(s.requiredFeatures)); put("tags", JSONArray(s.tags)); put("previewFrames", JSONArray(s.previewFrames))
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
        fun strings(key: String): List<String> {
            val array = j.optJSONArray(key) ?: return emptyList()
            return List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
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
        val id = j.optString("id", d.id)
        val legacyEngine = when {
            id.startsWith("ribbon.") -> "ribbon-exact"
            else -> d.engine
        }
        val legacyPrecision = if (id.startsWith("ribbon.")) "frame-exact" else d.precisionMode
        return RendererSpec(
            id = id, name = j.optString("name", d.name), author = j.optString("author", d.author),
            formatVersion = j.optInt("formatVersion", d.formatVersion), rendererApi = j.optInt("rendererApi", if (j.optInt("formatVersion", 1) >= 2) 2 else 1),
            engine = j.optString("engine", legacyEngine), precisionMode = j.optString("precisionMode", legacyPrecision), timelineUnit = j.optString("timelineUnit", "frames"),
            minAppVersion = j.optString("minAppVersion", d.minAppVersion), referenceWidth = j.optInt("referenceWidth", 1920), referenceHeight = j.optInt("referenceHeight", 1080), referenceFps = j.optInt("referenceFps", 60),
            canonicalCardCount = j.optInt("canonicalCardCount", 0), canonicalFrameCount = j.optInt("canonicalFrameCount", 0), requiredFeatures = strings("requiredFeatures"), tags = strings("tags"), previewFrames = ints("previewFrames", emptyList()),
            backgroundColor = c("backgroundColor", d.backgroundColor), titleBackgroundColor = c("titleBackgroundColor", d.titleBackgroundColor), descriptionBackgroundColor = c("descriptionBackgroundColor", d.descriptionBackgroundColor),
            titleTextColor = c("titleTextColor", d.titleTextColor), descriptionTextColor = c("descriptionTextColor", d.descriptionTextColor), badgeColor = c("badgeColor", d.badgeColor), badgeDarkColor = c("badgeDarkColor", d.badgeDarkColor), badgeTextColor = c("badgeTextColor", d.badgeTextColor), shineColor = c("shineColor", d.shineColor),
            slotPitch = f("slotPitch", d.slotPitch), bodyInset = f("bodyInset", d.bodyInset), bodyWidth = f("bodyWidth", d.bodyWidth), imageHeight = f("imageHeight", d.imageHeight), titleHeight = f("titleHeight", d.titleHeight), descriptionTop = f("descriptionTop", d.descriptionTop),
            titleTextSize = f("titleTextSize", d.titleTextSize), descriptionTextSize = f("descriptionTextSize", d.descriptionTextSize), badgeCenterX = f("badgeCenterX", d.badgeCenterX), badgeCenterY = f("badgeCenterY", d.badgeCenterY), badgeScale = f("badgeScale", d.badgeScale),
            badgeHeaderSize = f("badgeHeaderSize", d.badgeHeaderSize), badgeValueSize = f("badgeValueSize", d.badgeValueSize), badgeUnitSize = f("badgeUnitSize", d.badgeUnitSize), openingStarts = ints("openingStarts", d.openingStarts), openingEnds = ints("openingEnds", d.openingEnds),
            continuousStartFrame = j.optInt("continuousStartFrame", d.continuousStartFrame), continuousStepFrames = j.optInt("continuousStepFrames", d.continuousStepFrames), bodySlideFrames = j.optInt("bodySlideFrames", d.bodySlideFrames), laterBadgeFallStartFrame = j.optInt("laterBadgeFallStartFrame", d.laterBadgeFallStartFrame), laterBadgeFallEndFrame = j.optInt("laterBadgeFallEndFrame", d.laterBadgeFallEndFrame),
            shineStartFrame = j.optInt("shineStartFrame", d.shineStartFrame), shineFrames = j.optInt("shineFrames", d.shineFrames), endWipeFrames = j.optInt("endWipeFrames", d.endWipeFrames), endRiseFrames = j.optInt("endRiseFrames", d.endRiseFrames), endHoldFrames = j.optInt("endHoldFrames", d.endHoldFrames), fadeFrames = j.optInt("fadeFrames", d.fadeFrames), blackTailFrames = j.optInt("blackTailFrames", d.blackTailFrames), tracks = tracks,
        )
    }
}