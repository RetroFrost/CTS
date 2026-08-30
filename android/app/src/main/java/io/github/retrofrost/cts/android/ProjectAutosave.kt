package io.github.retrofrost.cts.android

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
        val bytes = project.toJson().toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun clear(context: Context) {
        File(File(context.filesDir, "autosave"), FILE_NAME).delete()
    }
}
