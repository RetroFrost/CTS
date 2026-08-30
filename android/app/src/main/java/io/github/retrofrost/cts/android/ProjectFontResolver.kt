package io.github.retrofrost.cts.android

import android.graphics.Typeface
import java.io.File
import java.util.LinkedHashMap

/** Resolves the optional project-wide comparison font without changing renderer-owned typography. */
object ProjectFontResolver {
    private const val MAX_CACHE = 8
    private val fileCache = object : LinkedHashMap<String, Typeface>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > MAX_CACHE
    }

    @Synchronized
    fun resolve(project: StudioProject, fallback: Typeface, style: Int): Typeface {
        val customPath = project.fontFile.trim()
        if (customPath.isNotEmpty()) {
            val file = File(customPath)
            if (file.isFile) {
                val bold = style and Typeface.BOLD != 0
                val italic = style and Typeface.ITALIC != 0
                val weight = if (bold) 700 else 400
                val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}:$weight:$italic"
                val resolved = fileCache[key] ?: buildFileTypeface(file, weight, italic, style)?.also {
                    fileCache[key] = it
                }
                if (resolved != null) return resolved
            }
        }

        val family = project.fontFamily.trim()
        if (family.isNotEmpty()) return Typeface.create(family, style)
        return fallback
    }

    /**
     * Typeface.Builder is important for OpenType variable fonts: setWeight/setItalic
     * select the requested point on supported variation axes instead of relying on
     * synthetic bold/italic after loading only the font's default instance.
     */
    private fun buildFileTypeface(file: File, weight: Int, italic: Boolean, style: Int): Typeface? {
        return runCatching {
            Typeface.Builder(file)
                .setWeight(weight)
                .setItalic(italic)
                .build()
        }.getOrNull() ?: runCatching {
            // Keep compatibility with unusual static fonts that the builder rejects.
            val base = Typeface.createFromFile(file)
            if (style == Typeface.NORMAL) base else Typeface.create(base, style)
        }.getOrNull()
    }

    fun displayName(project: StudioProject): String = when {
        project.fontFile.isNotBlank() -> File(project.fontFile).nameWithoutExtension.ifBlank { "Custom font" }
        project.fontFamily.isNotBlank() -> project.fontFamily
        else -> "Renderer default"
    }

    fun isUsable(file: File): Boolean = file.isFile && (
        runCatching { Typeface.Builder(file).build() }.getOrNull() != null ||
            runCatching { Typeface.createFromFile(file) }.getOrNull() != null
        )
}
