package io.github.retrofrost.cts.android.importer

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.ModelMode
import io.github.retrofrost.cts.android.model.SoundtrackSettings
import io.github.retrofrost.cts.android.model.VisualModel
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class MegaPackImportResult(
    val project: CtsProject,
    val packName: String,
    val extractedFiles: Int,
    val warnings: List<String> = emptyList(),
)

/** Imports a complete, portable CTS project without trusting paths from the ZIP. */
object MegaPackImporter {
    private const val MANIFEST_NAME = "megapack.json"
    private const val SUPPORTED_VERSION = 1
    private const val MAX_PACK_BYTES = 1_073_741_824L
    private const val MAX_EXTRACTED_BYTES = 536_870_912L
    private const val MAX_ENTRY_BYTES = 67_108_864L
    private const val MAX_MANIFEST_BYTES = 4_194_304L
    private const val MAX_ENTRIES = 1_000
    private const val MAX_CARDS = 500

    fun importPack(context: Context, source: Uri): MegaPackImportResult {
        val temporaryZip = File(context.cacheDir, "cts-megapack-${UUID.randomUUID()}.zip")
        val outputDirectory = File(context.filesDir, "megapacks/${UUID.randomUUID()}")
        try {
            copyPackToCache(context, source, temporaryZip)
            ZipFile(temporaryZip).use { zip ->
                require(zip.size() <= MAX_ENTRIES) { "This MegaPack contains too many files." }
                val manifestEntry = zip.getEntry(MANIFEST_NAME)
                    ?: error("MegaPack is missing $MANIFEST_NAME.")
                val manifest = JSONObject(readLimited(zip, manifestEntry, MAX_MANIFEST_BYTES).decodeToString())
                val version = manifest.optInt("version", SUPPORTED_VERSION)
                require(version in 1..SUPPORTED_VERSION) { "MegaPack version $version is not supported." }

                val cardsJson = manifest.optJSONArray("cards")
                    ?: error("MegaPack manifest has no cards array.")
                require(cardsJson.length() in 1..MAX_CARDS) {
                    "MegaPack must contain between 1 and $MAX_CARDS cards."
                }
                check(outputDirectory.mkdirs()) { "Could not create storage for the MegaPack." }

                var extractedBytes = 0L
                val extractedImages = mutableMapOf<String, String>()
                fun extract(reference: String, index: Int, prefix: String): String {
                    val safeReference = safeEntryReference(reference)
                    return extractedImages.getOrPut(safeReference) {
                        val entry = findEntry(zip, safeReference)
                            ?: error("MegaPack file '$safeReference' was not found.")
                        val extension = safeExtension(safeReference)
                        val output = File(
                            outputDirectory,
                            "$prefix-${index.toString().padStart(3, '0')}.$extension",
                        )
                        val written = extractLimited(zip, entry, output, MAX_ENTRY_BYTES)
                        extractedBytes += written
                        require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                            "MegaPack expands beyond the supported size limit."
                        }
                        output.absolutePath
                    }
                }

                val cards = List(cardsJson.length()) { index ->
                    val item = cardsJson.optJSONObject(index)
                        ?: error("MegaPack card ${index + 1} is not an object.")
                    val cardId = UUID.randomUUID().toString()
                    val imageReference = item.firstString("image", "artwork")
                    CtsCard(
                        id = cardId,
                        badgePrimary = item.firstString("badge_primary", "badgePrimary", "value"),
                        badgeSecondary = item.firstString("badge_secondary", "badgeSecondary", "label", "unit"),
                        title = item.firstString("title", "name"),
                        description = item.firstString("description", "details"),
                        imageSubcard = ImageSubcard(
                            parentCardId = cardId,
                            source = imageReference.takeIf { it.isNotBlank() }
                                ?.let { extract(it, index + 1, "card") },
                            cropFocusX = item.optDouble("crop_focus_x", 0.5).toFloat(),
                            cropFocusY = item.optDouble("crop_focus_y", 0.5).toFloat(),
                            cropZoom = item.optDouble("crop_zoom", 1.0).toFloat(),
                        ),
                    )
                }

                val warnings = validateCardImages(cards)

                val soundtrackObject = manifest.optJSONObject("soundtrack")
                val soundtrackReference = if (soundtrackObject != null) {
                    soundtrackObject.firstString("file", "path", "audio").takeIf { it.isNotBlank() }
                } else {
                    manifest.optString("soundtrack").takeIf { it.isNotBlank() }
                }
                val soundtrackPath = soundtrackReference?.let { extract(it, 1, "soundtrack") }
                val soundtrack = SoundtrackSettings(
                    uri = soundtrackPath,
                    displayName = soundtrackObject?.firstString("display_name", "displayName", "name")
                        ?.takeIf { it.isNotBlank() }
                        ?: soundtrackReference?.substringAfterLast('/').orEmpty(),
                    volume = soundtrackObject?.optDouble("volume", 1.0)?.toFloat() ?: 1f,
                    loop = soundtrackObject?.optBoolean("loop", true) ?: true,
                )

                val model = VisualModel.fromId(manifest.firstString("model", "model_id"))
                val mode = ModelMode.fromId(manifest.firstString("model_mode", "mode"))
                val packName = manifest.firstString("name", "title").ifBlank { "CTS MegaPack" }
                val project = CtsProject(
                    name = packName,
                    model = model,
                    modelMode = mode,
                    cards = cards,
                    showHexagons = manifest.optBoolean("show_badges", true),
                    showIntro = manifest.optBoolean("show_intro", true),
                    showDisclaimer = manifest.optBoolean("show_disclaimer", true),
                    showOutro = manifest.optBoolean("show_outro", true),
                    soundtrack = soundtrack,
                ).normalized()
                return MegaPackImportResult(project, packName, extractedImages.size, warnings)
            }
        } catch (error: Throwable) {
            outputDirectory.deleteRecursively()
            throw error
        } finally {
            temporaryZip.delete()
        }
    }

    internal fun safeEntryReference(reference: String): String {
        val normalized = reference.trim().replace('\\', '/').removePrefix("./")
        require(normalized.isNotBlank()) { "MegaPack contains an empty file reference." }
        require(!normalized.startsWith('/') && ':' !in normalized) { "MegaPack contains an unsafe file path." }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "MegaPack contains an unsafe file path."
        }
        return normalized
    }

    private fun copyPackToCache(context: Context, source: Uri, destination: File) {
        context.contentResolver.openInputStream(source)?.use { input ->
            FileOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_PACK_BYTES) { "MegaPack is larger than the supported size limit." }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("The selected MegaPack could not be opened.")
    }

    private fun findEntry(zip: ZipFile, safeReference: String): ZipEntry? =
        zip.entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && runCatching { safeEntryReference(entry.name) }.getOrNull() == safeReference
        }

    private fun readLimited(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        require(entry.size < 0 || entry.size <= limit) { "MegaPack manifest is too large." }
        return zip.getInputStream(entry).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit) { "MegaPack manifest is too large." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun extractLimited(zip: ZipFile, entry: ZipEntry, output: File, limit: Long): Long {
        require(!entry.isDirectory) { "MegaPack references a directory instead of a file." }
        require(entry.size < 0 || entry.size <= limit) { "MegaPack file '${entry.name}' is too large." }
        var total = 0L
        zip.getInputStream(entry).use { input ->
            output.outputStream().buffered().use { destination ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= limit) { "MegaPack file '${entry.name}' is too large." }
                    destination.write(buffer, 0, read)
                }
            }
        }
        return total
    }

    private fun safeExtension(reference: String): String = reference.substringAfterLast('.', "bin")
        .lowercase()
        .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }
        ?: "bin"

    private fun JSONObject.firstString(vararg keys: String): String = keys.asSequence()
        .map { key -> optString(key).trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    private fun validateCardImages(cards: List<CtsCard>): List<String> {
        val imageCards = cards.mapIndexedNotNull { cardIndex, card ->
            card.imageSubcard.source?.let { cardIndex to File(it) }
        }
        if (imageCards.isEmpty()) return listOf("This MegaPack has no card images.")

        val rasters = imageCards.asSequence().map { (cardIndex, file) ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "MegaPack image for card ${cardIndex + 1} is not a supported image."
            }
            var sampleSize = 1
            while (kotlin.math.max(bounds.outWidth, bounds.outHeight) / sampleSize > 384) sampleSize *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            ) ?: error("MegaPack image for card ${cardIndex + 1} could not be decoded.")
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                CardRaster(bitmap.width, bitmap.height, pixels)
            } finally {
                bitmap.recycle()
            }
        }
        val analyses = CardImageRecognizer.analyze(rasters)
        return buildList {
            analyses.forEachIndexed { imageIndex, analysis ->
                val cardNumber = imageCards[imageIndex].first + 1
                when {
                    analysis.blank -> add("Card $cardNumber may contain a blank image.")
                    analysis.duplicateOf != null -> {
                        val duplicateCard = imageCards[analysis.duplicateOf].first + 1
                        add("Card $cardNumber appears to duplicate card $duplicateCard.")
                    }
                }
            }
            val missing = cards.count { it.imageSubcard.source.isNullOrBlank() }
            if (missing > 0) add("$missing card${if (missing == 1) " has" else "s have"} no image.")
        }
    }
}
