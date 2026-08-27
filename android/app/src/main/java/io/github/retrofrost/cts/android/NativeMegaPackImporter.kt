package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max

/** Native, memory-bounded MegaPack importer replacing ccengine.megapack. */
object NativeMegaPackImporter {
    private const val MANIFEST = "megapack.json"
    private const val SUPPORTED_VERSION = 2
    private const val MAX_PACK_BYTES = 1_073_741_824L
    private const val MAX_EXTRACTED_BYTES = 536_870_912L
    private const val MAX_ENTRY_BYTES = 67_108_864L
    private const val MAX_MANIFEST_BYTES = 4_194_304L
    private const val MAX_ENTRIES = 1_000
    private const val MAX_CARDS = 500

    fun load(path: String, assets: File): StudioProject {
        val source = File(path).canonicalFile
        require(source.isFile) { "The selected MegaPack could not be opened." }
        require(source.length() <= MAX_PACK_BYTES) { "MegaPack is larger than the supported size limit." }
        require(!assets.exists() || assets.list().isNullOrEmpty()) { "MegaPack destination is not empty." }
        assets.mkdirs()

        try {
            ZipFile(source).use { zip ->
                val entries = zip.entries().toList()
                require(entries.size <= MAX_ENTRIES) { "This MegaPack contains too many files." }
                val indexed = linkedMapOf<String, ZipEntry>()
                var expanded = 0L
                entries.filterNot(ZipEntry::isDirectory).forEach { entry ->
                    val safe = safeEntry(entry.name)
                    require(safe !in indexed) { "MegaPack contains duplicate file '$safe'." }
                    require(entry.size < 0 || entry.size <= MAX_ENTRY_BYTES) { "MegaPack file '$safe' is too large." }
                    if (entry.size > 0) {
                        expanded += entry.size
                        require(expanded <= MAX_EXTRACTED_BYTES) { "MegaPack expands beyond the supported size limit." }
                    }
                    indexed[safe] = entry
                }

                val manifestEntry = indexed[MANIFEST] ?: error("MegaPack is missing $MANIFEST.")
                require(manifestEntry.size < 0 || manifestEntry.size <= MAX_MANIFEST_BYTES) { "MegaPack manifest is too large." }
                val manifestText = readEntry(zip, manifestEntry, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8).removePrefix("\uFEFF")
                val manifest = runCatching { JSONObject(manifestText) }.getOrElse { error("MegaPack manifest is not valid UTF-8 JSON.") }
                val version = manifest.optInt("version", SUPPORTED_VERSION)
                require(version in 1..SUPPORTED_VERSION) { "MegaPack version $version is not supported." }
                val cardsJson = manifest.optJSONArray("cards") ?: error("MegaPack manifest has no cards array.")
                require(cardsJson.length() in 1..MAX_CARDS) { "MegaPack must contain between 1 and $MAX_CARDS cards." }

                val cards = ArrayList<StudioCard>(cardsJson.length())
                repeat(cardsJson.length()) { index ->
                    val item = cardsJson.optJSONObject(index) ?: error("MegaPack card ${index + 1} is not an object.")
                    val title = first(item, "title", "name")
                    val description = first(item, "description", "details")
                    val badgeHeader = first(item, "badge_header", "badgeHeader", "header")
                    val primary = first(item, "badge_primary", "badgePrimary", "value")
                    val secondary = first(item, "badge_secondary", "badgeSecondary", "label", "unit")
                    val value = listOf(primary, secondary).filter(String::isNotBlank).joinToString(" ")
                    val legacy = first(item, "image", "artwork")
                    val background = first(item, "background", "background_image", "backdrop")
                    val subject = first(item, "subject", "foreground", "subject_image").ifBlank { legacy }

                    val imagePath = if (background.isNotBlank() || subject.isNotBlank()) {
                        val destination = File(assets, "card-${(index + 1).toString().padStart(3, '0')}.png")
                        composeArtwork(
                            background = background.takeIf(String::isNotBlank)?.let { readReference(zip, indexed, it) },
                            subject = subject.takeIf(String::isNotBlank)?.let { readReference(zip, indexed, it) },
                            output = destination,
                            focusX = finite(item.opt("crop_focus_x"), 0.5).coerceIn(0.0, 1.0),
                            focusY = finite(item.opt("crop_focus_y"), 0.5).coerceIn(0.0, 1.0),
                            zoom = finite(item.opt("crop_zoom"), 1.0).coerceIn(1.0, 3.0),
                        )
                        destination.canonicalPath
                    } else ""

                    cards += StudioCard(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        title = title,
                        value = value,
                        badgeHeader = badgeHeader,
                        description = description,
                        image = imagePath,
                    )
                }

                var soundtrackPath = ""
                var soundtrackVolume = 1f
                var soundtrackLoop = true
                val soundtrackObject = manifest.optJSONObject("soundtrack")
                val soundtrackRef = if (soundtrackObject != null) {
                    soundtrackVolume = finite(soundtrackObject.opt("volume"), 1.0).toFloat().coerceIn(0f, 1f)
                    soundtrackLoop = soundtrackObject.optBoolean("loop", true)
                    first(soundtrackObject, "file", "path", "audio")
                } else {
                    manifest.opt("soundtrack")?.toString().orEmpty().trim()
                }
                if (soundtrackRef.isNotBlank()) {
                    val safe = safeEntry(soundtrackRef)
                    val entry = indexed[safe] ?: error("MegaPack file '$safe' was not found.")
                    val rawSuffix = File(safe).extension.lowercase()
                    val suffix = rawSuffix.takeIf { it.isNotBlank() && it.length <= 8 && it.all(Char::isLetterOrDigit) } ?: "bin"
                    val destination = File(assets, "soundtrack.$suffix")
                    copyEntry(zip, entry, destination)
                    soundtrackPath = destination.canonicalPath
                }

                val requestedDuration = manifestDuration(manifest)
                val hasBadgeData = cards.any { it.value.isNotBlank() || it.badgeHeader.isNotBlank() }
                return StudioProject(
                    name = first(manifest, "name", "title").ifBlank { source.nameWithoutExtension },
                    cards = cards,
                    width = 1920,
                    height = 1080,
                    fps = 60,
                    showBadges = manifest.optBoolean("show_badges", true) || hasBadgeData,
                    creditsEnabled = manifest.optBoolean("credits_enabled", true),
                    soundtrack = soundtrackPath,
                    soundtrackVolume = soundtrackVolume,
                    soundtrackLoop = soundtrackLoop,
                    autoLength = requestedDuration <= 0.0,
                    customLengthSeconds = if (requestedDuration > 0.0) requestedDuration else 90.0,
                    encoderPreference = EncoderPreference.AUTO,
                )
            }
        } catch (error: Throwable) {
            assets.deleteRecursively()
            throw error
        }
    }

    private fun readReference(zip: ZipFile, indexed: Map<String, ZipEntry>, reference: String): ByteArray {
        val safe = safeEntry(reference)
        val entry = indexed[safe] ?: error("MegaPack file '$safe' was not found.")
        return readEntry(zip, entry, MAX_ENTRY_BYTES)
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "MegaPack file '${entry.name}' is too large." }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, destination: File) {
        destination.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_ENTRY_BYTES) { "MegaPack file '${entry.name}' is too large." }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun composeArtwork(
        background: ByteArray?,
        subject: ByteArray?,
        output: File,
        focusX: Double,
        focusY: Double,
        zoom: Double,
    ) {
        val width = 471
        val height = 872
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        try {
            val backgroundBitmap = background?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (backgroundBitmap != null) {
                drawCentreCrop(canvas, backgroundBitmap, RectF(0f, 0f, width.toFloat(), height.toFloat()), 0.5, 0.5, 1.0)
                backgroundBitmap.recycle()
            } else {
                canvas.drawRect(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    Paint().apply {
                        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(19, 141, 219), Color.rgb(11, 116, 190), Shader.TileMode.CLAMP)
                    },
                )
            }
            val subjectBitmap = subject?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (subjectBitmap != null) {
                drawCentreCrop(canvas, subjectBitmap, RectF(0f, 0f, width.toFloat(), height.toFloat()), focusX, focusY, zoom)
                subjectBitmap.recycle()
            }
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            canvasBitmap.recycle()
        }
    }

    private fun drawCentreCrop(canvas: Canvas, bitmap: Bitmap, destination: RectF, focusX: Double, focusY: Double, zoom: Double) {
        require(bitmap.width > 0 && bitmap.height > 0 && bitmap.width.toLong() * bitmap.height <= 64_000_000L) {
            "MegaPack image dimensions are not supported."
        }
        val baseScale = max(destination.width() / bitmap.width, destination.height() / bitmap.height)
        val scale = baseScale * zoom.toFloat()
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val overflowX = max(0f, drawWidth - destination.width())
        val overflowY = max(0f, drawHeight - destination.height())
        val left = destination.left - overflowX * focusX.toFloat()
        val top = destination.top - overflowY * focusY.toFloat()
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }
        canvas.save()
        canvas.clipRect(destination)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true })
        canvas.restore()
    }

    private fun safeEntry(reference: String): String {
        var normalized = reference.trim().replace('\\', '/')
        if (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        require(normalized.isNotBlank()) { "MegaPack contains an empty file reference." }
        require(!normalized.startsWith('/') && ':' !in normalized) { "MegaPack contains an unsafe file path." }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "MegaPack contains an unsafe file path." }
        return normalized
    }

    private fun first(objectValue: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (objectValue.has(key) && !objectValue.isNull(key)) {
                val value = objectValue.opt(key)?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun finite(value: Any?, fallback: Double): Double =
        value?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    private fun manifestDuration(manifest: JSONObject): Double {
        for (key in arrayOf("duration_seconds", "video_duration_seconds", "duration")) {
            if (manifest.has(key)) {
                val value = finite(manifest.opt(key), 0.0)
                if (value > 0.0) return value
            }
        }
        val source = manifest.optJSONObject("source")
        if (source != null) {
            val value = finite(source.opt("duration_seconds"), 0.0)
            if (value > 0.0) return value
        }
        return 0.0
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
        while (hasMoreElements()) add(nextElement())
    }
}
