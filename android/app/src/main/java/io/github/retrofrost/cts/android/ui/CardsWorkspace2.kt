package io.github.retrofrost.cts.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ModelMode
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel

/**
 * CTS 2.0 card workspace.
 *
 * The reference renderers remain completely separate from this UI. This screen only edits
 * the project model and deliberately exposes only the real reference models shipped by CTS.
 */
@Composable
internal fun CardsWorkspace2(
    project: CtsProject,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onProjectChanged: (CtsProject) -> Unit,
    onUpdateSelectedCard: ((CtsCard) -> CtsCard) -> Unit,
    onChooseImage: () -> Unit,
    onChooseBackground: () -> Unit,
    onImportCardStrip: () -> Unit,
    isImportingCardStrip: Boolean,
    onImportMegaPack: () -> Unit,
    isImportingMegaPack: Boolean,
    onInsertData: () -> Unit,
) {
    val selected = project.cards.firstOrNull { it.id == selectedCardId }
    var showCardDetails by remember { mutableStateOf(false) }
    var showImports by remember { mutableStateOf(false) }
    var showReferenceSettings by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Cards",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    text = "${project.cards.size} cards · ${project.model.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Reference model", fontWeight = FontWeight.Bold)
                    Text(
                        "Choose one of the original CTS reference layouts. Geometry, timing and rendering stay untouched.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VisualModel.entries.forEach { model ->
                            FilterChip(
                                selected = project.model == model,
                                onClick = { onProjectChanged(project.copy(model = model)) },
                                label = {
                                    Text(
                                        text = model.label,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = onInsertData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Filled.TableRows, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Insert or edit all cards", fontWeight = FontWeight.Black)
            }
        }

        if (project.cards.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(project.cards, key = { it.id }) { card ->
                        FilterChip(
                            selected = card.id == selectedCardId,
                            onClick = { onSelectCard(card.id) },
                            label = {
                                Text(
                                    text = card.title.ifBlank { "Untitled" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (selected == null) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("No card selected", fontWeight = FontWeight.Bold)
                        Text(
                            "Add cards or paste your comparison data to begin.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(
                            onClick = {
                                val updated = project.addBlankCard()
                                onProjectChanged(updated)
                                updated.cards.lastOrNull()?.id?.let(onSelectCard)
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Add card")
                        }
                    }
                }
            }
        } else {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Edit selected card", fontWeight = FontWeight.Black)

                        OutlinedTextField(
                            value = selected.title,
                            onValueChange = { value -> onUpdateSelectedCard { it.copy(title = value) } },
                            label = { Text("Title") },
                            colors = titleFieldColors2(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    if (!state.isFocused && selected.title != selected.title.trim()) {
                                        onUpdateSelectedCard { it.copy(title = it.title.trim()) }
                                    }
                                },
                        )

                        OutlinedTextField(
                            value = selected.description,
                            onValueChange = { value -> onUpdateSelectedCard { it.copy(description = value) } },
                            label = { Text("Description") },
                            minLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    if (!state.isFocused && selected.description != selected.description.trim()) {
                                        onUpdateSelectedCard { it.copy(description = it.description.trim()) }
                                    }
                                },
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onChooseImage, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Image, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (selected.imageSubcard.source.isNullOrBlank()) "Add artwork" else "Artwork")
                            }
                            OutlinedButton(onClick = onChooseBackground, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Image, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text(if (selected.imageSubcard.backgroundSource.isNullOrBlank()) "Background" else "Replace bg")
                            }
                        }

                        OutlinedButton(
                            onClick = { showCardDetails = !showCardDetails },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (showCardDetails) "Hide card details" else "Card details")
                        }

                        AnimatedVisibility(showCardDetails) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = selected.badgePrimary,
                                    onValueChange = { value -> onUpdateSelectedCard { it.copy(badgePrimary = value) } },
                                    label = { Text("Badge value") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = selected.badgeSecondary,
                                    onValueChange = { value -> onUpdateSelectedCard { it.copy(badgeSecondary = value) } },
                                    label = { Text("Badge label") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedButton(
                                    onClick = {
                                        onUpdateSelectedCard { card ->
                                            card.copy(imageSubcard = card.imageSubcard.copy(transform = NormalizedRect.Full))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                                    Spacer(Modifier.size(6.dp))
                                    Text("Reset artwork position")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            val updated = project.addBlankCard()
                            onProjectChanged(updated)
                            updated.cards.lastOrNull()?.id?.let(onSelectCard)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("Add")
                    }
                    FilledTonalButton(
                        onClick = {
                            val id = selectedCardId ?: return@FilledTonalButton
                            val updated = project.duplicateCard(id)
                            onProjectChanged(updated)
                            val index = updated.cards.indexOfFirst { it.id == id }
                            updated.cards.getOrNull(index + 1)?.id?.let(onSelectCard)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("Copy")
                    }
                    FilledTonalButton(
                        onClick = {
                            val id = selectedCardId ?: return@FilledTonalButton
                            onProjectChanged(project.removeCard(id))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.size(4.dp))
                        Text("Delete")
                    }
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = { showImports = !showImports },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showImports) "Hide imports" else "Assets & imports")
            }
        }

        item {
            AnimatedVisibility(showImports) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onImportCardStrip,
                            enabled = project.cards.isNotEmpty() && !isImportingCardStrip,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null)
                            Spacer(Modifier.size(7.dp))
                            Text(if (isImportingCardStrip) "Importing artwork…" else "Import one image for all cards")
                        }
                        OutlinedButton(
                            onClick = onImportMegaPack,
                            enabled = !isImportingMegaPack && !isImportingCardStrip,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                            Spacer(Modifier.size(7.dp))
                            Text(if (isImportingMegaPack) "Loading MegaPack…" else "Import MegaPack")
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { showReferenceSettings = !showReferenceSettings },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showReferenceSettings) "Hide reference settings" else "Reference settings")
            }
        }

        item {
            AnimatedVisibility(showReferenceSettings) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Timing", fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModelMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = project.modelMode == mode,
                                    onClick = { onProjectChanged(project.copy(modelMode = mode)) },
                                    label = {
                                        Text(if (mode == ModelMode.ExactReference) "Exact reference" else "Custom timing")
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        ReferenceSwitch2(
                            label = "Disclaimer",
                            checked = project.showDisclaimer,
                            onCheckedChange = { onProjectChanged(project.copy(showDisclaimer = it)) },
                        )
                        ReferenceSwitch2(
                            label = "Badges",
                            checked = project.showHexagons,
                            onCheckedChange = { onProjectChanged(project.copy(showHexagons = it)) },
                        )
                        ReferenceSwitch2(
                            label = "Ending",
                            checked = project.showOutro,
                            onCheckedChange = { onProjectChanged(project.copy(showOutro = it)) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ReferenceSwitch2(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun titleFieldColors2() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    cursorColor = Color.Black,
    focusedLabelColor = Color.Black,
    unfocusedLabelColor = Color.DarkGray,
    focusedBorderColor = Color.Black,
    unfocusedBorderColor = Color(0xFF737373),
)
