package dev.thedataguys.cc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

class ProjectStore(private val context: Context) {
    private val projectFile = File(context.filesDir, "project.json")

    fun active(): CompareProject {
        if (!projectFile.isFile) return CompareProject.demo()
        return runCatching {
            FileInputStream(projectFile).use { parseJson(it.readBytesLimited(MAX_PROJECT_BYTES).toString(Charsets.UTF_8)) }
        }.getOrElse { CompareProject.demo() }
    }

    fun import(input: InputStream, displayName: String): CompareProject {
        val bytes = input.readBytesLimited(MAX_PROJECT_BYTES)
        val text = bytes.toString(Charsets.UTF_8)
        val project = if (displayName.endsWith(".json", ignoreCase = true) || text.trimStart().startsWith("{")) {
            parseJson(text)
        } else {
            parseCsv(text, displayName.substringBeforeLast('.').ifBlank { "Comparison" })
        }
        validate(project)
        save(project)
        return project
    }

    fun reset(): CompareProject {
        projectFile.delete()
        return CompareProject.demo()
    }

    fun export(project: CompareProject, output: OutputStream) {
        output.writer(Charsets.UTF_8).use { writer ->
            writer.write(toJson(project).toString(2))
        }
    }

    private fun save(project: CompareProject) {
        val temp = File(context.filesDir, "project.json.tmp")
        FileOutputStream(temp).use { out ->
            out.write(toJson(project).toString().toByteArray(Charsets.UTF_8))
        }
        check(temp.renameTo(projectFile) || temp.copyTo(projectFile, overwrite = true).exists()) {
            "Could not save project"
        }
        temp.delete()
    }

    private fun parseJson(text: String): CompareProject {
        val json = JSONObject(text)
        val itemsJson = json.getJSONArray("items")
        val items = ArrayList<CompareItem>(itemsJson.length())
        for (i in 0 until itemsJson.length()) {
            val item = itemsJson.getJSONObject(i)
            items += CompareItem(
                rank = item.optInt("rank", i + 1),
                title = item.getString("title"),
                subtitle = item.optString("subtitle", ""),
                value = item.optString("value", ""),
                note = item.optString("note", "")
            )
        }
        return CompareProject(
            title = json.optString("title", "Comparison"),
            subtitle = json.optString("subtitle", ""),
            items = items,
            fps = json.optInt("fps", 30),
            seconds = json.optInt("seconds", maxOf(8, items.size * 2)),
            width = json.optInt("width", 1080),
            height = json.optInt("height", 1920)
        ).also(::validate)
    }

    private fun parseCsv(text: String, fallbackTitle: String): CompareProject {
        val metadata = mutableMapOf<String, String>()
        val rows = mutableListOf<List<String>>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd('\r')
            if (line.isBlank()) return@forEach
            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                val split = body.indexOf('=')
                if (split > 0) metadata[body.substring(0, split).trim().lowercase()] = body.substring(split + 1).trim()
            } else {
                rows += parseCsvLine(line)
            }
        }
        require(rows.isNotEmpty()) { "CSV has no rows" }

        val first = rows.first().map { it.trim().lowercase() }
        val hasHeader = first.any { it in setOf("title", "subtitle", "value", "rank", "note") }
        val header = if (hasHeader) first else listOf("rank", "title", "subtitle", "value", "note")
        val dataRows = if (hasHeader) rows.drop(1) else rows

        fun column(name: String): Int = header.indexOf(name)
        val rankCol = column("rank")
        val titleCol = column("title")
        val subtitleCol = column("subtitle")
        val valueCol = column("value")
        val noteCol = column("note")
        require(titleCol >= 0) { "CSV must include a title column" }

        val items = dataRows.mapIndexedNotNull { index, row ->
            fun cell(col: Int): String = if (col >= 0 && col < row.size) row[col].trim() else ""
            val title = cell(titleCol)
            if (title.isBlank()) return@mapIndexedNotNull null
            CompareItem(
                rank = cell(rankCol).toIntOrNull() ?: index + 1,
                title = title,
                subtitle = cell(subtitleCol),
                value = cell(valueCol),
                note = cell(noteCol)
            )
        }

        return CompareProject(
            title = metadata["title"] ?: fallbackTitle,
            subtitle = metadata["subtitle"] ?: "",
            items = items,
            fps = metadata["fps"]?.toIntOrNull() ?: 30,
            seconds = metadata["seconds"]?.toIntOrNull() ?: maxOf(8, items.size * 2),
            width = metadata["width"]?.toIntOrNull() ?: 1080,
            height = metadata["height"]?.toIntOrNull() ?: 1920
        ).also(::validate)
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    cells += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        require(!quoted) { "Unclosed quote in CSV" }
        cells += current.toString()
        return cells
    }

    private fun toJson(project: CompareProject): JSONObject = JSONObject().apply {
        put("title", project.title)
        put("subtitle", project.subtitle)
        put("fps", project.fps)
        put("seconds", project.seconds)
        put("width", project.width)
        put("height", project.height)
        put("items", JSONArray().apply {
            project.items.forEach { item ->
                put(JSONObject().apply {
                    put("rank", item.rank)
                    put("title", item.title)
                    put("subtitle", item.subtitle)
                    put("value", item.value)
                    put("note", item.note)
                })
            }
        })
    }

    private fun validate(project: CompareProject) {
        require(project.title.isNotBlank() && project.title.length <= 200) { "Invalid project title" }
        require(project.subtitle.length <= 300) { "Project subtitle is too long" }
        require(project.items.size in 1..1000) { "Project needs between 1 and 1000 cards" }
        require(project.fps in 1..120) { "FPS must be between 1 and 120" }
        require(project.seconds in 1..3600) { "Duration must be between 1 and 3600 seconds" }
        require(project.width in 320..4320 && project.height in 320..7680) { "Invalid render size" }
        project.items.forEach {
            require(it.title.isNotBlank() && it.title.length <= 300) { "Invalid card title" }
            require(it.subtitle.length <= 500 && it.value.length <= 200 && it.note.length <= 1000) { "Card text is too long" }
        }
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Project file is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val MAX_PROJECT_BYTES = 4 * 1024 * 1024
    }
}
