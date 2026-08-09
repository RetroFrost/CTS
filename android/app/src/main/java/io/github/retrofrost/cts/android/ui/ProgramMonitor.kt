package io.github.retrofrost.cts.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.render.ReferenceBadgePainter
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import io.github.retrofrost.cts.android.ui.theme.CtsPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL
import kotlin.math.max
import kotlin.math.min

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
    val showIntroCredits = TimelineEngine.introCreditsVisible(project, positionSeconds)
    val outroCover = TimelineEngine.outroCoverProgress(project, positionSeconds)
    val outroContent = TimelineEngine.outroContentAlpha(project, positionSeconds)
    val relationshipsFrame = TimelineEngine.relationshipsSourceFrame(project, positionSeconds)
    val disclaimerAlpha = TimelineEngine.relationshipsDisclaimerAlpha(project, positionSeconds)
    val relationshipsOutroFrame = TimelineEngine.relationshipsOutroLocalFrame(project, positionSeconds)

    Surface(modifier = modifier, color = Color.Black, shadowElevation = 4.dp) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds(),
        ) {
            val cardWidth = maxWidth / 4
            if (showIntroCredits) ReferenceIntroCreditsPanel(cardWidth)
            if (project.model == VisualModel.Relationships && project.showIntro &&
                relationshipsFrame in 1 until TimelineEngine.RELATIONSHIPS_INTRO_OVERLAY_END_FRAME
            ) {
                RelationshipsInfinityIntro(relationshipsFrame)
            }
            if (disclaimerAlpha > 0f) RelationshipsDisclaimer(disclaimerAlpha, cardWidth)

            placements.forEach { placement ->
                val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
                ReferenceParentCard(
                    card = card,
                    model = project.model,
                    bodyReveal = placement.bodyReveal,
                    artworkReveal = placement.artworkReveal,
                    titleReveal = placement.titleReveal,
                    descriptionReveal = placement.descriptionReveal,
                    badgeVisible = project.showHexagons && placement.badgeVisible,
                    placement = placement,
                    selected = selectedCardId == card.id,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { onImageTransformChanged(card.id, it) },
                    modifier = Modifier
                        .offset(x = cardWidth * placement.xInCards)
                        .width(cardWidth)
                        .fillMaxHeight()
                        .zIndex(placement.cardIndex.toFloat() + 1f),
                )
            }

            if (project.model == VisualModel.Relationships) {
                RelationshipsOutroOverlay(cardWidth, relationshipsOutroFrame, outroContent)
            } else {
                ReferenceOutroOverlay(cardWidth, outroCover, outroContent)
            }
            if (fadeAlpha < 0.999f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 1f - fadeAlpha))
                        .zIndex(200f),
                )
            }
        }
    }
}

@Composable
private fun ReferenceParentCard(
    card: CtsCard,
    model: VisualModel,
    bodyReveal: Float,
    artworkReveal: Float,
    titleReveal: Float,
    descriptionReveal: Float,
    badgeVisible: Boolean,
    placement: io.github.retrofrost.cts.android.timeline.CardPlacement,
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .background(Color.Black)
            .clickable(onClick = onSelect),
    ) {
        val cardLayoutScope = this
        val fullCardWidth = maxWidth
        val reveal = bodyReveal.coerceIn(0f, 1f)

        // The card itself is uncovered from left to right. Its internal geometry never
        // stretches, so text, artwork, and dividers remain exactly where they settle.
        Box(
            modifier = Modifier
                .width(if (model == VisualModel.Relationships) fullCardWidth else fullCardWidth * reveal)
                .fillMaxHeight()
                .alpha(if (model == VisualModel.Relationships) reveal else 1f)
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .width(fullCardWidth)
                    .fillMaxHeight(),
            ) {
                cardLayoutScope.ReferenceCardBody(
                    card = card,
                    model = model,
                    artworkReveal = artworkReveal,
                    titleReveal = titleReveal,
                    descriptionReveal = descriptionReveal,
                    selected = selected,
                    onSelect = onSelect,
                    onImageTransformChanged = onImageTransformChanged,
                )
            }
        }

        // Badges are a separate child layer. This lets the oversized entrance extend
        // above the card while the parent card and its image continue to move together.
        if (badgeVisible) {
            Canvas(Modifier.fillMaxSize()) {
                ReferenceBadgePainter.draw(
                    canvas = drawContext.canvas.nativeCanvas,
                    card = card,
                    model = model,
                    placement = placement,
                    cardLeft = 0f,
                    cardWidth = size.width,
                    frameHeight = size.height,
                )
            }
        }

        if (selected && reveal > 0.98f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, CtsPurple),
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.ReferenceCardBody(
    card: CtsCard,
    model: VisualModel,
    artworkReveal: Float,
    titleReveal: Float,
    descriptionReveal: Float,
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    val frames = CardContentLayout.frames(model, card)
    Frame(
        frames.image,
        Modifier.background(if (model == VisualModel.Relationships) Color(0xFF1F1F1F) else Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(artworkReveal.coerceIn(0f, 1f))
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        if (model == VisualModel.Relationships) {
                            listOf(Color(0xFF0069D3), Color(0xFF0058B5))
                        } else {
                            listOf(Color(0xFF138DDB), Color(0xFF0B74BE))
                        },
                    ),
                ),
        ) {
            ImageSubcardFrame(
                card.imageSubcard,
                selected,
                onSelect,
                onImageTransformChanged,
            )
        }
    }

    frames.title?.let { titleFrame ->
        Frame(
            titleFrame,
            Modifier
                .background(if (model == VisualModel.Relationships) Color(0xFFF5F5F3) else Color(0xFFF0F0F0))
                .alpha(titleReveal.coerceIn(0f, 1f)),
        ) {
            CardText(
                text = card.title,
                color = Color(0xFF101010),
                fontWeight = if (model == VisualModel.Relationships) FontWeight.Normal else FontWeight.Black,
                fontSize = if (model == VisualModel.Relationships) 12.sp else 8.4.sp,
                maxLines = if (model == VisualModel.Relationships) 1 else 2,
            )
        }
    }

    frames.description?.let { descriptionFrame ->
        Frame(
            descriptionFrame,
            Modifier
                .background(if (model == VisualModel.Relationships) Color(0xFF2F2F2F) else Color(0xFF625F56))
                .alpha(descriptionReveal.coerceIn(0f, 1f)),
        ) {
            CardText(
                text = card.description,
                color = Color.White,
                fontWeight = if (model == VisualModel.Relationships) FontWeight.Normal else FontWeight.SemiBold,
                fontSize = if (model == VisualModel.Relationships) 6.5.sp else 5.4.sp,
                maxLines = if (model == VisualModel.Relationships) 4 else 3,
            )
        }
    }

    if (model == VisualModel.Relationships) {
        Frame(
            CardContentLayout.relationshipsRule(),
            Modifier
                .background(Color(0xFFEA7F1C))
                .alpha(descriptionReveal.coerceIn(0f, 1f)),
        )
    }
    Frame(CardContentLayout.bottomRule(), Modifier.background(Color(0xFF11100C)))
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
            .height(maxHeight * rect.height),
        content = content,
    )
}

@Composable
private fun BoxScope.CardText(
    text: String,
    color: Color,
    fontWeight: FontWeight,
    fontSize: TextUnit,
    maxLines: Int,
) {
    Text(
        text = text,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = fontSize * 1.04f,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ImageSubcardFrame(
    subcard: ImageSubcard,
    selected: Boolean,
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
                .offset(x = maxWidth * transform.x, y = maxHeight * transform.y)
                .width(maxWidth * transform.width)
                .height(maxHeight * transform.height)
                .then(if (selected) Modifier.border(1.5.dp, CtsPurple) else Modifier)
                .pointerInput(subcard.id, frameWidthPx, frameHeightPx) {
                    var working = latestTransform
                    detectDragGestures(
                        onDragStart = {
                            working = latestTransform
                            onSelect()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            working = working.moveBy(
                                amount.x / frameWidthPx,
                                amount.y / frameHeightPx,
                            )
                            onTransformChanged(working)
                        },
                    )
                }
                .clickable(onClick = onSelect),
        ) {
            ImageContent(subcard)

            if (selected) {
                ResizeHandle(
                    ResizeCorner.NorthWest,
                    Alignment.TopStart,
                    frameWidthPx,
                    frameHeightPx,
                    { latestTransform },
                    onSelect,
                    onTransformChanged,
                )
                ResizeHandle(
                    ResizeCorner.NorthEast,
                    Alignment.TopEnd,
                    frameWidthPx,
                    frameHeightPx,
                    { latestTransform },
                    onSelect,
                    onTransformChanged,
                )
                ResizeHandle(
                    ResizeCorner.SouthWest,
                    Alignment.BottomStart,
                    frameWidthPx,
                    frameHeightPx,
                    { latestTransform },
                    onSelect,
                    onTransformChanged,
                )
                ResizeHandle(
                    ResizeCorner.SouthEast,
                    Alignment.BottomEnd,
                    frameWidthPx,
                    frameHeightPx,
                    { latestTransform },
                    onSelect,
                    onTransformChanged,
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
                        working = working.resizeFrom(
                            corner,
                            amount.x / frameWidthPx,
                            amount.y / frameHeightPx,
                        )
                        onTransformChanged(working)
                    },
                )
            },
    )
}

@Composable
private fun BoxScope.ImageContent(subcard: ImageSubcard) {
    val bitmap by rememberSourceBitmap(subcard.source)
    if (bitmap != null) {
        val focusX = subcard.cropFocusX.coerceIn(0f, 1f)
        val focusY = subcard.cropFocusY.coerceIn(0f, 1f)
        val zoom = subcard.cropZoom.coerceIn(1f, 3f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val image = bitmap!!
            val destinationAspect = size.width / size.height.coerceAtLeast(1f)
            val sourceAspect = image.width / image.height.toFloat().coerceAtLeast(1f)
            val baseCropWidth: Float
            val baseCropHeight: Float
            if (sourceAspect >= destinationAspect) {
                baseCropHeight = image.height.toFloat()
                baseCropWidth = baseCropHeight * destinationAspect
            } else {
                baseCropWidth = image.width.toFloat()
                baseCropHeight = baseCropWidth / destinationAspect.coerceAtLeast(0.0001f)
            }
            val cropWidth = (baseCropWidth / zoom).coerceAtLeast(1f)
            val cropHeight = (baseCropHeight / zoom).coerceAtLeast(1f)
            val sourceLeft = (image.width * focusX - cropWidth / 2f)
                .coerceIn(0f, max(0f, image.width - cropWidth))
            val sourceTop = (image.height * focusY - cropHeight / 2f)
                .coerceIn(0f, max(0f, image.height - cropHeight))
            drawImage(
                image = image,
                srcOffset = IntOffset(sourceLeft.toInt(), sourceTop.toInt()),
                srcSize = IntSize(
                    min(image.width, cropWidth.toInt().coerceAtLeast(1)),
                    min(image.height, cropHeight.toInt().coerceAtLeast(1)),
                ),
                dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF138DDB), Color(0xFF0B74BE)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun rememberSourceBitmap(source: String?): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, key1 = source) {
        if (source.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val stream = when {
                    source.startsWith("http://", true) || source.startsWith("https://", true) -> {
                        URL(source).openStream()
                    }
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
