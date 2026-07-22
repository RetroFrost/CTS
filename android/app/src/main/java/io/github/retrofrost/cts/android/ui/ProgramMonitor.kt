package io.github.retrofrost.cts.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import io.github.retrofrost.cts.android.ui.theme.CtsPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL

private enum class ResizeCorner { NorthWest, NorthEast, SouthWest, SouthEast }

/** Point-up/point-down badge used by the supplied comparison video. */
private val ReferenceHexagonShape = GenericShape { size, _ ->
    moveTo(size.width * 0.5f, 0f)
    lineTo(size.width, size.height * 0.22f)
    lineTo(size.width, size.height * 0.78f)
    lineTo(size.width * 0.5f, size.height)
    lineTo(0f, size.height * 0.78f)
    lineTo(0f, size.height * 0.22f)
    close()
}

private val ImageFrame = NormalizedRect(0.008f, 0f, 0.984f, 0.807f)
private val TitleFrame = NormalizedRect(0.008f, 0.807f, 0.984f, 0.088f)
private val DescriptionFrame = NormalizedRect(0.008f, 0.895f, 0.984f, 0.101f)
private val BadgeFrame = NormalizedRect(0.245f, 0.063f, 0.51f, 0.263f)

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

    Surface(modifier = modifier, color = Color.Black, shadowElevation = 4.dp) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds(),
        ) {
            val cardWidth = maxWidth / 4
            if (showIntroCredits) ReferenceIntroCreditsPanel(cardWidth)

            placements.forEach { placement ->
                val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
                ReferenceParentCard(
                    card = card,
                    bodyReveal = placement.bodyReveal,
                    badgeVisible = placement.badgeVisible,
                    badgeSettle = placement.badgeSettle,
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

            ReferenceOutroOverlay(cardWidth, outroCover, outroContent)
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
    bodyReveal: Float,
    badgeVisible: Boolean,
    badgeSettle: Float,
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
                .width(fullCardWidth * reveal)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier
                    .width(fullCardWidth)
                    .fillMaxHeight(),
            ) {
                cardLayoutScope.ReferenceCardBody(
                    card = card,
                    selected = selected,
                    onSelect = onSelect,
                    onImageTransformChanged = onImageTransformChanged,
                )
            }
        }

        // Badges are a separate child layer. This lets the oversized entrance extend
        // above the card while the parent card and its image continue to move together.
        if (badgeVisible) {
            Frame(BadgeFrame) {
                ReferenceBadge(
                    card = card,
                    settleProgress = badgeSettle,
                    modifier = Modifier.fillMaxSize(),
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
    selected: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    val frames = CardContentLayout.frames(card)
    Frame(
        frames.image,
        Modifier.background(
            Brush.verticalGradient(
                0f to Color(0xFF138DDB),
                0.72f to Color(0xFF138DDB),
                1f to Color(0xFF0B74BE),
            ),
        ),
    ) {
        ImageSubcardFrame(
            card.imageSubcard,
            selected,
            ContentScale.Crop,
            onSelect,
            onImageTransformChanged,
        )
    }

    frames.title?.let { titleFrame ->
        Frame(titleFrame, Modifier.background(Color(0xFFF0F0F0))) {
            CardText(
                text = card.title,
                color = Color(0xFF101010),
                fontWeight = FontWeight.Black,
                fontSize = 8.4.sp,
                maxLines = 2,
            )
        }
    }

    frames.description?.let { descriptionFrame ->
        Frame(descriptionFrame, Modifier.background(Color(0xFF625F56))) {
            CardText(
                text = card.description,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 5.4.sp,
                maxLines = 3,
            )
        }
    }

    // Four black separators are visible in the reference at every stage of movement.
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .width(1.4.dp)
            .fillMaxHeight()
            .background(Color(0xFF11100C)),
    )
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .width(1.4.dp)
            .fillMaxHeight()
            .background(Color(0xFF11100C)),
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .height(1.4.dp)
            .width(maxWidth)
            .background(Color(0xFF11100C)),
    )
}

@Composable
private fun ReferenceBadge(
    card: CtsCard,
    settleProgress: Float,
    modifier: Modifier = Modifier,
) {
    val settle = settleProgress.coerceIn(0f, 1f)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val translation = with(density) { (-maxHeight * 0.42f * (1f - settle)).toPx() }
        val scale = 1.42f - 0.42f * settle
        val primarySize = (maxWidth.value * 0.22f).sp
        val secondarySize = (maxWidth.value * 0.105f).sp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = translation
                    transformOrigin = TransformOrigin.Center
                }
                .shadow(7.dp, ReferenceHexagonShape, clip = false)
                .clip(ReferenceHexagonShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFEB0909),
                            Color(0xFFE00000),
                            Color(0xFFD50000),
                        ),
                    ),
                )
                .border(0.8.dp, Color(0xFFFF4545), ReferenceHexagonShape),
            contentAlignment = Alignment.Center,
        ) {
            // A moving diagonal gloss is visible while each large badge settles.
            Canvas(Modifier.fillMaxSize()) {
                if (settle < 0.94f) {
                    val shineProgress = (settle / 0.94f).coerceIn(0f, 1f)
                    val shineX = -size.width * 0.30f + size.width * 1.65f * shineProgress
                    val shineAlpha = 0.34f * (1f - settle)
                    rotate(18f, pivot = Offset(shineX, size.height / 2f)) {
                        drawRect(
                            color = Color.White.copy(alpha = shineAlpha),
                            topLeft = Offset(shineX - size.width * 0.075f, -size.height * 0.20f),
                            size = Size(size.width * 0.15f, size.height * 1.40f),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = card.badgePrimary,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = primarySize,
                    lineHeight = primarySize * 0.98f,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.badgeSecondary.isNotBlank()) {
                    Text(
                        text = card.badgeSecondary,
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = secondarySize,
                        lineHeight = secondarySize * 1.02f,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
            ImageContent(subcard.source, contentScale)

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
