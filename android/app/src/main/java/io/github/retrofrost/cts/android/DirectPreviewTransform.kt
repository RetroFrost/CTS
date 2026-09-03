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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CapCut-style direct object transform on the real rendered frame.
 *
 * Gesture changes stay in [draft]. The StudioProject is written only when Done / Apply onward is
 * pressed, so autosave and metadata are never hammered by pointer-move events. Unlike the previous
 * overlay editor, the preview itself is rendered from the draft project. This keeps badge layering,
 * crop, artwork clipping and renderer-specific composition truthful while editing.
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
    var showVerticalGuide by remember { mutableStateOf(false) }
    var showHorizontalGuide by remember { mutableStateOf(false) }

    val editing = editingIndex != null && draft != null
    val renderProject = remember(project, editingIndex, draft) {
        val index = editingIndex
        val value = draft
        if (index == null || value == null || index !in project.cards.indices) {
            project
        } else {
            val cards = project.cards.toMutableList()
            cards[index] = value
            project.copy(cards = cards)
        }
    }

    LaunchedEffect(renderProject, frame) {
        // Coalesce very dense pointer events without delaying normal seeking/playback.
        if (editing) delay(10)
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
            showVerticalGuide = false
            showHorizontalGuide = false
        }
    }

    val imageInfo = remember(draft?.image) {
        draft?.image?.takeIf { it.isNotBlank() }?.let(::readImageInfo)
    }

    fun startEdit(index: Int) {
        val value = project.cards.getOrNull(index) ?: return
        if (value.image.isBlank()) return
        playing = false
        editingIndex = index
        draft = value
        showVerticalGuide = false
        showHorizontalGuide = false
        onSelectedCardChange(index)
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
        showVerticalGuide = false
        showHorizontalGuide = false
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
                        if (editing) "Object transform" else accuracyLabel,
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
                                    val xFraction = if (size.width > 0) point.x / size.width.toFloat() else 0.5f
                                    val yFraction = if (size.height > 0) point.y / size.height.toFloat() else 0.5f
                                    directPreviewCardAt(project, frame, xFraction, yFraction)?.let(::startEdit)
                                }
                            } else {
                                detectTransformGestures { _, pan, zoom, rotation ->
                                    val current = draft ?: return@detectTransformGestures
                                    val refWidth = RendererRuntime.active.referenceWidth.coerceAtLeast(1).toFloat()
                                    val refHeight = RendererRuntime.active.referenceHeight.coerceAtLeast(1).toFloat()
                                    val viewWidth = size.width.toFloat().coerceAtLeast(1f)
                                    val viewHeight = size.height.toFloat().coerceAtLeast(1f)

                                    var nextX = current.imageX + pan.x / viewWidth * refWidth
                                    var nextY = current.imageY + pan.y / viewHeight * refHeight
                                    val snapDistance = 14.0
                                    showVerticalGuide = abs(nextX) <= snapDistance
                                    showHorizontalGuide = abs(nextY) <= snapDistance
                                    if (showVerticalGuide) nextX = 0.0
                                    if (showHorizontalGuide) nextY = 0.0

                                    var angle = current.imageRotation + rotation.toDouble()
                                    angle %= 360.0
                                    if (angle > 180.0) angle -= 360.0
                                    if (angle < -180.0) angle += 360.0

                                    draft = current.copy(
                                        imageX = nextX.coerceIn(-2400.0, 2400.0),
                                        imageY = nextY.coerceIn(-2400.0, 2400.0),
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
                        val bounds = geometry?.let { directArtworkBounds(current, it, imageInfo) }

                        if (geometry != null && showVerticalGuide) {
                            val centreX = maxWidth * ((geometry.left + geometry.width / 2f) / geometry.referenceWidth)
                            Box(
                                Modifier
                                    .offset(x = centreX - 0.5.dp)
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                            )
                        }
                        if (geometry != null && showHorizontalGuide) {
                            val centreY = maxHeight * ((geometry.top + geometry.height / 2f) / geometry.referenceHeight)
                            Box(
                                Modifier
                                    .offset(y = centreY - 0.5.dp)
                                    .height(1.dp)
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
                            )
                        }

                        if (bounds != null) {
                            val left = maxWidth * (bounds.left / bounds.referenceWidth)
                            val top = maxHeight * (bounds.top / bounds.referenceHeight)
                            val width = maxWidth * (bounds.width / bounds.referenceWidth)
                            val height = maxHeight * (bounds.height / bounds.referenceHeight)

                            TransformSelectionBox(
                                left = left,
                                top = top,
                                width = width,
                                height = height,
                                rotation = current.imageRotation.toFloat(),
                                onScaleFactor = { factor ->
                                    val latest = draft ?: return@TransformSelectionBox
                                    draft = latest.copy(
                                        imageScale = (latest.imageScale * factor).coerceIn(0.05, 12.0),
                                    )
                                },
                                onRotationDelta = { delta ->
                                    val latest = draft ?: return@TransformSelectionBox
                                    var angle = (latest.imageRotation + delta) % 360.0
                                    if (angle > 180.0) angle -= 360.0
                                    if (angle < -180.0) angle += 360.0
                                    draft = latest.copy(imageRotation = angle)
                                },
                            )
                        }
                    }
                }
            }
            Text(
                if (editing) "Drag the object · pinch to resize · twist to rotate · use the corner handles for precise scaling"
                else "Tap artwork directly in the video to select it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (editing) {
            item {
                val index = editingIndex ?: 0
                val current = draft ?: project.cards.getOrNull(index) ?: StudioCard()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Card ${index + 1}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "X ${current.imageX.roundToInt()} · Y ${current.imageY.roundToInt()} · ${"%.2f".format(current.imageScale)}× · ${current.imageRotation.roundToInt()}°",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { finishEdit(true) }) { Text("Done") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { finishEdit(false) }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            OutlinedButton(
                                onClick = {
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
                                    showVerticalGuide = true
                                    showHorizontalGuide = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Reset") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = current.imageLayer != "front",
                                onClick = { draft = current.copy(imageLayer = "behind") },
                                label = { Text("Behind badge") },
                            )
                            FilterChip(
                                selected = current.imageLayer == "front",
                                onClick = { draft = current.copy(imageLayer = "front") },
                                label = { Text("In front") },
                            )
                        }

                        FilledTonalButton(
                            enabled = index < project.cards.lastIndex,
                            onClick = { finishEdit(true, applyFromHere = true) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Apply from card ${index + 1} onward")
                        }
                    }
                }
            }
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

private data class DirectImageInfo(val width: Int, val height: Int)

private data class DirectArtworkBounds(
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
    val height = when {
        RelationshipsPrecisionFrameRenderer.enabled(spec) -> spec.imageHeight.coerceIn(1f, refHeight)
        else -> RendererArtworkLayout.imageBottom(card, spec).coerceIn(1f, refHeight)
    }
    val top = if (RelationshipsPrecisionFrameRenderer.enabled(spec)) spec.track("card.$index.y", rendererFrame) ?: 0f else 0f
    return DirectPreviewGeometry(left, top, width, height, refWidth, refHeight)
}

private fun directArtworkBounds(
    card: StudioCard,
    geometry: DirectPreviewGeometry,
    imageInfo: DirectImageInfo?,
): DirectArtworkBounds {
    val sourceWidth = imageInfo?.width?.toFloat()?.coerceAtLeast(1f) ?: geometry.width
    val sourceHeight = imageInfo?.height?.toFloat()?.coerceAtLeast(1f) ?: geometry.height
    val cropWidth = sourceWidth * (1.0 - card.imageCropLeft.coerceIn(0.0, 0.95) - card.imageCropRight.coerceIn(0.0, 0.95)).coerceAtLeast(0.01).toFloat()
    val cropHeight = sourceHeight * (1.0 - card.imageCropTop.coerceIn(0.0, 0.95) - card.imageCropBottom.coerceIn(0.0, 0.95)).coerceAtLeast(0.01).toFloat()
    val coverScale = max(geometry.width / cropWidth.coerceAtLeast(1f), geometry.height / cropHeight.coerceAtLeast(1f))
    val width = cropWidth * coverScale * card.imageScale.coerceIn(0.05, 12.0).toFloat()
    val height = cropHeight * coverScale * card.imageScale.coerceIn(0.05, 12.0).toFloat()
    val centreX = geometry.left + geometry.width / 2f + card.imageX.toFloat()
    val centreY = geometry.top + geometry.height / 2f + card.imageY.toFloat()
    return DirectArtworkBounds(
        left = centreX - width / 2f,
        top = centreY - height / 2f,
        width = width,
        height = height,
        referenceWidth = geometry.referenceWidth,
        referenceHeight = geometry.referenceHeight,
    )
}

private fun directPreviewCardAt(
    project: StudioProject,
    projectFrame: Int,
    xFraction: Float,
    yFraction: Float,
): Int? {
    if (project.cards.isEmpty()) return null
    val spec = RendererRuntime.active
    val rendererFrame = directRendererFrame(project, projectFrame, spec) ?: return null
    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()
    val refHeight = spec.referenceHeight.coerceAtLeast(1).toFloat()
    val x = xFraction.coerceIn(0f, 1f) * refWidth
    val y = yFraction.coerceIn(0f, 1f) * refHeight

    val visible = project.cards.indices.mapNotNull { index ->
        val geometry = directPreviewGeometry(project, projectFrame, index) ?: return@mapNotNull null
        index to geometry
    }
    visible.firstOrNull { (_, geometry) ->
        x in geometry.left..(geometry.left + geometry.width) &&
            y in geometry.top..(geometry.top + geometry.height)
    }?.let { return it.first }

    return visible.minByOrNull { (_, geometry) ->
        abs((geometry.left + geometry.width / 2f) - x) + abs((geometry.top + geometry.height / 2f) - y)
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
        val base = index * spec.slotPitch
        return if (RelationshipsPrecisionFrameRenderer.enabled(spec)) spec.track("card.$index.x", frame) ?: base else base
    }

    val scroll = when {
        RelationshipsTimeline.isRelationships(spec) -> {
            val segment = (frame - spec.continuousStartFrame) / 4096
            spec.track("relationships.scroll.$segment", frame)
                ?: ((frame - spec.continuousStartFrame) * 2f)
        }
        RibbonTimeline.isRibbon(spec) -> {
            val segment = (frame - spec.continuousStartFrame) / 4096
            spec.track("ribbon.scroll.$segment", frame)
                ?: ((frame - spec.continuousStartFrame).toFloat() /
                    RibbonTimeline.continuousStepFrames(project, spec).coerceAtLeast(1) * spec.slotPitch)
        }
        else -> ((frame - spec.continuousStartFrame) * 2f)
    }
    val baseX = index * spec.slotPitch - scroll
    val slotX = if (RelationshipsPrecisionFrameRenderer.enabled(spec)) spec.track("card.$index.x", frame) ?: baseX else baseX
    val refWidth = spec.referenceWidth.coerceAtLeast(1).toFloat()
    return slotX.takeIf { it > -spec.slotPitch && it < refWidth + spec.slotPitch }
}

@Composable
private fun TransformSelectionBox(
    left: Dp,
    top: Dp,
    width: Dp,
    height: Dp,
    rotation: Float,
    onScaleFactor: (Double) -> Unit,
    onRotationDelta: (Double) -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(x = left, y = top)
            .width(width.coerceAtLeast(24.dp))
            .height(height.coerceAtLeast(24.dp))
            .graphicsLayer { rotationZ = rotation }
            .border(1.5.dp, Color.White, RoundedCornerShape(2.dp)),
    ) {
        TransformScaleHandle(Alignment.TopStart, -1f, -1f, onScaleFactor)
        TransformScaleHandle(Alignment.TopEnd, 1f, -1f, onScaleFactor)
        TransformScaleHandle(Alignment.BottomStart, -1f, 1f, onScaleFactor)
        TransformScaleHandle(Alignment.BottomEnd, 1f, 1f, onScaleFactor)
        TransformRotationHandle(onRotationDelta)
    }
}

@Composable
private fun BoxScope.TransformScaleHandle(
    alignment: Alignment,
    signX: Float,
    signY: Float,
    onScaleFactor: (Double) -> Unit,
) {
    Box(
        Modifier
            .align(alignment)
            .size(26.dp)
            .background(Color.White, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .pointerInput(alignment, signX, signY) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val signedPixels = dragAmount.x * signX + dragAmount.y * signY
                    val factor = (1.0 + signedPixels / 180.0).coerceIn(0.72, 1.28)
                    onScaleFactor(factor)
                }
            },
    )
}

@Composable
private fun BoxScope.TransformRotationHandle(onRotationDelta: (Double) -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 2.dp)
            .size(28.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onRotationDelta((dragAmount.x * 0.65f).toDouble())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("↻", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

private fun readImageInfo(path: String): DirectImageInfo? = runCatching {
    val file = File(path)
    if (!file.isFile) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else DirectImageInfo(bounds.outWidth, bounds.outHeight)
}.getOrNull()
