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
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel

/**
 * CTS 2.0 card workspace.
 *
 * Reference models are sealed presets. The app may select a model and provide content/artwork,
 * but it never overrides model-owned colours, gradients, geometry, typography, timing,
 * animation, badge styling, disclaimer styling, ending styling, or other visual rules.
 */
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
    val selected = project.cards.firstOrNull { it.id == selectedCardId }
    var showCardDetails by remember { mutableStateOf(false) }
    var showImports by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cards", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(
                    "${project.cards.size} cards · ${project.model.label}",
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
                        "Pick the reference. Its visual design and animation are fixed by the model itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        VisualModel.entries.forEach { model ->
                            FilterChip(
                                selected = project.model == model,
                                onClick = { onProjectChanged(project.copy(model = model)) },
                                label = {
                                    Text(model.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                modifier = Modifier.fillMaxWidth().height(56.dp),
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
                                Text(card.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Text("Add cards or paste your comparison data to begin.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                        Button(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Image, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(if (selected.imageSubcard.source.isNullOrBlank()) "Add artwork" else "Replace artwork")
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

        item { Spacer(Modifier.height(12.dp)) }
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
