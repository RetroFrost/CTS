package io.github.retrofrost.cts.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

private data class OfficialRendererEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val url: String,
    val sha256: String,
    val minAppVersion: String,
)

class RendererRepositoryActivity : ComponentActivity() {
    private val store by lazy { RendererStore(this) }
    private var entries by mutableStateOf<List<OfficialRendererEntry>>(emptyList())
    private var loading by mutableStateOf(true)
    private var downloadingId by mutableStateOf<String?>(null)
    private var installedShaById by mutableStateOf<Map<String, String>>(emptyMap())
    private var activeSha256 by mutableStateOf("")
    private var message by mutableStateOf(
        "Published by RetroFrost. Downloads come only from the official Cubical Compare renderer repository.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshLocal()
        setContent {
            MaterialTheme {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Text("Official renderer repository", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Cubical Compare ${BuildConfig.VERSION_NAME} • curated by RetroFrost",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    item { Text(message) }
                    item {
                        OutlinedButton(
                            enabled = !loading && downloadingId == null,
                            onClick = { loadCatalog() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (loading) "Loading…" else "Refresh repository") }
                    }

                    if (entries.isEmpty() && !loading) {
                        item { Text("No official renderers are available right now.") }
                    } else {
                        items(entries, key = { it.id }) { entry ->
                            val exactActive = entry.sha256.equals(activeSha256, ignoreCase = true)
                            val exactInstalled = entry.sha256.equals(installedShaById[entry.id], ignoreCase = true)
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.name, fontWeight = FontWeight.SemiBold)
                                            Text("by ${entry.author}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (exactActive) {
                                            Text(
                                                "ACTIVE",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                    if (entry.description.isNotBlank()) Text(entry.description)
                                    Text(
                                        "Requires ${entry.minAppVersion}+ • official SHA-256 ${entry.sha256.take(12)}…",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        enabled = downloadingId == null && !exactActive,
                                        onClick = {
                                            if (exactInstalled) activateInstalled(entry)
                                            else downloadAndUse(entry)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            when {
                                                exactActive -> "Active"
                                                downloadingId == entry.id -> "Downloading…"
                                                exactInstalled -> "Use"
                                                else -> "Download & use"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Back to renderer library") }
                    }
                }
            }
        }
        loadCatalog()
    }

    private fun refreshLocal() {
        store.active().also(RendererBridge::setRuntimeActive)
        activeSha256 = store.activeSha256().orEmpty()
        installedShaById = store.listInstalled().associate { item ->
            item.spec.id to store.installedSha256(item.spec.id).orEmpty()
        }
    }

    private fun loadCatalog() {
        loading = true
        message = "Loading the official renderer repository…"
        Thread {
            runCatching {
                parseCatalog(String(downloadLimited(CATALOG_URL, MAX_CATALOG_BYTES), Charsets.UTF_8))
            }.onSuccess { loaded ->
                runOnUiThread {
                    entries = loaded
                    loading = false
                    message = if (loaded.isEmpty()) {
                        "The official renderer repository is currently empty."
                    } else {
                        "${loaded.size} official renderer${if (loaded.size == 1) "" else "s"} available. Only RetroFrost can publish to this feed."
                    }
                }
            }.onFailure { error ->
                runOnUiThread {
                    loading = false
                    message = "Could not load renderer repository: ${error.message ?: "network error"}"
                }
            }
        }.start()
    }

    private fun activateInstalled(entry: OfficialRendererEntry) {
        runCatching { store.activate(entry.id) }
            .onSuccess {
                refreshLocal()
                message = "Activated ${it.name}."
            }
            .onFailure { message = it.message ?: "Renderer activation failed." }
    }

    private fun downloadAndUse(entry: OfficialRendererEntry) {
        downloadingId = entry.id
        message = "Downloading ${entry.name}…"
        Thread {
            runCatching {
                requireOfficialRendererUrl(entry.url)
                require(entry.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Catalog checksum is invalid." }
                val bytes = downloadLimited(entry.url, MAX_RENDERER_BYTES)
                val actual = sha256(bytes)
                require(actual.equals(entry.sha256, ignoreCase = true)) {
                    "Renderer checksum mismatch. The download was not installed."
                }
                val candidate = store.inspect(ByteArrayInputStream(bytes))
                require(candidate.spec.id == entry.id) {
                    "Renderer identity does not match the official catalog."
                }
                require(candidate.report.compatible) {
                    candidate.report.errors.joinToString("\n").ifBlank { "Renderer is not compatible with this app." }
                }
                store.install(candidate)
                store.activate(candidate.spec.id)
            }.onSuccess { spec ->
                runOnUiThread {
                    downloadingId = null
                    refreshLocal()
                    message = "Downloaded, verified, installed and activated ${spec.name}."
                }
            }.onFailure { error ->
                runOnUiThread {
                    downloadingId = null
                    message = error.message ?: "Renderer download failed."
                }
            }
        }.start()
    }

    private fun parseCatalog(text: String): List<OfficialRendererEntry> {
        val root = JSONObject(text)
        require(root.optInt("schema") == 1) { "Unsupported renderer repository schema." }
        require(root.optString("publisher") == OFFICIAL_PUBLISHER) { "Renderer repository publisher is not trusted." }
        require(root.optString("repository") == OFFICIAL_REPOSITORY) { "Renderer repository identity is not trusted." }
        val array = root.getJSONArray("renderers")
        val result = ArrayList<OfficialRendererEntry>(array.length())
        val ids = HashSet<String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val entry = OfficialRendererEntry(
                id = item.getString("id").trim(),
                name = item.getString("name").trim(),
                description = item.optString("description").trim(),
                author = item.optString("author", OFFICIAL_PUBLISHER).trim(),
                url = item.getString("url").trim(),
                sha256 = item.getString("sha256").trim().lowercase(),
                minAppVersion = item.optString("minAppVersion", "2.0.8").trim(),
            )
            require(entry.id.isNotBlank() && entry.name.isNotBlank()) { "Renderer repository contains an incomplete entry." }
            require(ids.add(entry.id)) { "Renderer repository contains duplicate IDs." }
            requireOfficialRendererUrl(entry.url)
            require(entry.sha256.matches(Regex("[0-9a-f]{64}"))) { "Renderer repository contains an invalid checksum." }
            result += entry
        }
        return result
    }

    private fun requireOfficialRendererUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Renderer URL must use HTTPS." }
        require(uri.host.equals("raw.githubusercontent.com", ignoreCase = true)) { "Renderer host is not trusted." }
        require(value.startsWith(OFFICIAL_RENDERER_PREFIX)) { "Renderer URL is outside the official repository." }
    }

    private fun downloadLimited(value: String, limit: Int): ByteArray {
        val connection = URL(value).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "Cubical-Compare/${BuildConfig.VERSION_NAME}")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Server returned HTTP $code." }
            val announced = connection.contentLengthLong
            require(announced < 0 || announced <= limit) { "Download is larger than the allowed size." }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= limit) { "Download is larger than the allowed size." }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val OFFICIAL_PUBLISHER = "RetroFrost"
        private const val OFFICIAL_REPOSITORY = "RetroFrost/CTS"
        private const val CATALOG_URL =
            "https://raw.githubusercontent.com/RetroFrost/CTS/renderer-repository/index.json"
        private const val OFFICIAL_RENDERER_PREFIX =
            "https://raw.githubusercontent.com/RetroFrost/CTS/renderer-repository/renderers/"
        private const val MAX_CATALOG_BYTES = 256 * 1024
        private const val MAX_RENDERER_BYTES = 8 * 1024 * 1024
    }
}
