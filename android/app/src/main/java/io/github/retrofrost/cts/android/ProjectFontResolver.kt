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
                val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                val base = fileCache[key] ?: runCatching { Typeface.createFromFile(file) }.getOrNull()?.also {
                    fileCache[key] = it
                }
                if (base != null) return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
            }
        }

        val family = project.fontFamily.trim()
        if (family.isNotEmpty()) return Typeface.create(family, style)
        return fallback
    }

    fun displayName(project: StudioProject): String = when {
        project.fontFile.isNotBlank() -> File(project.fontFile).nameWithoutExtension.ifBlank { "Custom font" }
        project.fontFamily.isNotBlank() -> project.fontFamily
        else -> "Renderer default"
    }

    fun isUsable(file: File): Boolean = file.isFile && runCatching { Typeface.createFromFile(file) }.getOrNull() != null
}
