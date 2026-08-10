package io.github.retrofrost.cts.android.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.importer.ReconstructedCard
import io.github.retrofrost.cts.android.importer.VideoReconstructionProgress
import io.github.retrofrost.cts.android.importer.VideoReconstructionResult
import io.github.retrofrost.cts.android.model.VisualModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoReconstructionReviewScreen(
    result: VideoReconstructionResult,
    isApplying: Boolean,
    onCancel: () -> Unit,
    onImport: (VisualModel, List<ReconstructedCard>) -> Unit,
) {
    var model by remember(result) { mutableStateOf(result.detectedModel) }
    var cards by remember(result) { mutableStateOf(result.cards) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reconstruct comparison", fontWeight = FontWeight.Black)
                        Text(
                            result.sourceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !isApplying) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Cancel reconstruction")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(onClick = onCancel, enabled = !isApplying, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { onImport(model, cards) },
                        enabled = cards.isNotEmpty() && !isApplying,
                        modifier = Modifier.weight(2f),
                    ) {
                        AnimatedContent(
                            targetState = isApplying,
                            transitionSpec = {
                                (fadeIn() + slideInVertically { it / 2 }) togetherWith
                                    (fadeOut() + slideOutVertically { -it / 2 })
                            },
                            label = "import-button",
                        ) { applying ->
                            if (applying) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Importing…")
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Use ${cards.size} cards", fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .animateContentSize(
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text("${cards.size} editable cards found", fontWeight = FontWeight.Black)
                                AnimatedContent(
                                    targetState = model,
                                    transitionSpec = {
                                        (fadeIn() + slideInVertically { it / 3 }) togetherWith
                                            (fadeOut() + slideOutVertically { -it / 3 })
                                    },
                                    label = "detected-model",
                                ) { selectedModel ->
                                    Text(
                                        "Artwork and text mapped for ${selectedModel.label}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        Text("Video style", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VisualModel.entries.forEach { option ->
                                FilterChip(
                                    selected = model == option,
                                    onClick = { model = option },
                                    label = {
                                        Text(
                                            if (option == VisualModel.Males) "Males" else "Relationships",
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            if (result.warnings.isNotEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Before importing", fontWeight = FontWeight.Black)
                            result.warnings.forEach { warning ->
                                Text("• $warning", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            itemsIndexed(cards, key = { _, card -> card.id }) { index, card ->
                ReconstructedCardEditor(
                    index = index,
                    card = card,
                    onChanged = { updated ->
                        cards = cards.toMutableList().apply { this[index] = updated }
                    },
                    onDelete = {
                        cards = cards.toMutableList().apply { removeAt(index) }
                    },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ReconstructedCardEditor(
    index: Int,
    card: ReconstructedCard,
    onChanged: (ReconstructedCard) -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReconstructedArtwork(
                    path = card.artworkPath,
                    modifier = Modifier
                        .size(width = 78.dp, height = 128.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Card ${index + 1}", style = MaterialTheme.typography.labelLarge)
                    Text(
                        card.title.ifBlank { "Title needs review" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${(card.confidence * 100).roundToInt()}% recognition confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (card.confidence >= 0.58f) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove card ${index + 1}")
                }
            }
            AnimatedVisibility(
                visible = card.warnings.isNotEmpty(),
                enter = fadeIn() + slideInVertically { -it / 3 },
                exit = fadeOut() + slideOutVertically { -it / 3 },
            ) {
                Text(
                    card.warnings.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = card.badgePrimary,
                    onValueChange = { onChanged(card.copy(badgePrimary = it)) },
                    label = { Text("Badge value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = card.badgeSecondary,
                    onValueChange = { onChanged(card.copy(badgeSecondary = it)) },
                    label = { Text("Badge label") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = card.title,
                onValueChange = { onChanged(card.copy(title = it)) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = card.description,
                onValueChange = { onChanged(card.copy(description = it)) },
                label = { Text("Description") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReconstructedArtwork(path: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    DisposableEffect(bitmap) {
        onDispose { bitmap?.let { if (!it.isRecycled) it.recycle() } }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = null,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "Recovered artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
internal fun VideoReconstructionProgressDialog(
    progress: VideoReconstructionProgress,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
        title = { Text("Reconstructing comparison") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedContent(
                    targetState = progress.phase,
                    transitionSpec = {
                        (fadeIn() + slideInVertically { it / 2 }) togetherWith
                            (fadeOut() + slideOutVertically { -it / 2 })
                    },
                    label = "reconstruction-phase",
                ) { phase ->
                    Text(phase.label, fontWeight = FontWeight.Black)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        progress.detail.ifBlank { "Preparing the next reconstruction step" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("${progress.percent}%", fontWeight = FontWeight.Black)
                }
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (progress.total > 1) {
                    Text(
                        "${progress.completed.coerceIn(0, progress.total)} / ${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "This runs as a foreground background job, so the screen can be off and you can leave CTS while it continues.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text("Cancel reconstruction") }
        },
        shape = RoundedCornerShape(28.dp),
    )
}
