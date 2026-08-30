package io.github.retrofrost.cts.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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

class RendererManagerActivity : ComponentActivity() {
    private val store by lazy { RendererStore(this) }
    private var active by mutableStateOf(RendererRuntime.active)
    private var candidate by mutableStateOf<RendererCandidate?>(null)
    private var installed by mutableStateOf<List<InstalledRenderer>>(emptyList())
    private var previewBitmap by mutableStateOf<Bitmap?>(null)
    private var previewIndex by mutableStateOf(0)
    private var message by mutableStateOf("Renderer files are declarative and sandboxed. No executable code is loaded.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refresh()
        importFromIntent(intent)
        setContent {
            val importRenderer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "The selected renderer could not be opened." }
                            store.inspect(input)
                        }
                    }.onSuccess {
                        showCandidate(it, "Preflight finished. Nothing has been activated yet.")
                    }.onFailure { message = it.message ?: "Renderer preflight failed." }
                }
            }
            val exportRenderer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.openOutputStream(uri, "w").use { output ->
                            requireNotNull(output) { "The destination could not be opened." }
                            store.export(output)
                        }
                    }.onSuccess { message = "Active renderer exported." }
                        .onFailure { message = it.message ?: "Renderer export failed." }
                }
            }

            MaterialTheme {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text("Cubical Compare renderers", style = MaterialTheme.typography.headlineSmall)
                        Text("2.0.7 • renderer API ${RendererCapabilities.RENDERER_API}", style = MaterialTheme.typography.labelLarge)
                    }
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(active.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${active.engine} • ${active.precisionMode} • by ${active.author}")
                                Text("Schema ${active.formatVersion} • API ${active.rendererApi} • ${active.tracks.size} tracks")
                            }
                        }
                    }
                    item { Text(message) }
                    item {
                        Button(
                            onClick = { importRenderer.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Inspect .renderer") }
                    }

                    candidate?.let { pending ->
                        item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Import preflight", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(pending.spec.name, style = MaterialTheme.typography.titleLarge)
                                    Text("${pending.spec.id} • ${pending.spec.engine}")
                                    Text("API ${pending.spec.rendererApi} • ${pending.spec.referenceWidth}×${pending.spec.referenceHeight} @ ${pending.spec.referenceFps} fps")
                                    Text("Precision: ${pending.spec.precisionMode} • ${pending.spec.tracks.size} animation tracks")
                                    if (pending.spec.canonicalFrameCount > 0) Text("Canonical frames: ${pending.spec.canonicalFrameCount}")
                                    if (pending.spec.canonicalCardCount > 0) Text("Canonical cards: ${pending.spec.canonicalCardCount}")
                                    Text("SHA-256: ${pending.sha256}", style = MaterialTheme.typography.bodySmall)
                                    HorizontalDivider()
                                    Text(
                                        pending.report.summary(),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (pending.report.compatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    )
                                    pending.report.errors.forEach { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                                    pending.report.warnings.forEach { Text("Warning: $it", color = MaterialTheme.colorScheme.tertiary) }

                                    if (pending.report.compatible) {
                                        val frames = previewFrames(pending.spec)
                                        Text("Pre-activation preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Box(
                                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            previewBitmap?.let { bitmap ->
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = "Renderer pre-activation preview",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Fit,
                                                )
                                            } ?: Text("Preview unavailable", color = Color.White)
                                        }
                                        if (frames.isNotEmpty()) {
                                            val frame = frames[previewIndex.coerceIn(0, frames.lastIndex)]
                                            Text("Checkpoint ${previewIndex + 1}/${frames.size} • frame $frame", style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedButton(
                                                    enabled = previewIndex > 0,
                                                    onClick = {
                                                        previewIndex = (previewIndex - 1).coerceAtLeast(0)
                                                        renderCandidatePreview()
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                ) { Text("Previous") }
                                                OutlinedButton(
                                                    enabled = previewIndex < frames.lastIndex,
                                                    onClick = {
                                                        previewIndex = (previewIndex + 1).coerceAtMost(frames.lastIndex)
                                                        renderCandidatePreview()
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                ) { Text("Next") }
                                            }
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Renderer diagnostics", diagnostics(pending)))
                                            message = "Renderer diagnostics copied."
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Copy diagnostics") }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            enabled = pending.report.compatible && previewBitmap != null,
                                            onClick = {
                                                runCatching {
                                                    store.install(pending)
                                                    store.activate(pending.spec.id)
                                                }.onSuccess {
                                                    candidate = null
                                                    previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                                                    previewBitmap = null
                                                    message = "Installed and activated ${it.name}."
                                                    refresh()
                                                }.onFailure { message = it.message ?: "Renderer activation failed." }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Install & use") }
                                        OutlinedButton(
                                            onClick = {
                                                candidate = null
                                                previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                                                previewBitmap = null
                                                message = "Import cancelled. Active renderer was not changed."
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Cancel") }
                                    }
                                }
                            }
                        }
                    }

                    item { Text("Installed renderers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                    if (installed.isEmpty()) {
                        item { Text("No custom renderers installed yet.") }
                    } else {
                        items(installed, key = { it.spec.id }) { item ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.spec.name, fontWeight = FontWeight.SemiBold)
                                            Text("${item.spec.engine} • ${item.spec.precisionMode}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (item.active) Text("ACTIVE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                    val report = RendererCapabilities.report(item.spec)
                                    Text(report.summary(), style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            enabled = !item.active && report.compatible,
                                            onClick = {
                                                runCatching { store.activate(item.spec.id) }
                                                    .onSuccess { message = "Activated ${it.name}."; refresh() }
                                                    .onFailure { message = it.message ?: "Activation failed." }
                                            },
                                        ) { Text("Use") }
                                        OutlinedButton(
                                            enabled = !item.active,
                                            onClick = {
                                                runCatching { store.uninstall(item.spec.id) }
                                                    .onSuccess { message = "Deleted ${item.spec.name}."; refresh() }
                                                    .onFailure { message = it.message ?: "Delete failed." }
                                            },
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = {
                                runCatching { store.rollback() }
                                    .onSuccess { message = "Restored ${it.name}."; refresh() }
                                    .onFailure { message = it.message ?: "No previous renderer could be restored." }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Restore previous renderer") }
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                active = store.reset()
                                message = "Built-in 2.0.7 native renderer restored."
                                refresh()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Restore built-in renderer") }
                    }
                    item {
                        OutlinedButton(
                            onClick = { exportRenderer.launch("${active.id}.renderer") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export active renderer") }
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                startActivity(Intent(this@RendererManagerActivity, MainActivity::class.java))
                                finish()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open Cubical Compare") }
                    }
                    item { Spacer(Modifier.width(1.dp)) }
                }
            }
        }
    }

    override fun onDestroy() {
        previewBitmap?.takeIf { !it.isRecycled }?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFromIntent(intent)
    }

    private fun refresh() {
        active = store.active().also(RendererBridge::setRuntimeActive)
        installed = store.listInstalled()
    }

    private fun showCandidate(value: RendererCandidate, status: String) {
        candidate = value
        previewIndex = 0
        message = status
        renderCandidatePreview()
    }

    private fun renderCandidatePreview() {
        val pending = candidate ?: run { previewBitmap = null; return }
        if (!pending.report.compatible) {
            previewBitmap = null
            return
        }
        val frames = previewFrames(pending.spec)
        if (frames.isEmpty()) {
            previewBitmap = null
            return
        }
        previewIndex = previewIndex.coerceIn(0, frames.lastIndex)
        val project = previewProject(pending.spec)
        runCatching {
            RendererBridge.renderWithSpec(project, pending.spec, frames[previewIndex], 640, 360)
        }.onSuccess { next ->
            previewBitmap?.takeIf { it !== next && !it.isRecycled }?.recycle()
            previewBitmap = next
        }.onFailure {
            previewBitmap = null
            message = "Renderer preview failed: ${it.message ?: "unknown renderer error"}"
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
        val cards = List(count) { index ->
            StudioCard(
                title = "Preview ${index + 1}",
                value = "${max(1, (index + 1) * 10)} People",
                badgeHeader = "1 in",
                description = if (index % 3 == 0) "Renderer layout and animation preview" else "",
            )
        }
        return StudioProject(
            name = "Renderer preflight",
            cards = cards,
            width = spec.referenceWidth,
            height = spec.referenceHeight,
            fps = spec.referenceFps,
            creditsEnabled = true,
            showBadges = true,
        )
    }

    private fun diagnostics(value: RendererCandidate): String = buildString {
        appendLine("Cubical Compare renderer preflight")
        appendLine("Name: ${value.spec.name}")
        appendLine("ID: ${value.spec.id}")
        appendLine("Author: ${value.spec.author}")
        appendLine("SHA-256: ${value.sha256}")
        appendLine("Format: ${value.spec.formatVersion}")
        appendLine("Renderer API: ${value.spec.rendererApi}")
        appendLine("Engine: ${value.spec.engine}")
        appendLine("Precision: ${value.spec.precisionMode}")
        appendLine("Timeline unit: ${value.spec.timelineUnit}")
        appendLine("Reference: ${value.spec.referenceWidth}x${value.spec.referenceHeight} @ ${value.spec.referenceFps} fps")
        appendLine("Canonical cards: ${value.spec.canonicalCardCount}")
        appendLine("Canonical frames: ${value.spec.canonicalFrameCount}")
        appendLine("Tracks: ${value.spec.tracks.size}")
        appendLine("Required features: ${value.spec.requiredFeatures.joinToString()}")
        appendLine("Compatibility: ${value.report.summary()}")
        value.report.errors.forEach { appendLine("ERROR: $it") }
        value.report.warnings.forEach { appendLine("WARNING: $it") }
    }

    private fun importFromIntent(value: Intent?) {
        if (value?.action != Intent.ACTION_VIEW) return
        val uri = value.data ?: return
        runCatching {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected renderer could not be opened." }
                store.inspect(input)
            }
        }.onSuccess {
            showCandidate(it, "Renderer opened for preflight. Review it before activation.")
        }.onFailure { message = it.message ?: "Renderer preflight failed." }
    }
}