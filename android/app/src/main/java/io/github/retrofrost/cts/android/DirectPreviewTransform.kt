package io.github.retrofrost.cts.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Video-editor style preview. Artwork transforms stay local while the user gestures and are
 * committed to the project only once on Done, so autosave/metadata/export are never thrashed.
 */
@Composable
internal fun DirectPreviewPage(
    project: StudioProject,
    metadata: RenderMetadata,
    metadataLoading: Boolean,
    accuracyLabel: String,
    accuracyDetail: String,
    accuracyExact: Boolean,
    onProjectChange: (StudioProject) -> Unit,
    onSelectedCardChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf<StudioCard?>(null) }

    val editing = editingIndex != null && draft != null
    val renderProject = remember(project, editingIndex) {
        val index = editingIndex
        if (index == null || index !in project.cards.indices) {
            project
        } else {
            val cards = project.cards.toMutableList()
            cards[index] = cards[index].copy(image = "")
            project.copy(cards = cards)
        }
    }

    LaunchedEffect(renderProject, frame) {
        runCatching {
            withContext(Dispatchers.Default) { RendererBridge.render(renderProject, frame, 640, 360) }
        }.onSuccess { next ->
            previewBitmap?.takeIf { it !== next && !it.isRecycled }?.recycle()
            previewBitmap = next
        }
    }
    DisposableEffect(Unit) {
        onDispose { previewBitmap?.takeIf { !it.isRecycled }?.recycle() }
    }

    val editBitmap = remember(editingIndex, draft?.image) {
        draft?.image?.takeIf { it.isNotBlank() }?.let(::decodeDirectPreviewBitmap)
    }
    DisposableEffect(editBitmap) {
        onDispose { editBitmap?.takeIf { !it.isRecycled }?.recycle() }
    }

    LaunchedEffect(metadata.frameCount) {
        frame = frame.coerceIn(0, (metadata.frameCount - 1).coerceAtLeast(0))
    }
    LaunchedEffect(playing, speed, metadata.frameCount, metadata.fps, editing) {
        while (playing && !editing) {
            delay((1000.0 / (metadata.fps.coerceAtLeast(1) * speed)).toLong().coerceAtLeast(4L))
            if (frame + 1 >= metadata.frameCount) {
                playing = false
                frame = 0
            } else {
                frame += 1
            }
        }
    }
    LaunchedEffect(project.cards.size, editingIndex) {
        if (editingIndex != null && editingIndex !in project.cards.indices) {
            editingIndex = null
            draft = null
        }
    }

    fun finishEdit(commit: Boolean, applyFromHere: Boolean = false) {
        val index = editingIndex
        val value = draft
        if (commit && index != null && value != null && index in project.cards.indices) {
            val cards = project.cards.toMutableList()
            if (applyFromHere) {
                for (targetIndex in index until cards.size) {
                    val target = cards[targetIndex]
                    cards[targetIndex] = target.copy(
                        imageX = value.imageX,
                        imageY = value.imageY,
                        imageScale = value.imageScale,
                        imageRotation = value.imageRotation,
                        imageCropLeft = value.imageCropLeft,
                        imageCropTop = value.imageCropTop,
                        imageCropRight = value.imageCropRight,
                        imageCropBottom = value.imageCropBottom,
                        imageLayer = value.imageLayer,
                    )
                }
            } else {
                cards[index] = value
            }
            onProjectChange(project.copy(cards = cards))
            onSelectedCardChange(index)
        }
        editingIndex = null
        draft = null
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Preview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (editing) "Direct transform" else accuracyLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (editing || accuracyExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    )
                }
                FilledTonalButton(
                    enabled = !metadataLoading && !editing,
                    onClick = { playing = !playing },
                ) {
                    androidx.compose.material3.Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "Pause" else "Play")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(project.cards, frame, editingIndex, draft?.id) {
                            if (editingIndex == null) {
                                detectTapGestures { point ->
                                    val fraction = if (size.width > 0) point.x / size.width.toFloat() else 0.5f
                                    val index = directPreviewCardAt(project, frame, fraction)
                                    val card = index?.let(project.cards::getOrNull)
                                    if (index != null && card != null && card.image.isNotBlank()) {
                                        playing = false
                                        editingIndex = index
                                        draft = card
                                        onSelectedCardChange(index)
                                    }
                                }
                            } else {
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    val current = draft ?: return@detectTransformGestures
                                    val refWidth = RendererRuntime.active.referenceWidth.coerceAtLeast(1).toFloat()
                                    val refHeight = RendererRuntime.active.referenceHeight.coerceAtLeast(1).toFloat()
                                    val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                                    val viewHeight = size.height.toFloat().coerceAtLeast(1f)
                                    var angle = current.imageRotation + rotation.toDouble()
                                    angle %= 360.0
                                    if (angle > 180.0) angle -= 360.0
                                    if (angle < -180.0) angle += 360.0
                                    draft = current.copy(
                                        imageX = (current.imageX + pan.x / viewWidth * refWidth).coerceIn(-2400.0, 2400.0),
                                        imageY = (current.imageY + pan.y / viewHeight * refHeight).coerceIn(-2400.0, 2400.0),
                                        imageScale = (current.imageScale * zoom.toDouble()).coerceIn(0.05, 12.0),
                                        imageRotation = angle,
                                    )
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    previewBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Video preview",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } ?: Text("Rendering…", color = Color.White)

                    val index = editingIndex
                    val current = draft
                    if (index != null && current != null) {
                        val geometry = directPreviewGeometry(project, frame, index)
                        if (geometry != null && editBitmap != null) {
                            val left = maxWidth * (geometry.left / geometry.referenceWidth)
                            val top = maxHeight * (geometry.top / geometry.referenceHeight)
                            val width = maxWidth * (geometry.width / geometry.referenceWidth)
                            val height = maxHeight * (geometry.height / geometry.referenceHeight)

                            Box(
                                modifier = Modifier
                                    .offset(x = left, y = top)
                                    .width(width)
                                    .height(height)
                                    .clipToBounds()
                                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = editBitmap.asImageBitmap(),
                                    contentDescription = "Selected artwork",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = current.imageScale.toFloat()
                                            scaleY = current.imageScale.toFloat()
                                            translationX = current.imageX.toFloat() / geometry.width.coerceAtLeast(1f) * size.width
                                            translationY = current.imageY.toFloat() / geometry.height.coerceAtLeast(1f) * size.height
                                            rotationZ = current.imageRotation.toFloat()
                                        },
                                    contentScale = ContentScale.Crop,
                                )
                            }

                            DirectHandle(
                                alignment = Alignment.TopStart,
                                x = left,
                                y = top,
                                signX = -1f,
                                signY = -1f,
                            ) { delta ->
                                draft = current.copy(imageScale = (current.imageScale * (1.0 + delta)).coerceIn(0.05, 12.0))
                            }
                            DirectHandle(
                                alignment = Alignment.TopEnd,
                                x = left + width,
                                y = top,
                                signX = 1f,
                                signY = -1f,
                            ) { delta ->
                                draft = current.copy(imageScale = (current.imageScale * (1.0 + delta)).coerceIn(0.05, 12.0))
                            }
                            DirectHandle(
                                alignment = Alignment.BottomStart,
                                x = left,
                                y = top + height,
                                signX = -1f,
                                signY = 1f,
                            ) { delta ->
                                draft = current.copy(imageScale = (current.imageScale * (1.0 + delta)).coerceIn(0.05, 12.0))
                            }
                            DirectHandle(
                                alignment = Alignment.BottomEnd,
                                x = left + width,
                                y = top + height,
                                signX = 1f,
                                signY = 1f,
                            ) { delta ->
                                draft = current.copy(imageScale = (current.imageScale * (1.0 + delta)).coerceIn(0.05, 12.0))
                            }
                        }

                        Column(
                            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { finishEdit(true) }) { Text("Done") }
                                OutlinedButton(onClick = { finishEdit(false) }) { Text("Cancel") }
                                OutlinedButton(onClick = {
                                draft = current.copy(
                                    imageX = 0.0,
                                    imageY = 0.0,
                                    imageScale = 1.0,
                                    imageRotation = 0.0,
                                    imageCropLeft = 0.0,
                                    imageCropTop = 0.0,
                                    imageCropRight = 0.0,
                                    imageCropBottom = 0.0,
                                )
                                }) { Text("Reset") }
                            }
                            FilledTonalButton(
                                enabled = index < project.cards.lastIndex,
                                onClick = { finishEdit(true, applyFromHere = true) },
                            ) {
                                Text("Apply from card ${index + 1} onward")
                            }
                        }
                    }
                }
            }
            Text(
                if (editing) "Drag to move · pinch to resize · twist to rotate · Apply from here copies this transform to every following card"
                else "Tap artwork directly in the video to transform it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text("Frame ${frame + 1} of ${metadata.frameCount}", fontWeight = FontWeight.Medium)
            Slider(
                value = frame.toFloat(),
                onValueChange = { frame = it.roundToInt() },
                valueRange = 0f..(metadata.frameCount - 1).coerceAtLeast(1).toFloat(),
                enabled = !metadataLoading && !editing,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !editing,
                    onClick = { frame = (frame - 1).coerceAtLeast(0) },
                    modifier = Modifier.weight(1f),
                ) { Text("−1") }
                listOf(0.5f, 1f, 2f).forEach { option ->
                    FilterChip(
                        selected = speed == option,
                        enabled = !editing,
                        onClick = { speed = option },
                        label = { Text("${option}×") },
                    )
                }
                OutlinedButton(
                    enabled = !editing,
                    onClick = { frame = (frame + 1).coerceAtMost((metadata.frameCount - 1).coerceAtLeast(0)) },
                    modifier = Modifier.weight(1f),
                ) { Text("+1") }
            }
            Text(
                "${DurationFormat.formatPrecise(frame.toDouble() / metadata.fps.coerceAtLeast(1))} / ${DurationFormat.formatPrecise(metadata.duration)} · ${metadata.fps} FPS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Renderer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(RendererRuntime.active.name, fontWeight = FontWeight.Medium)
                    Text(accuracyLabel, color = if (accuracyExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    Text(accuracyDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private data class DirectPreviewGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val referenceWidth: Float,
    val referenceHeight: Float,
)

private fun directPreviewGeometry(project: StudioProject, projectFrame: Int, index: Int): DirectPreviewGeometry? {
    if (index !in project.cards.indices) return null
    val spec = RendererRuntime.active
    val rendererFrame = directRendererFrame(project, projectFrame, spec) ?: return null
    val slotX = directSlotX(project, rendererFrame, index, spec) ?: return null
    val card = project.cards[index]
    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()
    val refHeight = spec.referenceHeight.coerceAtLeast(1).toFloat()
    val left = slotX + spec.bodyInset
    val width = spec.bodyWidth.coerceAtLeast(1f)
    val height = if (RelationshipsTimeline.isRelationships(spec)) {
        val descriptionHeight = if (card.description.isBlank()) 0f else 115f
        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        (refHeight - descriptionHeight - titleHeight).coerceAtLeast(1f)
    } else {
        spec.imageHeight.coerceIn(1f, refHeight)
    }
    return DirectPreviewGeometry(left, 0f, width, height, refWidth, refHeight)
}

private fun directPreviewCardAt(project: StudioProject, projectFrame: Int, xFraction: Float): Int? {
    if (project.cards.isEmpty()) return null
    val spec = RendererRuntime.active
    val rendererFrame = directRendererFrame(project, projectFrame, spec) ?: return null
    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()
    val x = xFraction.coerceIn(0f, 1f) * refWidth
    val visible = project.cards.indices.mapNotNull { index ->
        directSlotX(project, rendererFrame, index, spec)?.let { index to it }
    }
    visible.firstOrNull { (_, slotX) ->
        val left = slotX + spec.bodyInset
        x in left..(left + spec.bodyWidth)
    }?.let { return it.first }
    return visible.minByOrNull { (_, slotX) ->
        abs((slotX + spec.bodyInset + spec.bodyWidth / 2f) - x)
    }?.first
}

private fun directRendererFrame(project: StudioProject, projectFrame: Int, spec: RendererSpec): Int? {
    var frame = projectFrame.coerceAtLeast(0)
    when (project.introMode) {
        IntroMode.RENDERER -> Unit
        IntroMode.DISABLED -> frame += RendererBridge.rendererIntroFrames(spec)
        IntroMode.CUSTOM -> {
            if (project.introVideo.isBlank()) return null
            val customFrames = RendererBridge.customIntroFrames(project)
            if (frame < customFrames) return null
            frame = frame - customFrames + RendererBridge.rendererIntroFrames(spec)
        }
    }
    return frame
}

private fun directSlotX(project: StudioProject, frame: Int, index: Int, spec: RendererSpec): Float? {
    if (index !in project.cards.indices) return null
    if (frame < spec.continuousStartFrame) {
        val start = spec.openingStarts.getOrElse(index) {
            (spec.openingStarts.lastOrNull() ?: 384) + (index - spec.openingStarts.size + 1).coerceAtLeast(0) * 140
        }
        if (frame < start) return null
        if (index >= 4 && RelationshipsTimeline.isRelationships(spec)) return null
        return index * spec.slotPitch
    }

    val scroll = if (RelationshipsTimeline.isRelationships(spec)) {
        val segment = (frame - spec.continuousStartFrame) / 4096
        spec.track("relationships.scroll.$segment", frame)
            ?: ((frame - spec.continuousStartFrame) * 2f)
    } else {
        ((frame - spec.continuousStartFrame) * 2f)
    }
    val slotX = index * spec.slotPitch - scroll
    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()
    return slotX.takeIf { it > -spec.slotPitch && it < refWidth + spec.slotPitch }
}

@Composable
private fun BoxScope.DirectHandle(
    alignment: Alignment,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    signX: Float,
    signY: Float,
    onScaleDelta: (Double) -> Unit,
) {
    Box(
        Modifier
            .offset(x = x - 15.dp, y = y - 15.dp)
            .size(30.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(alignment, signX, signY) {
                detectDragGestures { _, dragAmount ->
                    val signedPixels = dragAmount.x * signX + dragAmount.y * signY
                    onScaleDelta((signedPixels / 240f).toDouble().coerceIn(-0.35, 0.35))
                }
            },
    )
}

private fun decodeDirectPreviewBitmap(path: String): Bitmap? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 1280) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    })
}.getOrNull()
