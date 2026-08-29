package io.github.retrofrost.cts.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/**
 * Public Android file-handler for Cubical Compare .renderer packages.
 *
 * It deliberately does not activate anything on receipt. The package is inspected,
 * compatibility-checked and rendered through its declared engine first. Installation
 * happens only after an explicit action in the modal preflight dialog.
 */
class RendererImportActivity : ComponentActivity() {
    private val store by lazy { RendererStore(this) }

    private var candidate by mutableStateOf<RendererCandidate?>(null)
    private var previewBitmap by mutableStateOf<Bitmap?>(null)
    private var previewIndex by mutableStateOf(0)
    private var errorMessage by mutableStateOf<String?>(null)
    private var sourceName by mutableStateOf<String?>(null)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            finish()
        } else {
            inspect(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RendererImportDialog()
            }
        }
        val incoming = incomingUri(intent)
        if (incoming != null) {
            inspect(incoming)
        } else if (savedInstanceState == null) {
            picker.launch(arrayOf(RENDERER_MIME, "application/octet-stream", "*/*"))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri(intent)?.let(::inspect)
    }

    private fun incomingUri(value: Intent?): Uri? {
        if (value == null) return null
        return when (value.action) {
            Intent.ACTION_VIEW -> value.data
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) {
                value.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                value.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> value.data
        }
    }

    private fun inspect(uri: Uri) {
        errorMessage = null
        candidate = null
        previewBitmap?.recycle()
        previewBitmap = null
        previewIndex = 0
        sourceName = rendererDisplayName(uri)

        runCatching {
            if (uri.scheme == "content") {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected renderer could not be opened." }
                store.inspect(input)
            }
        }.onSuccess {
            candidate = it
            renderPreview()
        }.onFailure {
            errorMessage = it.message ?: "Renderer preflight failed."
        }
    }

    private fun rendererDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment?.substringAfterLast('/')
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    @androidx.compose.runtime.Composable
    private fun RendererImportDialog() {
        val pending = candidate
        val error = errorMessage
        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text("Import renderer") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when {
                        error != null -> {
                            Text(
                                "Cubical Compare couldn't import this renderer.",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            sourceName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(error)
                            Text(
                                "The active renderer has not been changed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        pending == null -> Text("Inspecting renderer…")
                        else -> RendererCandidateContent(pending)
                    }
                }
            },
            confirmButton = {
                if (pending != null) {
                    Button(
                        enabled = pending.report.compatible && previewBitmap != null,
                        onClick = { installAndUse(pending) },
                    ) { Text("Install & use") }
                } else if (error != null) {
                    Button(onClick = {
                        errorMessage = null
                        picker.launch(arrayOf(RENDERER_MIME, "application/octet-stream", "*/*"))
                    }) { Text("Choose another") }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { finish() }) { Text("Cancel") }
            },
        )
    }

    @androidx.compose.runtime.Composable
    private fun RendererCandidateContent(pending: RendererCandidate) {
        val spec = pending.spec
        sourceName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Text(spec.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("${spec.id} • by ${spec.author}", style = MaterialTheme.typography.bodySmall)
        Text("${spec.engine} • ${spec.precisionMode}")
        Text("API ${spec.rendererApi} • ${spec.referenceWidth}×${spec.referenceHeight} @ ${spec.referenceFps} fps")
        if (spec.canonicalFrameCount > 0 || spec.canonicalCardCount > 0) {
            Text(
                buildString {
                    if (spec.canonicalFrameCount > 0) append("${spec.canonicalFrameCount} canonical frames")
                    if (spec.canonicalFrameCount > 0 && spec.canonicalCardCount > 0) append(" • ")
                    if (spec.canonicalCardCount > 0) append("${spec.canonicalCardCount} canonical cards")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                previewBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Renderer pre-activation preview",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text("Rendering preview…", color = Color.White)
            }

            val frames = previewFrames(spec)
            if (frames.isNotEmpty()) {
                val safeIndex = previewIndex.coerceIn(0, frames.lastIndex)
                Text(
                    "Checkpoint ${safeIndex + 1}/${frames.size} • frame ${frames[safeIndex]}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        enabled = safeIndex > 0,
                        onClick = {
                            previewIndex = (safeIndex - 1).coerceAtLeast(0)
                            renderPreview()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Previous") }
                    OutlinedButton(
                        enabled = safeIndex < frames.lastIndex,
                        onClick = {
                            previewIndex = (safeIndex + 1).coerceAtMost(frames.lastIndex)
                            renderPreview()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Next") }
                }
            }
        }

        OutlinedButton(
            onClick = { copyDiagnostics(pending) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy diagnostics") }

        if (pending.report.compatible) {
            OutlinedButton(
                onClick = { installOnly(pending) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Install without activating") }
        }
    }

    private fun renderPreview() {
        val pending = candidate ?: return
        if (!pending.report.compatible) return
        val frames = previewFrames(pending.spec)
        if (frames.isEmpty()) return
        previewIndex = previewIndex.coerceIn(0, frames.lastIndex)
        previewBitmap?.recycle()
        previewBitmap = null
        runCatching {
            RendererBridge.renderWithSpec(
                previewProject(pending.spec),
                pending.spec,
                frames[previewIndex],
                640,
                360,
            )
        }.onSuccess {
            previewBitmap = it
        }.onFailure {
            errorMessage = "Renderer preview failed: ${it.message ?: "unknown renderer error"}"
        }
    }

    private fun previewFrames(spec: RendererSpec): List<Int> {
        val maximum = if (spec.canonicalFrameCount > 0) spec.canonicalFrameCount - 1 else Int.MAX_VALUE
        val explicit = spec.previewFrames.filter { it in 0..maximum }.distinct().sorted()
        if (explicit.isNotEmpty()) return explicit
        return listOf(
            0,
            spec.openingStarts.firstOrNull() ?: 0,
            spec.continuousStartFrame,
            max(spec.continuousStartFrame, (spec.canonicalFrameCount - 1).coerceAtLeast(0)),
        ).filter { it in 0..maximum }.distinct()
    }

    private fun previewProject(spec: RendererSpec): StudioProject {
        val count = min(60, max(4, spec.canonicalCardCount.takeIf { it > 0 } ?: 8))
        return StudioProject(
            name = "Renderer preflight",
            cards = List(count) { index ->
                StudioCard(
                    title = "Preview ${index + 1}",
                    value = "${max(1, (index + 1) * 10)} People",
                    badgeHeader = "1 in",
                    description = if (index % 3 == 0) "Renderer layout and animation preview" else "",
                )
            },
            width = spec.referenceWidth,
            height = spec.referenceHeight,
            fps = spec.referenceFps,
            creditsEnabled = true,
            showBadges = true,
        )
    }

    private fun installOnly(pending: RendererCandidate) {
        runCatching { store.install(pending) }
            .onSuccess { openMain("Installed ${pending.spec.name}.") }
            .onFailure { errorMessage = it.message ?: "Renderer installation failed." }
    }

    private fun installAndUse(pending: RendererCandidate) {
        runCatching {
            store.install(pending)
            store.activate(pending.spec.id)
        }.onSuccess {
            openMain("Installed and activated ${it.name}.")
        }.onFailure {
            errorMessage = it.message ?: "Renderer activation failed."
        }
    }

    private fun openMain(message: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_IMPORT_MESSAGE, message),
        )
        finish()
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
            appendLine("Canonical cards: ${value.spec.canonicalCardCount}")
            appendLine("Canonical frames: ${value.spec.canonicalFrameCount}")
            appendLine("Tracks: ${value.spec.tracks.size}")
            appendLine("Compatibility: ${value.report.summary()}")
            value.report.errors.forEach { appendLine("ERROR: $it") }
            value.report.warnings.forEach { appendLine("WARNING: $it") }
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Renderer diagnostics", text))
    }

    companion object {
        const val RENDERER_MIME = "application/vnd.cubicalcompare.renderer"
        const val EXTRA_IMPORT_MESSAGE = "renderer_import_message"
    }
}
