package io.github.retrofrost.cts.android

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class MegaPackImportState(
    val running: Boolean = false,
    val detail: String = "",
    val resultId: Long = 0L,
    val project: StudioProject? = null,
)

object MegaPackImportManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sequence = AtomicLong(0L)
    private val mutable = MutableStateFlow(MegaPackImportState())
    val state = mutable.asStateFlow()

    fun start(context: Context, uri: Uri) {
        if (mutable.value.running) return
        val app = context.applicationContext
        val id = sequence.incrementAndGet()
        mutable.value = MegaPackImportState(true, "Copying MegaPack…", id)
        scope.launch {
            var local: File? = null
            var destination: File? = null
            try {
                local = SharedRenderer.materialize(app, uri, "megapack")
                mutable.value = MegaPackImportState(true, "Importing artwork without loading the whole pack into memory…", id)
                destination = File(app.filesDir, "megapacks/${System.currentTimeMillis()}-$id")
                val project = SharedRenderer.importMegaPack(local.absolutePath, destination)
                mutable.value = MegaPackImportState(false, "MegaPack loaded · ${project.cards.size} cards", id, project)
            } catch (oom: OutOfMemoryError) {
                destination?.deleteRecursively()
                mutable.value = MegaPackImportState(false, "MegaPack import ran out of memory. The partial import was removed.", id)
            } catch (error: Throwable) {
                destination?.deleteRecursively()
                mutable.value = MegaPackImportState(false, "MegaPack failed: ${error.message ?: error.javaClass.simpleName}", id)
            } finally {
                runCatching { local?.delete() }
            }
        }
    }

    fun consumeResult(resultId: Long) {
        val current = mutable.value
        if (current.resultId == resultId && current.project != null) {
            mutable.value = current.copy(project = null)
        }
    }
}
