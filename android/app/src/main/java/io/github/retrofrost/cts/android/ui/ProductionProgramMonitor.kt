package io.github.retrofrost.cts.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL

/**
 * One renderer for the editor preview and future MP4 frames.
 *
 * `showEditorGuides` only adds selection chrome. Scene pixels, card geometry, image
 * transforms, reveal timing, and scrolling are identical when guides are disabled.
 */
@Composable
fun ProductionProgramMonitor(
    project: CtsProject,
    positionSeconds: Float,
    selectedCardId: String?,
    showEditorGuides: Boolean,
    onSelectCard: (String) -> Unit,
    onImageTransformChanged: (String, NormalizedRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val placements = TimelineEngine.placements(project, positionSeconds)
    val fadeAlpha = TimelineEngine.fadeAlpha(project, positionSeconds)

    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds(),
    ) {
        val cardWidth = maxWidth / project.model.visibleCards

        placements.forEach { placement ->
            val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
            val selected = showEditorGuides && selectedCardId == card.id

            ProductionParentCard(
                card = card,
                model = project.model,
                showHexagons = project.showHexagons,
                selected = selected,
                editorEnabled = showEditorGuides,
                onSelect = { onSelectCard(card.id) },
                onImageTransformChanged = { transform ->
                    onImageTransformChanged(card.id, transform)
                },
                modifier = Modifier
                    .offset(
                        x = cardWidth * placement.xInCards,
                        y = maxHeight * ((1f - placement.alpha) * 0.014f),
                    )
                    .width(cardWidth)
                    .height(maxHeight)
                    .alpha(placement.alpha * fadeAlpha)
                    .zIndex(placement.cardIndex.toFloat()),
            )
        }
    }
}

private enum class ProductionResizeCorner {
    NorthWest,
    NorthEast,
    SouthWest,
    SouthEast,
}

private val ProductionHexagon = GenericShape { size, _ ->
    moveTo(size.width * 0.22f, 0f)
    lineTo(size.width * 0.78f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.78f, size.height)
    lineTo(size.width * 0.22f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}

@Composable
private fun ProductionParentCard(
    card: CtsCard,
    model: VisualModel,
    showHexagons: Boolean,
    selected: Boolean,
    editorEnabled: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFF111216))
            .border(0.5.dp, Color(0xFF050506))
            .then(if (editorEnabled) Modifier.clickable(onClick = onSelect) else Modifier),
    ) {
        when (model) {
            VisualModel.Reference -> ProductionReferenceCard(
                card = card,
                showHexagons = showHexagons,
                selected = selected,
                editorEnabled = editorEnabled,
                onSelect = onSelect,
                onImageTransformChanged = onImageTransformChanged,
            )

            VisualModel.Illustrated -> ProductionIllustratedCard(
                card = card,
                showHexagons = showHexagons,
                selected = selected,
                editorEnabled = editorEnabled,
                onSelect = onSelect,
                onImageTransformChanged = onImageTransformChanged,
            )

            VisualModel.Compact -> ProductionCompactCard(
                card = card,
                showHexagons = showHexagons,
                selected = selected,
                editorEnabled = editorEnabled,
                onSelect = onSelect,
                onImageTransformChanged = onImageTransformChanged,
            )
        }

        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.ProductionReferenceCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    editorEnabled: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0f, 1f, 0.43f),
        modifier = Modifier.background(Color(0xFF101114)),
    ) {
        ProductionBadge(
            card = card,
            showHexagons = showHexagons,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.43f, 1f, 0.105f),
        modifier = Modifier.background(Color(0xFFF7F5EF)),
    ) {
        ProductionCardText(
            text = card.title,
            color = Color(0xFF181714),
            weight = FontWeight.Bold,
            size = 8.5.sp,
            maxLines = 2,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.535f, 1f, 0.135f),
        modifier = Modifier.background(Color(0xFFD7D2C8)),
    ) {
        ProductionCardText(
            text = card.description,
            color = Color(0xFF34312B),
            weight = FontWeight.Medium,
            size = 6.2.sp,
            maxLines = 4,
        )
    }

    ProductionSceneFrame(
        rect = productionImageFrame(VisualModel.Reference),
        modifier = Modifier.background(Color(0xFF747873)),
    ) {
        ProductionImageSubcard(
            subcard = card.imageSubcard,
            selected = selected,
            editorEnabled = editorEnabled,
            contentScale = ContentScale.Crop,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.ProductionIllustratedCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    editorEnabled: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    ProductionSceneFrame(
        rect = productionImageFrame(VisualModel.Illustrated),
        modifier = Modifier.background(
            Brush.verticalGradient(
                0f to Color(0xFF57D0E6),
                0.64f to Color(0xFF57D0E6),
                0.65f to Color(0xFFEBC57D),
                1f to Color(0xFFF2D69A),
            ),
        ),
    ) {
        ProductionImageSubcard(
            subcard = card.imageSubcard,
            selected = selected,
            editorEnabled = editorEnabled,
            contentScale = ContentScale.Fit,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.88f, 1f, 0.12f),
        modifier = Modifier.background(Color(0xFFF7F5EF)),
    ) {
        ProductionCardText(
            text = card.title,
            color = Color(0xFF181714),
            weight = FontWeight.Bold,
            size = 8.5.sp,
            maxLines = 2,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0.12f, 0.035f, 0.76f, 0.26f),
    ) {
        ProductionBadge(
            card = card,
            showHexagons = showHexagons,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun BoxWithConstraintsScope.ProductionCompactCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    editorEnabled: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0f, 1f, 0.39f),
        modifier = Modifier.background(Color(0xFF101114)),
    ) {
        ProductionBadge(
            card = card,
            showHexagons = showHexagons,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.39f, 1f, 0.115f),
        modifier = Modifier.background(Color(0xFFF7F5EF)),
    ) {
        ProductionCardText(
            text = card.title,
            color = Color(0xFF181714),
            weight = FontWeight.Bold,
            size = 8.sp,
            maxLines = 3,
        )
    }

    ProductionSceneFrame(
        rect = productionImageFrame(VisualModel.Compact),
        modifier = Modifier.background(Color(0xFF747873)),
    ) {
        ProductionImageSubcard(
            subcard = card.imageSubcard,
            selected = selected,
            editorEnabled = editorEnabled,
            contentScale = ContentScale.Crop,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }
}

/** Geometry must be applied before decoration so each background paints only its frame. */
@Composable
private fun BoxWithConstraintsScope.ProductionSceneFrame(
    rect: NormalizedRect,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .offset(
                x = maxWidth * rect.x,
                y = maxHeight * rect.y,
            )
            .width(maxWidth * rect.width)
            .height(maxHeight * rect.height)
            .then(modifier),
        content = content,
    )
}

@Composable
private fun BoxWithConstraintsScope.ProductionBadge(
    card: CtsCard,
    showHexagons: Boolean,
    modifier: Modifier = Modifier,
) {
    val badgeWidth = maxWidth * 0.66f
    val badgeHeight = maxHeight * 0.54f

    Box(
        modifier = modifier
            .width(badgeWidth)
            .height(badgeHeight)
            .then(
                if (showHexagons) {
                    Modifier
                        .clip(ProductionHexagon)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF8A70F2), Color(0xFF6D55D8)),
                            ),
                        )
                        .border(0.8.dp, Color(0xFFE4DEFF), ProductionHexagon)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = listOf(card.badgePrimary, card.badgeSecondary)
                .filter(String::isNotBlank)
                .joinToString("\n"),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 9.5.sp,
            lineHeight = 9.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BoxScope.ProductionCardText(
    text: String,
    color: Color,
    weight: FontWeight,
    size: TextUnit,
    maxLines: Int,
) {
    Text(
        text = text,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        color = color,
        fontWeight = weight,
        fontSize = size,
        lineHeight = size * 1.08f,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ProductionImageSubcard(
    subcard: ImageSubcard,
    selected: Boolean,
    editorEnabled: Boolean,
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
                .height(maxHeight * transform.height)
                .then(
                    if (selected) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (editorEnabled) {
                        Modifier
                            .pointerInput(subcard.id, frameWidthPx, frameHeightPx) {
                                var working = latestTransform
                                detectDragGestures(
                                    onDragStart = {
                                        working = latestTransform
                                        onSelect()
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        working = working.productionMoveBy(
                                            amount.x / frameWidthPx,
                                            amount.y / frameHeightPx,
                                        )
                                        onTransformChanged(working)
                                    },
                                )
                            }
                            .clickable(onClick = onSelect)
                    } else {
                        Modifier
                    },
                ),
        ) {
            ProductionImageContent(
                source = subcard.source,
                contentScale = contentScale,
            )

            if (selected) {
                ProductionResizeHandle(
                    corner = ProductionResizeCorner.NorthWest,
                    alignment = Alignment.TopStart,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
                ProductionResizeHandle(
                    corner = ProductionResizeCorner.NorthEast,
                    alignment = Alignment.TopEnd,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
                ProductionResizeHandle(
                    corner = ProductionResizeCorner.SouthWest,
                    alignment = Alignment.BottomStart,
                    frameWidthPx = frameWidthPx,
                    frameHeightPx = frameHeightPx,
                    currentTransform = { latestTransform },
                    onSelect = onSelect,
                    onTransformChanged = onTransformChanged,
                )
                ProductionResizeHandle(
                    corner = ProductionResizeCorner.SouthEast,
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
private fun BoxScope.ProductionResizeHandle(
    corner: ProductionResizeCorner,
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
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(1.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
            .zIndex(5f)
            .pointerInput(corner, frameWidthPx, frameHeightPx) {
                var working = currentTransform()
                detectDragGestures(
                    onDragStart = {
                        working = currentTransform()
                        onSelect()
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        working = working.productionResizeFrom(
                            corner = corner,
                            dx = amount.x / frameWidthPx,
                            dy = amount.y / frameHeightPx,
                        )
                        onTransformChanged(working)
                    },
                )
            },
    )
}

@Composable
private fun BoxScope.ProductionImageContent(
    source: String?,
    contentScale: ContentScale,
) {
    val bitmap by rememberProductionBitmap(source)

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
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF727772), Color(0xFF555A56)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = Color(0xFFD7DCD7),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun rememberProductionBitmap(source: String?): State<ImageBitmap?> {
    val context = LocalContext.current

    return produceState<ImageBitmap?>(initialValue = null, key1 = source) {
        if (source.isNullOrBlank()) {
            value = null
            return@produceState
        }

        value = withContext(Dispatchers.IO) {
            runCatching {
                val stream = when {
                    source.startsWith("http://", ignoreCase = true) ||
                        source.startsWith("https://", ignoreCase = true) -> URL(source).openStream()

                    Uri.parse(source).scheme != null -> {
                        context.contentResolver.openInputStream(Uri.parse(source))
                    }

                    else -> FileInputStream(File(source))
                }
                stream?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
    }
}

private fun productionImageFrame(model: VisualModel): NormalizedRect = when (model) {
    VisualModel.Reference -> NormalizedRect(0.06f, 0.685f, 0.88f, 0.295f)
    VisualModel.Illustrated -> NormalizedRect(0.01f, 0.01f, 0.98f, 0.87f)
    VisualModel.Compact -> NormalizedRect(0.015f, 0.51f, 0.97f, 0.475f)
}

private fun NormalizedRect.productionMoveBy(
    dx: Float,
    dy: Float,
): NormalizedRect = copy(x = x + dx, y = y + dy).clamped()

private fun NormalizedRect.productionResizeFrom(
    corner: ProductionResizeCorner,
    dx: Float,
    dy: Float,
): NormalizedRect {
    val candidate = when (corner) {
        ProductionResizeCorner.NorthWest -> copy(
            x = x + dx,
            y = y + dy,
            width = width - dx,
            height = height - dy,
        )

        ProductionResizeCorner.NorthEast -> copy(
            y = y + dy,
            width = width + dx,
            height = height - dy,
        )

        ProductionResizeCorner.SouthWest -> copy(
            x = x + dx,
            width = width - dx,
            height = height + dy,
        )

        ProductionResizeCorner.SouthEast -> copy(
            width = width + dx,
            height = height + dy,
        )
    }

    return candidate.clamped()
}
