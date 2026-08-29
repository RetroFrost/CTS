package io.github.retrofrost.cts.android

import android.content.Context
import java.io.File

object ProjectAutosave {
    private const val FILE_NAME = "current.ccproject.json"

    fun load(context: Context): StudioProject? = runCatching {
        val file = File(File(context.filesDir, "autosave"), FILE_NAME)
        if (!file.isFile) null else StudioProject.fromJson(file.readText())
    }.getOrNull()

    fun save(context: Context, project: StudioProject) {
        val dir = File(context.filesDir, "autosave").apply { mkdirs() }
        val destination = File(dir, FILE_NAME)
        val temp = File(dir, "$FILE_NAME.tmp")
        temp.writeText(project.toJson())
        if (destination.exists()) destination.delete()
        check(temp.renameTo(destination)) { "Could not update autosave." }
    }

    fun clear(context: Context) {
        File(File(context.filesDir, "autosave"), FILE_NAME).delete()
    }
}
