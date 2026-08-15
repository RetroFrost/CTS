package io.github.retrofrost.cts.android.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel

/** Focused card editor: model, data, selection and the active card—nothing else. */
@Composable
internal fun CardsWorkspace2(
    project: CtsProject,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onProjectChanged: (CtsProject) -> Unit,
    onUpdateSelectedCard: ((CtsCard) -> CtsCard) -> Unit,
    onChooseImage: () -> Unit,
    onImportCardStrip: () -> Unit,
    isImportingCardStrip: Boolean,
    onImportMegaPack: () -> Unit,
    isImportingMegaPack: Boolean,
    onInsertData: () -> Unit,
) {
    val selectedIndex = project.cards.indexOfFirst { it.id == selectedCardId }
    val selected = project.cards.getOrNull(selectedIndex)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Cards", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Build the comparison from data and artwork",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        "${project.cards.size}",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Reference model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Geometry and animation stay locked to the source video.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelChoice(
                            title = "Males by age",
                            selected = project.model == VisualModel.Males,
                            onClick = { onProjectChanged(project.copy(model = VisualModel.Males)) },
                            modifier = Modifier.weight(1f),
                        )
                        ModelChoice(
                            title = "Relationships",
                            selected = project.model == VisualModel.Relationships,
                            onClick = { onProjectChanged(project.copy(model = VisualModel.Relationships)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onInsertData,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.TableRows, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Import data")
                }
                FilledTonalButton(
                    onClick = {
                        val updated = project.addBlankCard()
                        onProjectChanged(updated)
                        updated.cards.lastOrNull()?.id?.let(onSelectCard)
                    },
                    modifier = Modifier.height(52.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Add")
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onImportCardStrip,
                    enabled = project.cards.isNotEmpty() && !isImportingCardStrip && !isImportingMegaPack,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (isImportingCardStrip) "Reading…" else "Image sheet")
                }
                OutlinedButton(
                    onClick = onImportMegaPack,
                    enabled = !isImportingMegaPack && !isImportingCardStrip,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.FolderZip, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (isImportingMegaPack) "Loading…" else "MegaPack")
                }
            }
        }

        if (project.cards.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(project.cards, key = { _, card -> card.id }) { index, card ->
                        FilterChip(
                            selected = card.id == selectedCardId,
                            onClick = { onSelectCard(card.id) },
                            modifier = Modifier.widthIn(max = 220.dp),
                            label = {
                                Text(
                                    "${index + 1} · ${card.title.ifBlank { "Untitled" }}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            if (selected == null) {
                EmptyCards(onAdd = {
                    val updated = project.addBlankCard()
                    onProjectChanged(updated)
                    updated.cards.lastOrNull()?.id?.let(onSelectCard)
                })
            } else {
                SelectedCardEditor(
                    card = selected,
                    index = selectedIndex,
                    total = project.cards.size,
                    onUpdate = onUpdateSelectedCard,
                    onChooseImage = onChooseImage,
                    onDuplicate = {
                        val updated = project.duplicateCard(selected.id)
                        onProjectChanged(updated)
                        updated.cards.getOrNull(selectedIndex + 1)?.id?.let(onSelectCard)
                    },
                    onDelete = {
                        val updated = project.removeCard(selected.id)
                        onProjectChanged(updated)
                        updated.cards.getOrNull(selectedIndex.coerceAtMost(updated.cards.lastIndex))?.id?.let(onSelectCard)
                    },
                )
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ModelChoice(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = container),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(title, color = content, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
        }
    }
}

@Composable
private fun EmptyCards(onAdd: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("No cards yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Import a table or add the first card manually.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Add first card")
            }
        }
    }
}

@Composable
private fun SelectedCardEditor(
    card: CtsCard,
    index: Int,
    total: Int,
    onUpdate: ((CtsCard) -> CtsCard) -> Unit,
    onChooseImage: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Card ${index + 1} of $total", style = MaterialTheme.typography.titleMedium)
                    Text(
                        card.title.ifBlank { "Untitled card" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row {
                    IconButton(onClick = onDuplicate) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate card")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete card")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = card.title,
                onValueChange = { value -> onUpdate { it.copy(title = value) } },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = card.badgePrimary,
                    onValueChange = { value -> onUpdate { it.copy(badgePrimary = value) } },
                    label = { Text("Badge value") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = card.badgeSecondary,
                    onValueChange = { value -> onUpdate { it.copy(badgeSecondary = value) } },
                    label = { Text("Badge label") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = card.description,
                onValueChange = { value -> onUpdate { it.copy(description = value) } },
                label = { Text("Description · optional") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (card.imageSubcard.source.isNullOrBlank()) "Choose artwork" else "Replace artwork")
            }

            if (!card.imageSubcard.source.isNullOrBlank()) {
                OutlinedButton(
                    onClick = {
                        onUpdate { current ->
                            current.copy(imageSubcard = current.imageSubcard.copy(transform = NormalizedRect.Full))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Reset artwork crop")
                }
            }
        }
    }
}
