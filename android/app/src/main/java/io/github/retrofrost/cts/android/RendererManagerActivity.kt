package io.github.retrofrost.cts.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class RendererManagerActivity : ComponentActivity() {
    private val store by lazy { RendererStore(this) }
    private var active by mutableStateOf(RendererRuntime.active)
    private var candidate by mutableStateOf<RendererCandidate?>(null)
    private var installed by mutableStateOf<List<InstalledRenderer>>(emptyList())
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
                        candidate = it
                        message = "Preflight finished. Nothing has been activated yet."
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
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            enabled = pending.report.compatible,
                                            onClick = {
                                                runCatching {
                                                    store.install(pending)
                                                    store.activate(pending.spec.id)
                                                }.onSuccess {
                                                    candidate = null
                                                    message = "Installed and activated ${it.name}."
                                                    refresh()
                                                }.onFailure { message = it.message ?: "Renderer activation failed." }
                                            },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Install & use") }
                                        OutlinedButton(
                                            onClick = { candidate = null; message = "Import cancelled. Active renderer was not changed." },
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFromIntent(intent)
    }

    private fun refresh() {
        active = store.active().also { RendererRuntime.active = it }
        installed = store.listInstalled()
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
            candidate = it
            message = "Renderer opened for preflight. Review it before activation."
        }.onFailure { message = it.message ?: "Renderer preflight failed." }
    }
}