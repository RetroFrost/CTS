package io.github.retrofrost.cts.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** Public Android handler for .renderer packages. All imports are preflighted in a modal dialog. */
class RendererImportActivity : ComponentActivity() {
    private val store by lazy { RendererStore(this) }
    private var candidate by mutableStateOf<RendererCandidate?>(null)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var error by mutableStateOf<String?>(null)
    private var sourceName by mutableStateOf<String?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) finish() else inspect(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ImportDialog() } }
        incomingUri(intent)?.let(::inspect) ?: if (savedInstanceState == null) {
            picker.launch(arrayOf(RENDERER_MIME, "application/octet-stream", "*/*"))
        } else Unit
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri(intent)?.let(::inspect)
    }

    private fun incomingUri(value: Intent?): Uri? = when (value?.action) {
        Intent.ACTION_VIEW -> value.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            value.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            value.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> value?.data
    }

    private fun inspect(uri: Uri) {
        preview?.recycle(); preview = null; candidate = null; error = null
        sourceName = displayName(uri)
        Thread {
            runCatching {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "The selected renderer could not be opened." }
                    store.inspect(input)
                }
            }.onSuccess { pending ->
                val bitmap = if (pending.report.compatible) runCatching {
                    RendererBridge.renderWithSpec(previewProject(pending.spec), pending.spec, previewFrame(pending.spec), 640, 360)
                }.getOrNull() else null
                runOnUiThread { candidate = pending; preview = bitmap }
            }.onFailure { failure -> runOnUiThread { error = failure.message ?: "Renderer preflight failed." } }
        }.start()
    }

    @androidx.compose.runtime.Composable
    private fun ImportDialog() {
        val pending = candidate
        val failure = error
        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text("Import renderer") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        failure != null -> {
                            Text("Cubical Compare couldn't import this renderer.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                            sourceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(failure)
                            Text("The active renderer was not changed.", style = MaterialTheme.typography.bodySmall)
                        }
                        pending == null -> Text("Inspecting renderer…")
                        else -> CandidateContent(pending)
                    }
                }
            },
            confirmButton = {
                when {
                    pending != null -> Button(enabled = pending.report.compatible && preview != null, onClick = { installAndUse(pending) }) { Text("Install & use") }
                    failure != null -> Button(onClick = {
                        error = null
                        picker.launch(arrayOf(RENDERER_MIME, "application/octet-stream", "*/*"))
                    }) { Text("Choose another") }
                }
            },
            dismissButton = { OutlinedButton(onClick = { finish() }) { Text("Cancel") } },
        )
    }

    @androidx.compose.runtime.Composable
    private fun CandidateContent(pending: RendererCandidate) {
        val spec = pending.spec
        sourceName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Text(spec.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("${spec.id} • by ${spec.author}", style = MaterialTheme.typography.bodySmall)
        Text("${spec.engine} • ${spec.precisionMode}")
        Text("API ${spec.rendererApi} • ${spec.referenceWidth}×${spec.referenceHeight} @ ${spec.referenceFps} FPS")
        if (spec.canonicalFrameCount > 0) Text("${spec.canonicalFrameCount} canonical frames", style = MaterialTheme.typography.bodySmall)
        if (spec.canonicalCardCount > 0) Text("${spec.canonicalCardCount} canonical cards", style = MaterialTheme.typography.bodySmall)
        Text("SHA-256 ${pending.sha256}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(
            pending.report.summary(),
            fontWeight = FontWeight.SemiBold,
            color = if (pending.report.compatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        pending.report.errors.forEach { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
        pending.report.warnings.forEach { Text("Warning: $it", color = MaterialTheme.colorScheme.tertiary) }
        if (pending.report.compatible) {
            Text("Pre-activation preview", fontWeight = FontWeight.SemiBold)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                preview?.let { Image(it.asImageBitmap(), "Renderer preview", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) }
                    ?: Text("Rendering preview…", color = Color.White)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { copyDiagnostics(pending) }, modifier = Modifier.weight(1f)) { Text("Diagnostics") }
            OutlinedButton(enabled = pending.report.compatible, onClick = { installOnly(pending) }, modifier = Modifier.weight(1f)) { Text("Install only") }
        }
    }

    private fun installOnly(pending: RendererCandidate) {
        runCatching { store.install(pending) }
            .onSuccess { openMain() }
            .onFailure { error = it.message ?: "Renderer installation failed." }
    }

    private fun installAndUse(pending: RendererCandidate) {
        runCatching {
            store.install(pending)
            store.activate(pending.spec.id)
        }.onSuccess { openMain() }
            .onFailure { error = it.message ?: "Renderer activation failed." }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun previewFrame(spec: RendererSpec): Int {
        val maximum = if (spec.canonicalFrameCount > 0) spec.canonicalFrameCount - 1 else Int.MAX_VALUE
        return spec.previewFrames.firstOrNull { it in 0..maximum }
            ?: spec.openingStarts.firstOrNull()?.coerceIn(0, maximum)
            ?: 0
    }

    private fun previewProject(spec: RendererSpec): StudioProject {
        val count = min(60, max(4, spec.canonicalCardCount.takeIf { it > 0 } ?: 8))
        return StudioProject(
            name = "Renderer preflight",
            cards = List(count) { index -> StudioCard(
                title = "Preview ${index + 1}", value = "${max(1, (index + 1) * 10)} People", badgeHeader = "1 in",
                description = if (index % 3 == 0) "Renderer layout and animation preview" else "",
            ) },
            width = spec.referenceWidth, height = spec.referenceHeight, fps = spec.referenceFps,
            creditsEnabled = true, showBadges = true, introMode = IntroMode.RENDERER,
        )
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment?.substringAfterLast('/')
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    private fun copyDiagnostics(value: RendererCandidate) {
        val text = buildString {
            appendLine("Cubical Compare renderer preflight")
            appendLine("Source: ${sourceName.orEmpty()}")
            appendLine("Name: ${value.spec.name}")
            appendLine("ID: ${value.spec.id}")
            appendLine("Author: ${value.spec.author}")
            appendLine("SHA-256: ${value.sha256}")
            appendLine("Format: ${value.spec.formatVersion}")
            appendLine("Renderer API: ${value.spec.rendererApi}")
            appendLine("Engine: ${value.spec.engine}")
            appendLine("Precision: ${value.spec.precisionMode}")
            appendLine("Reference: ${value.spec.referenceWidth}x${value.spec.referenceHeight} @ ${value.spec.referenceFps} fps")
            appendLine("Compatibility: ${value.report.summary()}")
            value.report.errors.forEach { appendLine("ERROR: $it") }
            value.report.warnings.forEach { appendLine("WARNING: $it") }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Renderer diagnostics", text))
    }

    companion object {
        const val RENDERER_MIME = "application/vnd.cubicalcompare.renderer"
    }
}
