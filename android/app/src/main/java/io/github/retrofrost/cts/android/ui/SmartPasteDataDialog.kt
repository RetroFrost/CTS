package io.github.retrofrost.cts.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal data class SmartClipboardPayload(
    val tableText: String,
    val artworkSources: List<String>,
)

@Composable
fun SmartPasteDataDialog(
    existingCards: List<CtsCard>,
    selectedCardId: String?,
    onDismiss: () -> Unit,
    onApply: (List<CtsCard>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(cardsAsTable(existingCards)) }
    var pendingArtwork by remember { mutableStateOf<List<String>>(emptyList()) }
    var artworkStartIndex by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.TableRows, contentDescription = null) },
        title = { Text("Paste comparison data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste rows, Google Images, raw copied images, HTML images, or direct image links.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            error = null
                            status = "Reading clipboard…"
                            runCatching { readSmartClipboard(context) }
                                .onSuccess { payload ->
                                    if (payload.tableText.isNotBlank()) {
                                        text = payload.tableText
                                        artworkStartIndex = 0
                                    } else {
                                        artworkStartIndex = existingCards
                                            .indexOfFirst { it.id == selectedCardId }
                                            .takeIf { it >= 0 }
                                            ?: 0
                                    }
                                    pendingArtwork = payload.artworkSources
                                    status = buildString {
                                        if (payload.tableText.isNotBlank()) append("Table imported")
                                        if (payload.artworkSources.isNotEmpty()) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${payload.artworkSources.size} artwork")
                                        }
                                        if (isEmpty()) append("Clipboard has no table or image")
                                    }
                                }
                                .onFailure { throwable ->
                                    error = throwable.message ?: "Could not read clipboard"
                                    status = null
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Paste clipboard text + artwork")
                }
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp),
                    label = { Text("Value · Label · Title · Description · Image") },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                if (pendingArtwork.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "${pendingArtwork.size} copied image${if (pendingArtwork.size == 1) "" else "s"} will fill cards from ${artworkStartIndex + 1}.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching {
                        val parsed = parseSmartCards(text, existingCards)
                        applyArtworkToCards(parsed, pendingArtwork, artworkStartIndex)
                    }.onSuccess(onApply)
                        .onFailure { error = it.message ?: "Could not parse this table" }
                },
            ) {
                Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

internal fun cardsAsTable(cards: List<CtsCard>): String =
    "Value\tLabel\tTitle\tDescription\tImage\n" + cards.joinToString("\n") { card ->
        listOf(
            card.badgePrimary,
            card.badgeSecondary,
            card.title,
            card.description,
            card.imageSubcard.source.orEmpty(),
        ).joinToString("\t")
    }

internal fun applyArtworkToCards(
    cards: List<CtsCard>,
    sources: List<String>,
    startIndex: Int,
): List<CtsCard> {
    if (cards.isEmpty() || sources.isEmpty()) return cards
    val safeStart = startIndex.coerceIn(0, cards.lastIndex)
    return cards.mapIndexed { index, card ->
        val source = sources.getOrNull(index - safeStart)
        if (source == null) card else card.copy(
            imageSubcard = card.imageSubcard.copy(source = source),
        )
    }
}

internal fun parseSmartCards(
    text: String,
    existingCards: List<CtsCard>,
): List<CtsCard> {
    val lines = text.lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()
    require(lines.isNotEmpty()) { "Paste at least one row." }

    val delimiter = when {
        lines.first().contains('\t') -> '\t'
        lines.first().contains('|') -> '|'
        lines.first().contains(';') -> ';'
        else -> ','
    }
    val matrix = lines.map { parseSmartDelimitedLine(it, delimiter) }
    val first = matrix.first().map { it.trim().lowercase() }
    val knownHeaders = setOf(
        "badge", "value", "date", "year", "title", "name", "description",
        "details", "image", "artwork", "picture", "photo", "label", "unit",
    )
    val hasHeader = first.any { it in knownHeaders }
    val headers = if (hasHeader) first else listOf("value", "label", "title", "description", "image")
    val rows = if (hasHeader) matrix.drop(1) else matrix

    fun column(vararg names: String): Int = headers.indexOfFirst { it in names }
    val badgeColumn = column("badge", "value", "date", "year")
    val labelColumn = column("label", "unit")
    val titleColumn = column("title", "name")
    val descriptionColumn = column("description", "details")
    val imageColumn = column("image", "artwork", "picture", "photo")
    fun cell(row: List<String>, index: Int): String = if (index in row.indices) row[index].trim() else ""

    val cards = rows.mapIndexedNotNull { rowIndex, row ->
        if (row.all(String::isBlank)) return@mapIndexedNotNull null
        val previous = existingCards.getOrNull(rowIndex)
        val cardId = previous?.id ?: UUID.randomUUID().toString()
        val image = normalizeArtworkSource(cell(row, imageColumn))
        CtsCard(
            id = cardId,
            badgePrimary = cell(row, badgeColumn),
            badgeSecondary = cell(row, labelColumn),
            title = cell(row, titleColumn),
            description = cell(row, descriptionColumn),
            imageSubcard = ImageSubcard(
                id = previous?.imageSubcard?.id ?: UUID.randomUUID().toString(),
                parentCardId = cardId,
                source = image.ifBlank { previous?.imageSubcard?.source },
                transform = previous?.imageSubcard?.transform ?: NormalizedRect.Full,
            ),
        )
    }
    require(cards.isNotEmpty()) { "The table contains no cards." }
    return cards
}

private fun parseSmartDelimitedLine(line: String, delimiter: Char): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        when {
            character == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            character == '"' -> quoted = !quoted
            character == delimiter && !quoted -> {
                cells += current.toString()
                current.clear()
            }
            else -> current.append(character)
        }
        index++
    }
    cells += current.toString()
    return cells
}

internal fun normalizeArtworkSource(raw: String): String {
    val cleaned = raw.trim().trim('"', '\'').replace("&amp;", "&")
    if (cleaned.isBlank()) return ""
    return runCatching {
        val uri = URI(cleaned)
        val host = uri.host.orEmpty().lowercase()
        if ("google." in host || host.endsWith("googleusercontent.com")) {
            val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) null else pieces[0] to URLDecoder.decode(
                    pieces[1],
                    StandardCharsets.UTF_8.name(),
                )
            }.toMap()
            listOf("imgurl", "mediaurl", "image_url", "url")
                .firstNotNullOfOrNull { key -> parameters[key]?.takeIf { it.startsWith("http") } }
                ?: cleaned
        } else {
            cleaned
        }
    }.getOrDefault(cleaned)
}

private suspend fun readSmartClipboard(context: Context): SmartClipboardPayload = withContext(Dispatchers.IO) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return@withContext SmartClipboardPayload("", emptyList())
    val textParts = mutableListOf<String>()
    val artwork = mutableListOf<String>()

    for (index in 0 until clip.itemCount) {
        val item = clip.getItemAt(index)
        val uriCandidates = listOfNotNull(item.uri, item.intent?.data)
        for (uri in uriCandidates) {
            persistUriArtwork(context, uri)?.let(artwork::add)
        }

        val html = item.htmlText.orEmpty()
        if (html.isNotBlank()) {
            for (candidate in extractImageCandidates(html)) {
                persistArtworkCandidate(context, candidate)?.let(artwork::add)
            }
        }

        val text = item.text?.toString()?.trim().orEmpty()
            .ifBlank { item.coerceToText(context)?.toString()?.trim().orEmpty() }
        if (text.isNotBlank()) textParts += text
    }

    var tableText = textParts.joinToString("\n").trim()
    if (tableText.isNotBlank()) {
        val imageCandidates = extractImageCandidates(tableText)
        val lines = tableText.lines().map(String::trim).filter(String::isNotBlank)
        val onlyImages = lines.isNotEmpty() && lines.all { line ->
            line.startsWith("data:image/", ignoreCase = true) ||
                line.startsWith("content://", ignoreCase = true) ||
                looksLikeImageUrl(line)
        }
        if (onlyImages) {
            for (candidate in lines) {
                persistArtworkCandidate(context, candidate)?.let(artwork::add)
            }
            tableText = ""
        } else if ('<' in tableText && imageCandidates.isNotEmpty()) {
            for (candidate in imageCandidates) {
                persistArtworkCandidate(context, candidate)?.let(artwork::add)
            }
        }
    }

    SmartClipboardPayload(
        tableText = tableText,
        artworkSources = artwork.distinct(),
    )
}

private fun extractImageCandidates(value: String): List<String> {
    val candidates = mutableListOf<String>()
    val attribute = Regex(
        """(?:src|data-src|data-iurl)\\s*=\\s*[\"']([^\"']+)[\"']""",
        RegexOption.IGNORE_CASE,
    )
    attribute.findAll(value).forEach { candidates += it.groupValues[1] }
    Regex("""data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=\\r\\n]+""")
        .findAll(value)
        .forEach { candidates += it.value }
    if (candidates.isEmpty()) {
        Regex("""https?://[^\\s<>\"']+""", RegexOption.IGNORE_CASE)
            .findAll(value)
            .map { it.value.trimEnd(')', ']', ',', ';') }
            .filter(::looksLikeImageUrl)
            .forEach(candidates::add)
    }
    return candidates
}

private fun looksLikeImageUrl(value: String): Boolean {
    val normalized = normalizeArtworkSource(value).lowercase()
    return normalized.startsWith("http") && (
        Regex("""\\.(png|jpe?g|webp|gif|bmp|avif)(?:[?#].*)?$""").containsMatchIn(normalized) ||
            "imgurl=" in normalized || "mediaurl=" in normalized ||
            "googleusercontent.com" in normalized || "gstatic.com" in normalized
        )
}

private suspend fun persistArtworkCandidate(context: Context, raw: String): String? {
    val source = normalizeArtworkSource(raw)
    return when {
        source.startsWith("data:image/", ignoreCase = true) -> persistDataArtwork(context, source)
        source.startsWith("content://", ignoreCase = true) -> persistUriArtwork(context, Uri.parse(source))
        source.startsWith("file://", ignoreCase = true) -> Uri.parse(source).path
        source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true) -> source
        else -> null
    }
}

private fun persistDataArtwork(context: Context, source: String): String? = runCatching {
    val comma = source.indexOf(',')
    require(comma > 0) { "Invalid copied image data" }
    val mime = source.substringAfter("data:").substringBefore(';')
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
    val bytes = Base64.decode(source.substring(comma + 1).replace("\n", ""), Base64.DEFAULT)
    val folder = File(context.filesDir, "cts-artwork").apply { mkdirs() }
    File(folder, "clipboard-${UUID.randomUUID()}.$extension").apply { writeBytes(bytes) }.absolutePath
}.getOrNull()

private fun persistUriArtwork(context: Context, uri: Uri): String? = runCatching {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val mime = context.contentResolver.getType(uri)
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
    val folder = File(context.filesDir, "cts-artwork").apply { mkdirs() }
    val output = File(folder, "clipboard-${UUID.randomUUID()}.$extension")
    context.contentResolver.openInputStream(uri)?.use { input ->
        output.outputStream().use(input::copyTo)
    } ?: return@runCatching uri.toString()
    output.absolutePath
}.getOrElse { uri.toString() }
