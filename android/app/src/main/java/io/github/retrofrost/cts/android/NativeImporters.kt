package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.math.max

object NativeImporters {
    private const val MAX_PACK_BYTES = 1_073_741_824L
    private const val MAX_EXTRACTED_BYTES = 536_870_912L
    private const val MAX_ENTRY_BYTES = 67_108_864L
    private const val MAX_MANIFEST_BYTES = 4_194_304L
    private const val MAX_ENTRIES = 1_000
    private const val MAX_CARDS = 500

    private val aliases = mapOf(
        "title" to setOf("title", "name", "label", "item", "topic", "age", "card"),
        "value" to setOf("value", "amount", "score", "number", "rank", "percentage", "percent"),
        "badge_header" to setOf("badge_header", "badgeheader", "header"),
        "description" to setOf("description", "desc", "details", "detail", "explanation", "subtitle", "text"),
        "image" to setOf("image", "img", "picture", "photo", "icon", "image_url", "image path", "image_path", "url"),
    )

    fun importData(project: StudioProject, source: File): StudioProject {
        val rows = when (source.extension.lowercase()) {
            "csv", "txt", "tsv" -> parseDelimited(source.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            "xlsx", "xlsm" -> parseXlsx(source)
            else -> error("Cubical Compare supports CSV, TSV, XLSX and XLSM files.")
        }
        val cards = rowsToCards(rows, source.parentFile)
        require(cards.isNotEmpty()) { "No cards were found in the selected file." }
        return project.copy(
            name = source.nameWithoutExtension.replace('_', ' ').trim().ifBlank { project.name },
            cards = cards,
        )
    }

    fun importMegaPack(source: File, assets: File): StudioProject {
        require(source.isFile) { "The selected MegaPack could not be opened." }
        require(source.length() <= MAX_PACK_BYTES) { "MegaPack is larger than the supported size limit." }
        require(!assets.exists() || assets.listFiles().isNullOrEmpty()) { "MegaPack destination is not empty." }
        assets.mkdirs()

        try {
            ZipFile(source).use { zip ->
                val entries = zip.entries().toList()
                require(entries.size <= MAX_ENTRIES) { "This MegaPack contains too many files." }
                val indexed = mutableMapOf<String, java.util.zip.ZipEntry>()
                var declaredSize = 0L
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    val safe = safeEntry(entry.name)
                    require(!indexed.containsKey(safe)) { "MegaPack contains duplicate file '$safe'." }
                    require(entry.size < 0 || entry.size <= MAX_ENTRY_BYTES) { "MegaPack file '$safe' is too large." }
                    if (entry.size > 0) declaredSize += entry.size
                    require(declaredSize <= MAX_EXTRACTED_BYTES) { "MegaPack expands beyond the supported size limit." }
                    indexed[safe] = entry
                }

                val manifestEntry = indexed["megapack.json"] ?: error("MegaPack is missing megapack.json.")
                require(manifestEntry.size < 0 || manifestEntry.size <= MAX_MANIFEST_BYTES) { "MegaPack manifest is too large." }
                val manifestBytes = readEntry(zip, manifestEntry, MAX_MANIFEST_BYTES)
                val manifest = JSONObject(String(manifestBytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
                val version = manifest.optInt("version", 2)
                require(version in 1..2) { "MegaPack version $version is not supported." }
                val cardArray = manifest.optJSONArray("cards") ?: error("MegaPack manifest has no cards array.")
                require(cardArray.length() in 1..MAX_CARDS) { "MegaPack must contain between 1 and $MAX_CARDS cards." }

                var actualBytes = manifestBytes.size.toLong()
                fun bytes(reference: String): ByteArray? {
                    if (reference.isBlank()) return null
                    val safe = safeEntry(reference)
                    val entry = indexed[safe] ?: error("MegaPack file '$safe' was not found.")
                    val data = readEntry(zip, entry, MAX_ENTRY_BYTES)
                    actualBytes += data.size
                    require(actualBytes <= MAX_EXTRACTED_BYTES) { "MegaPack expands beyond the supported size limit." }
                    return data
                }

                val cards = ArrayList<StudioCard>(cardArray.length())
                repeat(cardArray.length()) { index ->
                    val item = cardArray.getJSONObject(index)
                    val title = firstString(item, "title", "name")
                    val description = firstString(item, "description", "details")
                    val header = firstString(item, "badge_header", "badgeHeader", "header")
                    val primary = firstString(item, "badge_primary", "badgePrimary", "value")
                    val secondary = firstString(item, "badge_secondary", "badgeSecondary", "label", "unit")
                    val value = listOf(primary, secondary).filter { it.isNotBlank() }.joinToString(" ")
                    val legacy = firstString(item, "image", "artwork")
                    val backgroundRef = firstString(item, "background", "background_image", "backdrop")
                    val subjectRef = firstString(item, "subject", "foreground", "subject_image").ifBlank { legacy }
                    val background = bytes(backgroundRef)
                    val subject = bytes(subjectRef)
                    var imagePath = ""
                    if (background != null || subject != null) {
                        val descriptionHeight = if (description.isBlank()) 0 else 115
                        val titleHeight = if (title.isBlank()) 0 else 93
                        val imageHeight = (1080 - titleHeight - descriptionHeight).coerceAtLeast(1)
                        val artwork = composeArtwork(
                            background,
                            subject,
                            471,
                            imageHeight,
                            finite(item.opt("crop_focus_x"), 0.5).coerceIn(0.0, 1.0),
                            finite(item.opt("crop_focus_y"), 0.5).coerceIn(0.0, 1.0),
                            finite(item.opt("crop_zoom"), 1.0).coerceIn(1.0, 3.0),
                        )
                        val output = File(assets, "card-${(index + 1).toString().padStart(3, '0')}.png")
                        FileOutputStream(output).use { artwork.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        artwork.recycle()
                        imagePath = output.absolutePath
                    }
                    cards += StudioCard(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        title = title,
                        value = value,
                        badgeHeader = header,
                        description = description,
                        image = imagePath,
                    )
                }

                var soundtrackPath = ""
                var soundtrackVolume = 1f
                var soundtrackLoop = true
                val soundtrack = manifest.opt("soundtrack")
                val soundtrackRef = when (soundtrack) {
                    is JSONObject -> {
                        soundtrackVolume = finite(soundtrack.opt("volume"), 1.0).toFloat().coerceIn(0f, 1f)
                        soundtrackLoop = soundtrack.optBoolean("loop", true)
                        firstString(soundtrack, "file", "path", "audio")
                    }
                    null, JSONObject.NULL -> ""
                    else -> soundtrack.toString().trim()
                }
                if (soundtrackRef.isNotBlank()) {
                    val safe = safeEntry(soundtrackRef)
                    val entry = indexed[safe] ?: error("MegaPack file '$safe' was not found.")
                    val suffix = File(safe).extension.lowercase().takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) } ?: "bin"
                    val output = File(assets, "soundtrack.$suffix")
                    zip.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                    soundtrackPath = output.absolutePath
                }

                val duration = manifestDuration(manifest)
                val hasBadgeData = cards.any { it.value.isNotBlank() || it.badgeHeader.isNotBlank() }
                return StudioProject(
                    name = firstString(manifest, "name", "title").ifBlank { source.nameWithoutExtension },
                    cards = cards,
                    width = 1920,
                    height = 1080,
                    fps = 60,
                    showBadges = manifest.optBoolean("show_badges", true) || hasBadgeData,
                    creditsEnabled = manifest.optBoolean("credits_enabled", true),
                    soundtrack = soundtrackPath,
                    soundtrackVolume = soundtrackVolume,
                    soundtrackLoop = soundtrackLoop,
                    autoLength = duration <= 0.0,
                    customLengthSeconds = if (duration > 0.0) duration else 90.0,
                )
            }
        } catch (error: Throwable) {
            assets.deleteRecursively()
            throw error
        }
    }

    private fun rowsToCards(rowsInput: List<List<String>>, assetBase: File?): List<StudioCard> {
        val rows = rowsInput.filter { row -> row.any { it.trim().isNotEmpty() } }
        if (rows.isEmpty()) return emptyList()
        val headerMap = mapHeaders(rows.first())
        val nonEmpty = rows.first().map { normalize(it) }.filter { it.isNotEmpty() }
        val hasHeader = headerMap.size >= 2 || (headerMap.size == 1 && nonEmpty.size == 1 && nonEmpty.first() in setOf("title", "value", "description", "image", "image_url", "image_path"))
        val data = if (hasHeader) rows.drop(1) else rows
        return data.mapNotNull { row ->
            if (row.all { it.isBlank() }) return@mapNotNull null
            val mapped = mutableMapOf<String, String>()
            if (hasHeader) {
                headerMap.forEach { (index, target) -> mapped[target] = row.getOrElse(index) { "" }.trim() }
            } else {
                mapped["title"] = row.getOrElse(0) { "" }.trim()
                mapped["value"] = row.getOrElse(1) { "" }.trim()
                mapped["description"] = row.getOrElse(2) { "" }.trim()
                mapped["image"] = row.getOrElse(3) { "" }.trim()
            }
            var image = mapped["image"].orEmpty()
            if (image.isNotBlank() && !image.startsWith("http://", true) && !image.startsWith("https://", true)) {
                val file = File(image)
                if (!file.isAbsolute && assetBase != null) image = File(assetBase, image).absolutePath
            }
            StudioCard(
                title = mapped["title"].orEmpty(),
                value = mapped["value"].orEmpty(),
                badgeHeader = mapped["badge_header"].orEmpty(),
                description = mapped["description"].orEmpty(),
                image = image,
            )
        }
    }

    private fun mapHeaders(row: List<String>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val used = mutableSetOf<String>()
        row.forEachIndexed { index, raw ->
            val value = normalize(raw)
            aliases.forEach { (target, names) ->
                if (target !in used && value in names) {
                    result[index] = target
                    used += target
                    return@forEach
                }
            }
        }
        return result
    }

    private fun normalize(value: String) = value.trim().lowercase().replace('-', '_')

    private fun parseDelimited(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delimiter = listOf(',', '\t', ';', '|').maxByOrNull { candidate -> firstLine.count { it == candidate } } ?: ','
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    cell.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> { row += cell.toString(); cell.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString(); cell.clear(); rows += row.toList(); row.clear()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row.toList() }
        return rows
    }

    private fun parseXlsx(source: File): List<List<String>> = ZipFile(source).use { zip ->
        val shared = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            zip.getInputStream(entry).use(::parseSharedStrings)
        } ?: emptyList()
        val sheetPath = resolveFirstSheet(zip)
        val sheet = zip.getEntry(sheetPath) ?: error("The workbook has no readable worksheet.")
        zip.getInputStream(sheet).use { parseSheet(it.readBytes(), shared) }
    }

    private fun resolveFirstSheet(zip: ZipFile): String {
        val workbook = zip.getEntry("xl/workbook.xml") ?: return "xl/worksheets/sheet1.xml"
        var relationId = ""
        zip.getInputStream(workbook).use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.eventType != XmlPullParser.END_DOCUMENT && relationId.isBlank()) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
                    repeat(parser.attributeCount) { index ->
                        if (parser.getAttributeName(index) == "id") relationId = parser.getAttributeValue(index)
                    }
                }
                parser.next()
            }
        }
        if (relationId.isBlank()) return "xl/worksheets/sheet1.xml"
        val rels = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return "xl/worksheets/sheet1.xml"
        zip.getInputStream(rels).use { input ->
            val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship" && parser.getAttributeValue(null, "Id") == relationId) {
                    val target = parser.getAttributeValue(null, "Target") ?: break
                    return if (target.startsWith("/")) target.removePrefix("/") else "xl/${target.removePrefix("./")}".replace("xl/xl/", "xl/")
                }
                parser.next()
            }
        }
        return "xl/worksheets/sheet1.xml"
    }

    private fun parseSharedStrings(input: java.io.InputStream): List<String> {
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val values = mutableListOf<String>()
        var inside = false
        val current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "si") { inside = true; current.clear() }
                XmlPullParser.TEXT -> if (inside) current.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") { values += current.toString(); inside = false }
            }
            parser.next()
        }
        return values
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var cellColumn = 0
        var cellType = ""
        var cellValue = ""
        var inValue = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = mutableListOf()
                    "c" -> {
                        cellColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                        cellType = parser.getAttributeValue(null, "t").orEmpty()
                        cellValue = ""
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) cellValue += parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        while (row.size <= cellColumn) row += ""
                        row[cellColumn] = when (cellType) {
                            "s" -> shared.getOrElse(cellValue.toIntOrNull() ?: -1) { "" }
                            "b" -> if (cellValue == "1") "True" else "False"
                            else -> cellValue
                        }
                    }
                    "row" -> rows += row.toList()
                }
            }
            parser.next()
        }
        return rows
    }

    private fun columnIndex(reference: String): Int {
        var result = 0
        for (char in reference) {
            if (!char.isLetter()) break
            result = result * 26 + (char.uppercaseChar() - 'A' + 1)
        }
        return (result - 1).coerceAtLeast(0)
    }

    private fun safeEntry(value: String): String {
        val normalized = value.trim().replace('\\', '/').removePrefix("./")
        require(normalized.isNotBlank() && !normalized.startsWith('/') && ':' !in normalized) { "MegaPack contains an unsafe file path." }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "MegaPack contains an unsafe file path." }
        return normalized
    }

    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "MegaPack file '${entry.name}' is too large." }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun composeArtwork(backgroundBytes: ByteArray?, subjectBytes: ByteArray?, width: Int, height: Int, focusX: Double, focusY: Double, zoom: Double): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        if (backgroundBytes != null) {
            val background = decodeImage(backgroundBytes)
            drawCentreCrop(canvas, background, width, height, 0.5, 0.5, 1.0)
            background.recycle()
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(19, 141, 219), Color.rgb(11, 116, 190), Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        if (subjectBytes != null) {
            val subject = decodeImage(subjectBytes)
            drawCentreCrop(canvas, subject, width, height, focusX, focusY, zoom)
            subject.recycle()
        }
        return output
    }

    private fun decodeImage(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 64_000_000L) { "MegaPack image dimensions are not supported." }
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "MegaPack contains an unsupported image." }
    }

    private fun drawCentreCrop(canvas: Canvas, source: Bitmap, width: Int, height: Int, focusX: Double, focusY: Double, zoom: Double) {
        val destinationAspect = width.toDouble() / height.coerceAtLeast(1)
        val sourceAspect = source.width.toDouble() / source.height.coerceAtLeast(1)
        val baseWidth: Double
        val baseHeight: Double
        if (sourceAspect >= destinationAspect) {
            baseHeight = source.height.toDouble(); baseWidth = baseHeight * destinationAspect
        } else {
            baseWidth = source.width.toDouble(); baseHeight = baseWidth / destinationAspect
        }
        val cropWidth = max(1.0, baseWidth / zoom)
        val cropHeight = max(1.0, baseHeight / zoom)
        val left = (source.width * focusX - cropWidth / 2.0).coerceIn(0.0, max(0.0, source.width - cropWidth))
        val top = (source.height * focusY - cropHeight / 2.0).coerceIn(0.0, max(0.0, source.height - cropHeight))
        val src = Rect(left.toInt(), top.toInt(), (left + cropWidth).toInt().coerceAtMost(source.width), (top + cropHeight).toInt().coerceAtMost(source.height))
        canvas.drawBitmap(source, src, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun firstString(objectValue: JSONObject, vararg keys: String): String {
        keys.forEach { key -> if (objectValue.has(key) && objectValue.opt(key) != JSONObject.NULL) objectValue.optString(key).trim().takeIf { it.isNotBlank() }?.let { return it } }
        return ""
    }

    private fun finite(value: Any?, fallback: Double): Double {
        val result = when (value) {
            is Number -> value.toDouble()
            else -> value?.toString()?.toDoubleOrNull()
        } ?: fallback
        return if (result.isFinite()) result else fallback
    }

    private fun manifestDuration(manifest: JSONObject): Double {
        listOf("duration_seconds", "video_duration_seconds", "duration").forEach { key ->
            val value = finite(manifest.opt(key), 0.0)
            if (value > 0.0) return value
        }
        val source = manifest.optJSONObject("source")
        if (source != null) {
            val value = finite(source.opt("duration_seconds"), 0.0)
            if (value > 0.0) return value
        }
        return 0.0
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val result = mutableListOf<T>()
    while (hasMoreElements()) result += nextElement()
    return result
}
