package io.github.retrofrost.cts.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun FinalStudioApp() {
    val context = LocalContext.current
    var project by remember { mutableStateOf(StudioProject()) }
    var selected by remember { mutableIntStateOf(0) }
    var frame by remember { mutableIntStateOf(0) }
    var metadata by remember { mutableStateOf(RenderMetadata(1, 0.0, 60)) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var message by remember { mutableStateOf("Shared Windows renderer ready") }
    val exportState by FinalExportState.state.collectAsState()
    val projectJson = remember(project) { project.toJson() }

    LaunchedEffect(projectJson) {
        try {
            val next = withContext(Dispatchers.Default) { SharedRenderer.metadata(project) }
            metadata = next
            frame = frame.coerceIn(0, (next.frameCount - 1).coerceAtLeast(0))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            message = "Renderer metadata failed: ${error.message}"
        }
    }
    LaunchedEffect(projectJson, frame) {
        try {
            val bitmap = withContext(Dispatchers.Default) { SharedRenderer.render(project, frame, 960, 540) }
            preview?.recycle()
            preview = bitmap
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            message = "Preview failed: ${error.message}"
        }
    }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val raw = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            project = StudioProject.fromJson(raw); selected = 0; frame = 0; message = "Project opened"
        }.onFailure { message = "Open failed: ${it.message}" }
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri, "w")!!.bufferedWriter().use { it.write(project.toJson()) }; message = "Project saved"
        }.onFailure { message = "Save failed: ${it.message}" }
    }
    val importData = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            project = SharedRenderer.importData(project, SharedRenderer.materialize(context, uri, "data").absolutePath)
            selected = 0; frame = 0; message = "Imported ${project.cards.size} cards through shared engine"
        }.onFailure { message = "Import failed: ${it.message}" }
    }
    val importPack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val local = SharedRenderer.materialize(context, uri, "megapack")
            project = SharedRenderer.importMegaPack(local.absolutePath, File(context.filesDir, "megapacks/${System.currentTimeMillis()}"))
            selected = 0; frame = 0; message = "MegaPack loaded through shared engine"
        }.onFailure { message = "MegaPack failed: ${it.message}" }
    }
    val chooseImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && selected in project.cards.indices) runCatching {
            val local = SharedRenderer.materialize(context, uri, "art")
            project = project.copy(cards = project.cards.toMutableList().also { it[selected] = it[selected].copy(image = local.absolutePath) })
            message = "Artwork assigned"
        }.onFailure { message = "Artwork failed: ${it.message}" }
    }
    val chooseSoundtrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            project = project.copy(soundtrack = uri.toString()); message = "Soundtrack selected"
        }
    }
    val exportVideo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) { FinalExportService.start(context, project.toJson(), uri); message = "Export started in background" }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        topBar = {
            Surface(Modifier.statusBarsPadding(), tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Action("New", Icons.Default.Add) { project = StudioProject(); selected = 0; frame = 0; message = "New project" }
                    Action("Open", Icons.Default.FolderOpen) { openProject.launch(arrayOf("application/json", "text/plain")) }
                    Action("Save", Icons.Default.Save) { saveProject.launch("Cubical-Compare-project.json") }
                    Action("Data", Icons.Default.TableChart) { importData.launch(arrayOf("text/csv", "text/tab-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }
                    Action("MegaPack", Icons.Default.Inventory2) { importPack.launch(arrayOf("application/zip", "application/octet-stream")) }
                    Action("Add card", Icons.Default.AddBox) {
                        val cards = project.cards + StudioCard(title = "New card")
                        project = project.copy(cards = cards); selected = cards.lastIndex
                    }
                    Action("Export", Icons.Default.MovieCreation, enabled = !exportState.running) {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        exportVideo.launch("Cubical-Compare-2.0.1.mp4")
                    }
                    if (exportState.running) Action("Cancel", Icons.Default.Cancel) { FinalExportService.cancel(context) }
                }
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (exportState.running) LinearProgressIndicator(progress = { exportState.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(if (exportState.running) "${exportState.stage} · ${exportState.detail}" else message, style = MaterialTheme.typography.bodySmall)
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(10.dp)) {
            if (maxWidth < 760.dp) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PreviewPanel(preview, frame, metadata) { frame = it }
                    HorizontalCardPicker(project, selected) { selected = it }
                    Inspector(project, selected, onProject = { project = it }, onChooseImage = { chooseImage.launch(arrayOf("image/*")) }, onChooseSoundtrack = { chooseSoundtrack.launch(arrayOf("audio/*")) })
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardList(project, selected, Modifier.width(250.dp).fillMaxHeight(), onSelect = { selected = it }, onProject = { next -> project = next; selected = selected.coerceIn(0, next.cards.lastIndex.coerceAtLeast(0)) })
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PreviewPanel(preview, frame, metadata) { frame = it }
                        Text("Exact shared renderer · ${metadata.frameCount} frames · ${"%.2f".format(metadata.duration)} s", style = MaterialTheme.typography.labelMedium)
                    }
                    Inspector(project, selected, Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState()), onProject = { project = it }, onChooseImage = { chooseImage.launch(arrayOf("image/*")) }, onChooseSoundtrack = { chooseSoundtrack.launch(arrayOf("audio/*")) })
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

@Composable
private fun PreviewPanel(bitmap: android.graphics.Bitmap?, frame: Int, metadata: RenderMetadata, onFrame: (Int) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "Renderer preview", Modifier.fillMaxSize()) else CircularProgressIndicator()
            }
            val max = (metadata.frameCount - 1).coerceAtLeast(1)
            Slider(frame.toFloat().coerceIn(0f, max.toFloat()), { onFrame(it.toInt()) }, valueRange = 0f..max.toFloat())
            Text("Frame $frame / ${metadata.frameCount - 1}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CardList(project: StudioProject, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit, onProject: (StudioProject) -> Unit) {
    ElevatedCard(modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Text("Cards", fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
                items(project.cards.size) { index ->
                    val card = project.cards[index]
                    ListItem(headlineContent = { Text(card.title.ifBlank { "Untitled" }, maxLines = 1) }, supportingContent = { Text(card.value.ifBlank { "No badge value" }, maxLines = 1) },
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(index) }, colors = ListItemDefaults.colors(containerColor = if (index == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface))
                }
            }
            OutlinedButton(onClick = { if (project.cards.size > 1 && selected in project.cards.indices) onProject(project.copy(cards = project.cards.toMutableList().also { it.removeAt(selected) })) }, modifier = Modifier.fillMaxWidth(), enabled = project.cards.size > 1) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete selected")
            }
        }
    }
}

@Composable
private fun HorizontalCardPicker(project: StudioProject, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        project.cards.forEachIndexed { index, card -> FilterChip(selected = selected == index, onClick = { onSelect(index) }, label = { Text("${index + 1} · ${card.title.ifBlank { "Untitled" }}") }) }
    }
}

@Composable
private fun Inspector(project: StudioProject, selected: Int, modifier: Modifier = Modifier, onProject: (StudioProject) -> Unit, onChooseImage: () -> Unit, onChooseSoundtrack: () -> Unit) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Inspector", fontWeight = FontWeight.Bold)
            OutlinedTextField(project.name, { onProject(project.copy(name = it)) }, label = { Text("Project name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            project.cards.getOrNull(selected)?.let { card ->
                fun update(next: StudioCard) = onProject(project.copy(cards = project.cards.toMutableList().also { it[selected] = next }))
                OutlinedTextField(card.title, { update(card.copy(title = it)) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(card.value, { update(card.copy(value = it)) }, label = { Text("Badge value") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(card.description, { update(card.copy(description = it)) }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                OutlinedTextField(card.image, { update(card.copy(image = it)) }, label = { Text("Artwork path") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedButton(onClick = onChooseImage, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Choose artwork") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(project.showBadges, { onProject(project.copy(showBadges = it)) }); Text("Badges")
                Spacer(Modifier.width(8.dp)); Checkbox(project.creditsEnabled, { onProject(project.copy(creditsEnabled = it)) }); Text("Credits")
            }
            OutlinedButton(onClick = onChooseSoundtrack, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(6.dp)); Text(if (project.soundtrack.isBlank()) "Choose soundtrack" else "Change soundtrack") }
            if (project.soundtrack.isNotBlank()) {
                Text("Soundtrack volume ${(project.soundtrackVolume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(project.soundtrackVolume, { onProject(project.copy(soundtrackVolume = it)) }, valueRange = 0f..1f)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(project.soundtrackLoop, { onProject(project.copy(soundtrackLoop = it)) }); Spacer(Modifier.width(8.dp)); Text("Loop soundtrack") }
            }
        }
    }
}
