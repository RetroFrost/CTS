package io.github.retrofrost.cts.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.AlignmentLine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import io.github.retrofrost.cts.android.ui.theme.CtsPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL

private val HexagonShape = GenericShape { size, _ ->
    moveTo(size.width * 0.25f, 0f)
    lineTo(size.width * 0.75f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.75f, size.height)
    lineTo(size.width * 0.25f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}

private enum class ResizeCorner { NorthWest, NorthEast, SouthWest, SouthEast }

@Composable
fun ProgramMonitor(
    project: CtsProject,
    positionSeconds: Float,
    selectedCardId: String?,
    onSelectCard: (String) -> Unit,
    onImageTransformChanged: (String, NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placements = TimelineEngine.placements(project, positionSeconds)
    val fadeAlpha = TimelineEngine.fadeAlpha(project, positionSeconds)

    Surface(
        modifier = modifier,
        color = Color.Black,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(Color.Black),
        ) {
            val visibleCards = project.model.visibleCards
            val cardWidth = maxWidth / visibleCards

            placements.forEach { placement ->
                val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
                val entranceYOffset = maxHeight * ((1f - placement.alpha) * 0.018f)

                ParentCard(
                    card = card,
                    model = project.model,
                    showHexagons = project.showHexagons,
                    selected = selectedCardId == card.id,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { transform ->
                        onImageTransformChanged(card.id, transform)
                    },
                    modifier = Modifier
                        .offset(
                            x = cardWidth * placement.xInCards,
                            y = entranceYOffset,
                        )
                        .width(cardWidth)
                        .fillMaxHeight()
                        .alpha(placement.alpha * fadeAlpha)
                        .zIndex(placement.cardIndex.toFloat()),
                )
            }
        }
    }
}

@Composable
private fun ParentCard(
    card: CtsCard,
    model: VisualModel,
    showHexagons: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF121419))
            .border(0.6.dp, Color(0xFF090A0C))
            .clickable(onClick = onSelect),
    ) {
        when (model) {
            VisualModel.Reference -> ReferenceCard(
                card = card,
                showHexagons = showHexagons,
                selected = selected,
                onSelect = onSelect,
                onImageTransformChanged = onImageTransformChanged,
            )

            VisualModel.Illustrated -> IllustratedCard(
                card = card,
                showHexagons = showHexagons,
                selected = selected,
                onSelect = onSelect,
                onImageTransformChanged = onImageTransformChanged,
            )

            VisualModel.Compact -> CompactCard(
                card = card,
                showHexagons = showHexagons,
                selected = selected,
                onSelect = onSelect,
                onImageTransformChanged = onImageTransformChanged,
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.5.dp, CtsPurple),
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.ReferenceCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    Frame(NormalizedRect(0f, 0f, 1f, 0.44f), Modifier.background(Color(0xFF111319))) {
        Badge(card, showHexagons, Modifier.align(Alignment.Center))
    }
    Frame(NormalizedRect(0f, 0.44f, 1f, 0.098f), Modifier.background(Color(0xFFF4F3EE))) {
        CardText(card.title, Color(0xFF111111), FontWeight.Bold, 9.sp)
    }
    Frame(NormalizedRect(0f, 0.538f, 1f, 0.132f), Modifier.background(Color(0xFFC9C5BA))) {
        CardText(card.description, Color(0xFF2B2925), FontWeight.Normal, 6.5.sp, 4)
    }
    Frame(imageFrame(VisualModel.Reference), Modifier.background(Color(0xFF5E605E))) {
        ImageSubcardFrame(
            subcard = card.imageSubcard,
            selected = selected,
            contentScale = ContentScale.Crop,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.IllustratedCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    Frame(
        imageFrame(VisualModel.Illustrated),
        Modifier.background(
            Brush.verticalGradient(
                0f to Color(0xFF55CFE4),
                0.64f to Color(0xFF55CFE4),
                0.65f to Color(0xFFEAC17B),
                1f to Color(0xFFF2D394),
            ),
        ),
    ) {
        ImageSubcardFrame(
            subcard = card.imageSubcard,
            selected = selected,
            contentScale = ContentScale.Fit,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }
    Frame(NormalizedRect(0f, 0.88f, 1f, 0.12f), Modifier.background(Color(0xFFF4F3EE))) {
        CardText(card.title, Color(0xFF111111), FontWeight.Bold, 9.sp)
    }
    Frame(NormalizedRect(0.13f, 0.035f, 0.74f, 0.28f)) {
        Badge(card, showHexagons, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun BoxWithConstraintsScope.CompactCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    Frame(NormalizedRect(0f, 0f, 1f, 0.39f), Modifier.background(Color(0xFF101113))) {
        Badge(card, showHexagons, Modifier.align(Alignment.Center))
    }
    Frame(NormalizedRect(0f, 0.39f, 1f, 0.115f), Modifier.background(Color(0xFFF0F0F0))) {
        CardText(card.title, Color(0xFF111111), FontWeight.Bold, 8.sp, 3)
    }
    Frame(imageFrame(VisualModel.Compact), Modifier.background(Color(0xFF777976))) {
        ImageSubcardFrame(
            subcard = card.imageSubcard,
            selected = selected,
            contentScale = ContentScale.Crop,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.Frame(
    rect: NormalizedRect,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .offset(x = maxWidth * rect.x, y = maxHeight * rect.y)
            .width(maxWidth * rect.width)
            .fillMaxHeight(rect.height),
        content = content,
    )
}

@Composable
private fun Badge(
    card: CtsCard,
    showHexagons: Boolean,
    modifier: Modifier = Modifier,
) {
    val badgeModifier = modifier
        .fillMaxSize(0.72f)
        .then(
            if (showHexagons) {
                Modifier
                    .clip(HexagonShape)
                    .background(Color(0xFF7D67EE))
                    .border(1.dp, Color(0xFFD7D0FF), HexagonShape)
            } else {
                Modifier
            },
        )
        .padding(horizontal = 7.dp, vertical = 4.dp)

    Box(modifier = badgeModifier, contentAlignment = Alignment.Center) {
        Text(
            text = listOf(card.badgePrimary, card.badgeSecondary)
                .filter { it.isNotBlank() }
                .joinToString("\n"),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoxScope.CardText(
    text: String,
    color: Color,
    weight: FontWeight,
    size: androidx.compose.ui.unit.TextUnit,
    maxLines: Int = 2,
) {
    Text(
        text = text,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        color = color,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * 1.06f,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ImageSubcardFrame(
    subcard: ImageSubcard,
    selected: Boolean,
    contentScale: ContentScale,
    onSelect: () -> Unit,
    onTransformChanged: (NormalizedRect) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds(),
    ) {
        val density = LocalDensity.current
        val frameWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val frameHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val transform = subcard.transform.clamped()
        val latestTransform by rememberUpdatedState(transform)

        Box(
            modifier = Modifier
                .offset(
                    x = maxWidth * transform.x,
                    y = maxHeight * transform.y,
                )
                .width(maxWidth * transform.width)
                .fillMaxHeight(transform.height)
                .then(if (selected) Modifier.border(1.5.dp, CtsPurple) else Modifier)
                .pointerInput(subcard.id, frameWidthPx, frameHeightPx) {
                    var working = latestTransform
                    detectDragGestures(
                        onDragStart = {
                            working = latestTransform
                            onSelect()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            working = working.moveBy(
                                dx = dragAmount.x / frameWidthPx,
                                dy = dragAmount.y / frameHeightPx,
                            )
                            onTransformChanged(working)
                        },
                    )
                }
                .clickable(onClick = onSelect),
        ) {
            ImageContent(subcard.source, contentScale)

            if (selected) {
                ResizeHandle(
                    corner = ResizeCorner.NorthWest,
                    alignment = Alignment.TopStart,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
                ResizeHandle(
                    corner = ResizeCorner.NorthEast,
                    alignment = Alignment.TopEnd,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
                ResizeHandle(
                    corner = ResizeCorner.SouthWest,
                    alignment = Alignment.BottomStart,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
                ResizeHandle(
                    corner = ResizeCorner.SouthEast,
                    alignment = Alignment.BottomEnd,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ResizeHandle(
    corner: ResizeCorner,
    alignment: Alignment,
    frameWidthPx: Float,
    frameHeightPx: Float,
    currentTransform: () -> NormalizedRect,
    onSelect: () -> Unit,
    onTransformChanged: (NormalizedRect) -> Unit,
) {
    Box(
        modifier = Modifier
            .align(alignment)
            .size(14.dp)
            .background(CtsPurple)
            .border(1.dp, Color.White)
            .zIndex(4f)
            .pointerInput(corner, frameWidthPx, frameHeightPx) {
                var working = currentTransform()
                detectDragGestures(
                    onDragStart = {
                        working = currentTransform()
                        onSelect()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        working = working.resizeFrom(
                            corner = corner,
                            dx = dragAmount.x / frameWidthPx,
                            dy = dragAmount.y / frameHeightPx,
                        )
                        onTransformChanged(working)
                    },
                )
            },
    )
}

@Composable
private fun BoxScope.ImageContent(source: String?, contentScale: ContentScale) {
    val bitmap by rememberSourceBitmap(source)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = "Card image",
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF474B48)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = Color(0xFFB9BDB8),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun rememberSourceBitmap(source: String?) = produceState<ImageBitmap?>(
    initialValue = null,
    key1 = source,
) {
    if (source.isNullOrBlank()) {
        value = null
        return@produceState
    }

    val context = LocalContext.current
    value = withContext(Dispatchers.IO) {
        runCatching {
            val stream = when {
                source.startsWith("http://", ignoreCase = true) ||
                    source.startsWith("https://", ignoreCase = true) -> URL(source).openStream()

                Uri.parse(source).scheme != null -> context.contentResolver.openInputStream(Uri.parse(source))
                else -> FileInputStream(File(source))
            }
            stream?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        }.getOrNull()
    }
}

private fun imageFrame(model: VisualModel): NormalizedRect = when (model) {
    VisualModel.Reference -> NormalizedRect(0.085f, 0.67f, 0.83f, 0.32f)
    VisualModel.Illustrated -> NormalizedRect(0.01f, 0.01f, 0.98f, 0.87f)
    VisualModel.Compact -> NormalizedRect(0.01f, 0.505f, 0.98f, 0.485f)
}

private fun NormalizedRect.moveBy(dx: Float, dy: Float): NormalizedRect =
    copy(x = x + dx, y = y + dy).clamped()

private fun NormalizedRect.resizeFrom(
    corner: ResizeCorner,
    dx: Float,
    dy: Float,
): NormalizedRect {
    val candidate = when (corner) {
        ResizeCorner.NorthWest -> copy(
            x = x + dx,
            y = y + dy,
            width = width - dx,
            height = height - dy,
        )

        ResizeCorner.NorthEast -> copy(
            y = y + dy,
            width = width + dx,
            height = height - dy,
        )

        ResizeCorner.SouthWest -> copy(
            x = x + dx,
            width = width - dx,
            height = height + dy,
        )

        ResizeCorner.SouthEast -> copy(
            width = width + dx,
            height = height + dy,
        )
    }
    return candidate.clamped()
}
