package io.github.retrofrost.cts.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsProject

internal data class PendingArtwork(
    val source: String,
    val displayName: String,
)

/**
 * Replace image sources without touching the image-subcard transform.
 *
 * This is deliberately pure so the batch ordering and transform-preservation behavior
 * can be covered by local unit tests without an Android device.
 */
internal fun assignArtworkSources(
    project: CtsProject,
    sources: List<String>,
    startIndex: Int,
): CtsProject {
    if (project.cards.isEmpty() || sources.isEmpty()) return project
    val safeStart = startIndex.coerceIn(0, project.cards.lastIndex)
    val cards = project.cards.mapIndexed { index, card ->
        val sourceIndex = index - safeStart
        val source = sources.getOrNull(sourceIndex)
        if (source == null) {
            card
        } else {
            card.copy(
                imageSubcard = card.imageSubcard.copy(source = source),
            )
        }
    }
    return project.copy(cards = cards).normalized()
}

@Composable
fun BatchArtworkCard(
    project: CtsProject,
    selectedCardId: String?,
    onProjectChanged: (CtsProject) -> Unit,
    onSelectCard: (String) -> Unit,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<List<PendingArtwork>>(emptyList()) }
    var pendingStartIndex by remember { mutableStateOf(0) }

    fun selectedStartIndex(): Int = project.cards
        .indexOfFirst { it.id == selectedCardId }
        .takeIf { it >= 0 }
        ?: 0

    fun stageArtwork(items: List<PendingArtwork>) {
        if (project.cards.isEmpty()) {
            Toast.makeText(context, "Add a card before adding artwork", Toast.LENGTH_SHORT).show()
            return
        }
        if (items.isEmpty()) {
            Toast.makeText(
                context,
                "The clipboard does not contain artwork or an image link",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        pendingStartIndex = selectedStartIndex()
        val remaining = project.cards.size - pendingStartIndex
        pending = items.take(remaining)
    }

    val multiplePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        val items = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            PendingArtwork(
                source = uri.toString(),
                displayName = artworkDisplayName(context, uri),
            )
        }
        stageArtwork(items)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Artwork",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "Paste copied images or choose several gallery files. They fill cards in order from the selected card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        stageArtwork(readArtworkClipboard(context))
                    },
                    enabled = project.cards.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.size(7.dp))
                    Text("Paste artwork")
                }
                Button(
                    onClick = { multiplePicker.launch(arrayOf("image/*")) },
                    enabled = project.cards.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.size(7.dp))
                    Text("Choose multiple")
                }
            }
        }
    }

    if (pending.isNotEmpty()) {
        val assignedCount = pending.size
        AlertDialog(
            onDismissRequest = { pending = emptyList() },
            icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
            title = { Text("Assign $assignedCount artwork${if (assignedCount == 1) "" else "s"}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Images will be assigned in this order. Existing resize and position settings stay unchanged.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(pending) { index, artwork ->
                            val cardIndex = pendingStartIndex + index
                            val cardTitle = project.cards.getOrNull(cardIndex)?.title
                                ?.ifBlank { "Untitled card" }
                                ?: "Card ${cardIndex + 1}"
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "${cardIndex + 1}. $cardTitle",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = artwork.displayName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = assignArtworkSources(
                            project = project,
                            sources = pending.map { it.source },
                            startIndex = pendingStartIndex,
                        )
                        onProjectChanged(updated)
                        updated.cards.getOrNull(pendingStartIndex)?.id?.let(onSelectCard)
                        Toast.makeText(
                            context,
                            "$assignedCount artwork${if (assignedCount == 1) "" else "s"} assigned",
                            Toast.LENGTH_SHORT,
                        ).show()
                        pending = emptyList()
                    },
                ) {
                    Text("Assign")
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = emptyList() }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun readArtworkClipboard(context: Context): List<PendingArtwork> {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip: ClipData = clipboard.primaryClip ?: return emptyList()
    return buildList {
        for (index in 0 until clip.itemCount) {
            val item = clip.getItemAt(index)
            val uri = item.uri
            if (uri != null) {
                add(
                    PendingArtwork(
                        source = uri.toString(),
                        displayName = artworkDisplayName(context, uri),
                    ),
                )
                continue
            }
            val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
            if (
                text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) ||
                text.startsWith("content://", ignoreCase = true) ||
                text.startsWith("file://", ignoreCase = true)
            ) {
                add(PendingArtwork(source = text, displayName = text.substringAfterLast('/')))
            }
        }
    }
}

private fun artworkDisplayName(context: Context, uri: Uri): String {
    val displayName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    return displayName?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment
        ?: "Artwork"
}
