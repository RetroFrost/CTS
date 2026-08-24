package dev.infinitycomparison.cc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class RenderMetadata(val frameCount: Int, val duration: Double, val fps: Int)

object RendererBridge {
    fun metadata(project: StudioProject): RenderMetadata = NativeTimeline.metadata(project)

    fun render(context: Context, project: StudioProject, frame: Int, width: Int, height: Int): Bitmap =
        NativeFrameRenderer.render(context, project, frame, width, height)

    fun importData(project: StudioProject, path: String): StudioProject {
        val source = File(path)
        val rows = when (source.extension.lowercase()) {
            "csv", "tsv", "txt" -> parseDelimited(source.readText(Charsets.UTF_8))
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

    fun importMegaPack(path: String, assets: File): StudioProject {
        val source = File(path)
        require(source.isFile) { "The selected MegaPack could not be opened." }
        require(source.length() <= 1_073_741_824L) { "MegaPack is larger than the supported size limit." }
        require(!assets.exists() || assets.listFiles().isNullOrEmpty()) { "MegaPack destination is not empty." }
        assets.mkdirs()
        try {
            ZipFile(source).use { archive ->
                val entries = archive.entries().toList()
                require(entries.size <= 1_000) { "This MegaPack contains too many files." }
                val indexed = linkedMapOf<String, ZipEntry>()
                var expanded = 0L
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val safe = safeEntry(entry.name)
                    require(!indexed.containsKey(safe)) { "MegaPack contains duplicate file '$safe'." }
                    require(entry.size in 0..67_108_864L) { "MegaPack file '$safe' is too large." }
                    expanded += entry.size
                    require(expanded <= 536_870_912L) { "MegaPack expands beyond the supported size limit." }
                    indexed[safe] = entry
                }
                val manifestEntry = indexed["megapack.json"] ?: error("MegaPack is missing megapack.json.")
                require(manifestEntry.size <= 4_194_304L) { "MegaPack manifest is too large." }
                val manifest = JSONObject(readEntry(archive, manifestEntry).toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                val version = manifest.optInt("version", 2)
                require(version in 1..2) { "MegaPack version $version is not supported." }
                val cardData = manifest.optJSONArray("cards") ?: error("MegaPack does not contain cards.")
                require(cardData.length() in 1..500) { "MegaPack must contain between 1 and 500 cards." }
                val cards = buildList {
                    repeat(cardData.length()) { index ->
                        val item = cardData.optJSONObject(index) ?: error("MegaPack card ${index + 1} is not an object.")
                        val title = first(item, "title", "name")
                        val description = first(item, "description", "details")
                        val header = first(item, "badge_header", "badgeHeader", "header")
                        val primary = first(item, "badge_primary", "badgePrimary", "value")
                        val secondary = first(item, "badge_secondary", "badgeSecondary", "label", "unit")
                        val background = first(item, "background", "background_image", "backdrop")
                        val subject = first(item, "subject", "foreground", "subject_image").ifBlank {
                            first(item, "image", "artwork")
                        }
                        var image = ""
                        if (background.isNotBlank() || subject.isNotBlank()) {
                            val output = File(assets, "card-${(index + 1).toString().padStart(3, '0')}.png")
                            composePackArtwork(
                                background.takeIf(String::isNotBlank)?.let { readReferenced(archive, indexed, it) },
                                subject.takeIf(String::isNotBlank)?.let { readReferenced(archive, indexed, it) },
                                output,
                                title.isNotBlank(),
                                description.isNotBlank(),
                                item.optDouble("crop_focus_x", 0.5).toFloat(),
                                item.optDouble("crop_focus_y", 0.5).toFloat(),
                                item.optDouble("crop_zoom", 1.0).toFloat(),
                            )
                            image = output.absolutePath
                        }
                        add(
                            StudioCard(
                                id = UUID.randomUUID().toString().replace("-", ""),
                                title = title,
                                value = listOf(primary, secondary).filter(String::isNotBlank).joinToString(" "),
                                badgeHeader = header,
                                description = description,
                                image = image,
                            ),
                        )
                    }
                }
                var soundtrackPath = ""
                var soundtrackVolume = 1f
                var soundtrackLoop = true
                val soundtrackObject = manifest.optJSONObject("soundtrack")
                val soundtrackRef = if (soundtrackObject != null) {
                    soundtrackVolume = soundtrackObject.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f)
                    soundtrackLoop = soundtrackObject.optBoolean("loop", true)
                    first(soundtrackObject, "file", "path", "audio")
                } else manifest.optString("soundtrack")
                if (soundtrackRef.isNotBlank()) {
                    val safe = safeEntry(soundtrackRef)
                    val entry = indexed[safe] ?: error("MegaPack file '$safe' was not found.")
                    val extension = File(safe).extension.takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) } ?: "bin"
                    val output = File(assets, "soundtrack.$extension")
                    archive.getInputStream(entry).use { input -> output.outputStream().use(input::copyTo) }
                    soundtrackPath = output.absolutePath
                }
                val duration = sequenceOf("duration_seconds", "video_duration_seconds", "duration")
                    .map { manifest.optDouble(it, 0.0) }.firstOrNull { it > 0.0 } ?: 0.0
                return StudioProject(
                    name = first(manifest, "name", "title").ifBlank { source.nameWithoutExtension },
                    cards = cards,
                    showBadges = manifest.optBoolean("show_badges", true) || cards.any { it.value.isNotBlank() },
                    creditsEnabled = manifest.optBoolean("credits_enabled", true),
                    soundtrack = soundtrackPath,
                    soundtrackVolume = soundtrackVolume,
                    soundtrackLoop = soundtrackLoop,
                    autoLength = duration <= 0.0,
                    customLengthSeconds = if (duration > 0.0) duration else 60.0,
                )
            }
        } catch (error: Throwable) {
            assets.deleteRecursively()
            throw error
        }
    }

    fun materialize(context: Context, uri: Uri, prefix: String): File {
        val imports = File(context.filesDir, "imports").apply { mkdirs() }
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        val extension = displayName?.substringAfterLast('.', "")?.takeIf { it.length in 1..12 && it.all(Char::isLetterOrDigit) }
            ?: context.contentResolver.getType(uri)?.substringAfterLast('/')?.substringAfterLast('+')
            ?: "bin"
        val destination = File(imports, "$prefix-${System.nanoTime()}.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "The selected file could not be opened." }
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }

    private fun rowsToCards(rows: List<List<String>>, base: File?): List<StudioCard> {
        val useful = rows.filter { row -> row.any { it.isNotBlank() } }
        if (useful.isEmpty()) return emptyList()
        val aliases = mapOf(
            "title" to setOf("title", "name", "label", "item", "topic", "age", "card"),
            "value" to setOf("value", "amount", "score", "number", "rank", "percentage", "percent"),
            "description" to setOf("description", "desc", "details", "detail", "explanation", "subtitle", "text"),
            "image" to setOf("image", "img", "picture", "photo", "icon", "image_url", "image path", "image_path", "url"),
        )
        val header = useful.first().map { it.trim().lowercase().replace('-', '_') }
        val mapping = mutableMapOf<Int, String>()
        val used = mutableSetOf<String>()
        header.forEachIndexed { index, name ->
            aliases.entries.firstOrNull { it.key !in used && name in it.value }?.key?.let { mapping[index] = it; used += it }
        }
        val named = mapping.size >= 2 || (
            mapping.size == 1 && header.count(String::isNotBlank) == 1 &&
                header.first() in setOf("title", "value", "description", "image", "image_url", "image_path")
        )
        return useful.drop(if (named) 1 else 0).mapNotNull { row ->
            val values = if (named) mapping.entries.associate { (index, key) -> key to row.getOrElse(index) { "" }.trim() }
            else mapOf(
                "title" to row.getOrElse(0) { "" }.trim(), "value" to row.getOrElse(1) { "" }.trim(),
                "description" to row.getOrElse(2) { "" }.trim(), "image" to row.getOrElse(3) { "" }.trim(),
            )
            if (values.values.all(String::isBlank)) null else StudioCard(
                title = values["title"].orEmpty(), value = values["value"].orEmpty(),
                description = values["description"].orEmpty(),
                image = values["image"].orEmpty().let { image ->
                    if (image.isBlank() || image.contains("://") || File(image).isAbsolute) image else File(base, image).absolutePath
                },
            )
        }
    }

    private fun parseDelimited(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val separator = if (firstLine.count { it == '\t' } > firstLine.count { it == ',' }) '\t' else ','
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == separator && !quoted -> { row += cell.toString(); cell.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString(); cell.clear(); rows += row; row = mutableListOf()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    private fun parseXlsx(file: File): List<List<String>> = ZipFile(file).use { archive ->
        val shared = archive.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val document = parseXml(archive.getInputStream(entry))
            val strings = document.getElementsByTagName("si")
            List(strings.length) { index ->
                val item = strings.item(index) as Element
                item.getElementsByTagName("t").let { nodes ->
                    (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
                }
            }
        }.orEmpty()
        val sheetEntry = archive.getEntry("xl/worksheets/sheet1.xml") ?: error("The workbook has no first worksheet.")
        val document = parseXml(archive.getInputStream(sheetEntry))
        val rowNodes = document.getElementsByTagName("row")
        List(rowNodes.length) { rowIndex ->
            val cells = (rowNodes.item(rowIndex) as Element).getElementsByTagName("c")
            val values = mutableMapOf<Int, String>()
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as Element
                val reference = cell.getAttribute("r")
                val column = reference.takeWhile(Char::isLetter).fold(0) { acc, char ->
                    acc * 26 + (char.uppercaseChar() - 'A' + 1)
                } - 1
                val raw = if (cell.getAttribute("t") == "inlineStr") {
                    cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
                } else cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                values[column.coerceAtLeast(cellIndex)] = if (cell.getAttribute("t") == "s") {
                    shared.getOrElse(raw.toIntOrNull() ?: -1) { "" }
                } else raw
            }
            List((values.keys.maxOrNull() ?: -1) + 1) { values[it].orEmpty() }
        }
    }

    private fun parseXml(input: InputStream) = input.use {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(it)
    }

    private fun composePackArtwork(
        background: ByteArray?, subject: ByteArray?, output: File,
        hasTitle: Boolean, hasDescription: Boolean, focusX: Float, focusY: Float, zoom: Float,
    ) {
        val resultHeight = NativeTimeline.bodyHeight - (if (hasTitle) 93 else 0) - (if (hasDescription) 115 else 0)
        val result = Bitmap.createBitmap(NativeTimeline.bodyWidth, resultHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(android.graphics.Color.rgb(19, 141, 219))
        listOfNotNull(background, subject).forEach { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("MegaPack contains an unsupported image.")
            val scale = maxOf(result.width.toFloat() / bitmap.width, result.height.toFloat() / bitmap.height) * zoom.coerceIn(1f, 3f)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (result.width - drawWidth) * focusX.coerceIn(0f, 1f)
            val top = (result.height - drawHeight) * focusY.coerceIn(0f, 1f)
            canvas.drawBitmap(
                bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            bitmap.recycle()
        }
        output.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
        result.recycle()
    }

    private fun readReferenced(archive: ZipFile, entries: Map<String, ZipEntry>, reference: String): ByteArray {
        val safe = safeEntry(reference)
        return readEntry(archive, entries[safe] ?: error("MegaPack file '$safe' was not found."))
    }

    private fun readEntry(archive: ZipFile, entry: ZipEntry): ByteArray = archive.getInputStream(entry).use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= 67_108_864) { "MegaPack file '${entry.name}' is too large." }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }

    private fun safeEntry(value: String): String {
        val normalized = value.trim().replace('\\', '/').removePrefix("./")
        require(normalized.isNotBlank() && !normalized.startsWith('/') && ':' !in normalized) { "MegaPack contains an unsafe file path." }
        require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) { "MegaPack contains an unsafe file path." }
        return normalized
    }

    private fun first(json: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (json.has(key) && !json.isNull(key)) {
                json.optString(key).trim().takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return ""
    }
}
