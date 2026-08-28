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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class RendererManagerActivity : ComponentActivity() {
    private val store by lazy { RendererStore(this) }
    private var active by mutableStateOf(RendererRuntime.active)
    private var message by mutableStateOf("Renderer files are declarative and sandboxed. No executable code is loaded.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importFromIntent(intent)
        setContent {
            val importRenderer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "The selected renderer could not be opened." }
                            store.import(input)
                        }
                    }.onSuccess {
                        active = it
                        message = "Activated ${it.name}. Preview and export now use this renderer."
                    }.onFailure { message = it.message ?: "Renderer import failed." }
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
                        Text("2.0.7 native fork", style = MaterialTheme.typography.labelLarge)
                    }
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(active.name, style = MaterialTheme.typography.titleMedium)
                                Text("${active.id} • by ${active.author}")
                                Text("Schema ${active.formatVersion} • ${active.tracks.size} animation tracks")
                            }
                        }
                    }
                    item { Text(message) }
                    item {
                        Button(
                            onClick = { importRenderer.launch(arrayOf("application/octet-stream", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Import .renderer") }
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
                                active = store.reset()
                                message = "Built-in 2.0.7 native renderer restored."
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Restore built-in renderer") }
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
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importFromIntent(intent)
    }

    private fun importFromIntent(value: Intent?) {
        if (value?.action != Intent.ACTION_VIEW) return
        val uri = value.data ?: return
        runCatching {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected renderer could not be opened." }
                store.import(input)
            }
        }.onSuccess {
            active = it
            message = "Activated ${it.name}."
        }.onFailure { message = it.message ?: "Renderer import failed." }
    }
}
