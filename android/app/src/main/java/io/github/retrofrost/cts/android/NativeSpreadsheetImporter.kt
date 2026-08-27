package io.github.retrofrost.cts.android

import org.w3c.dom.Element
import java.io.File
import java.io.StringReader
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Native replacement for ccengine.importers.load_spreadsheet.
 *
 * Supports the same CSV/XLSX/XLSM inputs and the same loose header aliases as
 * Cubical Compare 2.0.7 without starting Python or loading openpyxl.
 */
object NativeSpreadsheetImporter {
    private val aliases = mapOf(
        "title" to setOf("title", "name", "label", "item", "topic", "age", "card"),
        "value" to setOf("value", "amount", "score", "number", "rank", "percentage", "percent"),
        "description" to setOf("description", "desc", "details", "detail", "explanation", "subtitle", "text"),
        "image" to setOf("image", "img", "picture", "photo", "icon", "image_url", "image path", "image_path", "url"),
    )

    fun load(path: String): List<StudioCard> {
        val source = File(path).canonicalFile
        require(source.isFile) { "The selected spreadsheet could not be opened." }
        return when (source.extension.lowercase()) {
            "csv" -> rowsToCards(parseCsv(source.readText(Charsets.UTF_8).removePrefix("\uFEFF")), source.parentFile)
            "xlsx", "xlsm" -> rowsToCards(readXlsx(source), source.parentFile)
            else -> error("Cubical Compare supports CSV, XLSX and XLSM files.")
        }.also {
            require(it.isNotEmpty()) { "No cards were found in the selected file." }
        }
    }

    private fun rowsToCards(rawRows: List<List<String>>, assetBase: File): List<StudioCard> {
        val rows = rawRows.filter { row -> row.any { it.trim().isNotEmpty() } }
        if (rows.isEmpty()) return emptyList()

        val headerMap = mapHeaders(rows.first())
        val hasHeaders = looksLikeHeader(rows.first(), headerMap)
        val dataRows = if (hasHeaders) rows.drop(1) else rows

        return buildList {
            for (row in dataRows) {
                val values = row.map(String::trim)
                if (values.none(String::isNotEmpty)) continue

                val mapped = mutableMapOf<String, String>()
                if (hasHeaders) {
                    for ((index, target) in headerMap) {
                        mapped[target] = values.getOrElse(index) { "" }
                    }
                } else {
                    mapped["title"] = values.getOrElse(0) { "" }
                    mapped["value"] = values.getOrElse(1) { "" }
                    mapped["description"] = values.getOrElse(2) { "" }
                    mapped["image"] = values.getOrElse(3) { "" }
                }

                var image = mapped["image"].orEmpty()
                if (image.isNotBlank() && !image.startsWith("http://", true) && !image.startsWith("https://", true)) {
                    val candidate = File(image)
                    image = if (candidate.isAbsolute) candidate.canonicalPath else File(assetBase, image).canonicalPath
                }

                add(
                    StudioCard(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        title = mapped["title"].orEmpty(),
                        value = mapped["value"].orEmpty(),
                        description = mapped["description"].orEmpty(),
                        image = image,
                    ),
                )
            }
        }
    }

    private fun mapHeaders(headers: List<String>): Map<Int, String> {
        val mapped = linkedMapOf<Int, String>()
        val used = mutableSetOf<String>()
        headers.forEachIndexed { index, header ->
            val normalized = normalize(header)
            for ((target, names) in aliases) {
                if (target !in used && normalized in names) {
                    mapped[index] = target
                    used += target
                    break
                }
            }
        }
        return mapped
    }

    private fun looksLikeHeader(row: List<String>, headerMap: Map<Int, String>): Boolean {
        val nonEmpty = row.map(::normalize).filter(String::isNotEmpty)
        if (headerMap.size >= 2) return true
        if (headerMap.size == 1 && nonEmpty.size == 1) {
            return nonEmpty.first() in setOf("title", "value", "description", "image", "image_url", "image_path")
        }
        return false
    }

    private fun normalize(value: String): String = value.trim().lowercase().replace('-', '_')

    /** RFC-4180-ish parser with quoted fields, embedded commas and newlines. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            rows += row.toList()
            row.clear()
        }

        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == ',' -> finishField()
                !quoted && ch == '\n' -> finishRow()
                !quoted && ch == '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    finishRow()
                }
                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) finishRow()
        return rows
    }

    private fun readXlsx(source: File): List<List<String>> = ZipFile(source).use { zip ->
        val sharedStrings = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val document = parseXml(zip.getInputStream(entry).bufferedReader().use { it.readText() })
            val nodes = document.getElementsByTagName("si")
            List(nodes.length) { index -> nodes.item(index).textContent.orEmpty() }
        }.orEmpty()

        val sheetPath = firstWorksheetPath(zip)
        val sheetEntry = zip.getEntry(sheetPath) ?: error("The workbook contains no readable worksheet.")
        val document = parseXml(zip.getInputStream(sheetEntry).bufferedReader().use { it.readText() })
        val rowNodes = document.getElementsByTagName("row")

        buildList {
            for (rowIndex in 0 until rowNodes.length) {
                val rowElement = rowNodes.item(rowIndex) as? Element ?: continue
                val cellNodes = rowElement.getElementsByTagName("c")
                val values = mutableMapOf<Int, String>()
                var largestColumn = -1

                for (cellIndex in 0 until cellNodes.length) {
                    val cell = cellNodes.item(cellIndex) as? Element ?: continue
                    val reference = cell.getAttribute("r")
                    val column = columnIndex(reference)
                    largestColumn = maxOf(largestColumn, column)
                    val type = cell.getAttribute("t")
                    val raw = when (type) {
                        "inlineStr" -> cell.getElementsByTagName("is").item(0)?.textContent.orEmpty()
                        else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                    }
                    values[column] = when (type) {
                        "s" -> raw.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                        "b" -> if (raw == "1") "True" else "False"
                        else -> raw
                    }
                }

                if (largestColumn >= 0) {
                    add(List(largestColumn + 1) { column -> values[column].orEmpty() })
                }
            }
        }
    }

    private fun firstWorksheetPath(zip: ZipFile): String {
        // Prefer the first worksheet named by workbook.xml. Fall back to sheet1.
        val workbook = zip.getEntry("xl/workbook.xml") ?: return "xl/worksheets/sheet1.xml"
        val workbookDoc = parseXml(zip.getInputStream(workbook).bufferedReader().use { it.readText() })
        val firstSheet = workbookDoc.getElementsByTagName("sheet").item(0) as? Element
            ?: return "xl/worksheets/sheet1.xml"
        val relationshipId = firstSheet.getAttribute("r:id").ifBlank {
            firstSheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
        }
        if (relationshipId.isBlank()) return "xl/worksheets/sheet1.xml"

        val rels = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return "xl/worksheets/sheet1.xml"
        val relsDoc = parseXml(zip.getInputStream(rels).bufferedReader().use { it.readText() })
        val relationships = relsDoc.getElementsByTagName("Relationship")
        for (index in 0 until relationships.length) {
            val relationship = relationships.item(index) as? Element ?: continue
            if (relationship.getAttribute("Id") == relationshipId) {
                val target = relationship.getAttribute("Target").replace('\\', '/')
                return if (target.startsWith('/')) target.removePrefix("/") else "xl/${target.removePrefix("../")}".replace("xl/xl/", "xl/")
            }
        }
        return "xl/worksheets/sheet1.xml"
    }

    private fun columnIndex(reference: String): Int {
        var result = 0
        var found = false
        for (ch in reference) {
            if (!ch.isLetter()) break
            found = true
            result = result * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return if (found) result - 1 else 0
    }

    private fun parseXml(text: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        setExpandEntityReferences(false)
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder().parse(InputSource(StringReader(text)))
}
