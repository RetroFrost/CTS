package io.github.retrofrost.cts.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import io.github.retrofrost.cts.android.ui.theme.CtsPurple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.URL
import kotlin.math.PI
import kotlin.math.sin

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

/** Measured from the first settled badge in the supplied 1920x1080 video. */
private val BadgeFrame = NormalizedRect(0.164f, 0.009f, 0.726f, 0.350f)

private data class BadgeMotion(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

private data class BadgeMotionKeyframe(
    val at: Float,
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

private val BadgeMotionKeyframes = listOf(
    // The first visible source frame is only a red sliver at the upper-left edge.
    BadgeMotionKeyframe(0.00f, 0.08f, 1.18f, -0.72f, -0.71f),
    // Around 1.0s the badge is still narrow/tall and mostly above its final position.
    BadgeMotionKeyframe(0.30f, 0.76f, 1.12f, -0.32f, -0.10f),
    BadgeMotionKeyframe(0.58f, 0.98f, 1.02f, -0.12f, -0.05f),
    // Tiny size overshoot before the source settles.
    BadgeMotionKeyframe(0.76f, 1.01f, 1.025f, -0.045f, -0.035f),
    BadgeMotionKeyframe(0.90f, 1.006f, 0.985f, -0.012f, 0.00f),
    BadgeMotionKeyframe(1.00f, 1.00f, 1.00f, 0.00f, 0.00f),
)

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

    Surface(modifier = modifier, color = Color.Black, shadowElevation = 4.dp) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clipToBounds(),
        ) {
            val cardWidth = maxWidth / 4
            placements.forEach { placement ->
                val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
                ReferenceParentCard(
                    card = card,
                    bodyReveal = placement.bodyReveal,
                    badgeVisible = placement.badgeVisible,
                    badgeProgress = placement.badgeProgress,
                    selected = selectedCardId == card.id,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { onImageTransformChanged(card.id, it) },
                    modifier = Modifier
                        .offset(x = cardWidth * placement.xInCards)
                        .width(cardWidth)
                        .fillMaxHeight()
                        .alpha(fadeAlpha)
                        .zIndex(placement.cardIndex.toFloat()),
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
    badgeProgress: Float,
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

        // The card is uncovered from left to right without stretching its contents.
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

        // The badge is independent of the card wipe, exactly as in the source video.
        if (badgeVisible) {
            Frame(BadgeFrame) {
                ReferenceBadge(
                    card = card,
                    animationProgress = badgeProgress,
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
    Frame(
        ImageFrame,
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

    Frame(TitleFrame, Modifier.background(Color(0xFFF0F0F0))) {
        CardText(
            text = card.title,
            color = Color(0xFF101010),
            fontWeight = FontWeight.Black,
            fontSize = 8.4.sp,
            maxLines = 2,
        )
    }

    Frame(DescriptionFrame, Modifier.background(Color(0xFF625F56))) {
        CardText(
            text = card.description,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 5.4.sp,
            maxLines = 3,
        )
    }

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
    animationProgress: Float,
    modifier: Modifier = Modifier,
) {
    val phase = animationProgress.coerceIn(0f, 1f)
    val motion = badgeMotionAt(phase)
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val primarySize = (maxWidth.value * 0.225f).sp
        val secondarySize = (maxWidth.value * 0.105f).sp
        val labelLines = splitBadgeLabel(card.badgeSecondary)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = motion.scaleX
                    scaleY = motion.scaleY
                    translationX = widthPx * motion.translationX
                    translationY = heightPx * motion.translationY
                    transformOrigin = TransformOrigin.Center
                }
                .shadow(8.dp, ReferenceHexagonShape, clip = false)
                .clip(ReferenceHexagonShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFF31518),
                            Color(0xFFE60008),
                            Color(0xFFD60008),
                        ),
                        center = Offset(widthPx * 0.55f, heightPx * 0.42f),
                        radius = widthPx * 0.78f,
                    ),
                )
                .border(0.8.dp, Color(0xFFB90008), ReferenceHexagonShape),
        ) {
            ReferenceSheen(phase)

            AnimatedBadgeLine(
                text = card.badgePrimary,
                progress = trackProgress(phase, 0.33f, 0.48f),
                centerY = 0.31f,
                heightFraction = 0.25f,
                fontSize = primarySize,
            )

            when (labelLines.size) {
                0 -> Unit
                1 -> AnimatedBadgeLine(
                    text = labelLines[0],
                    progress = trackProgress(phase, 0.46f, 0.64f),
                    centerY = 0.66f,
                    heightFraction = 0.18f,
                    fontSize = secondarySize,
                )
                else -> {
                    AnimatedBadgeLine(
                        text = labelLines[0],
                        progress = trackProgress(phase, 0.41f, 0.57f),
                        centerY = 0.58f,
                        heightFraction = 0.16f,
                        fontSize = secondarySize,
                    )
                    AnimatedBadgeLine(
                        text = labelLines[1],
                        progress = trackProgress(phase, 0.57f, 0.73f),
                        centerY = 0.75f,
                        heightFraction = 0.16f,
                        fontSize = secondarySize,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.ReferenceSheen(phase: Float) {
    val progress = trackProgress(phase, 0.79f, 1.00f)
    if (progress <= 0f || progress >= 1f) return

    Canvas(Modifier.fillMaxSize()) {
        val fade = sin(progress * PI).toFloat().coerceIn(0f, 1f)
        val centerX = -size.width * 0.38f + size.width * 1.78f * progress
        val bandWidth = size.width * 0.23f
        rotate(18f, pivot = Offset(centerX, size.height / 2f)) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.08f * fade),
                        Color.White.copy(alpha = 0.48f * fade),
                        Color.White.copy(alpha = 0.08f * fade),
                        Color.Transparent,
                    ),
                    startX = centerX - bandWidth,
                    endX = centerX + bandWidth,
                ),
                topLeft = Offset(centerX - bandWidth, -size.height * 0.28f),
                size = Size(bandWidth * 2f, size.height * 1.56f),
            )
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.AnimatedBadgeLine(
    text: String,
    progress: Float,
    centerY: Float,
    heightFraction: Float,
    fontSize: TextUnit,
) {
    if (text.isBlank() || progress <= 0f) return

    val eased = easeOutCubic(progress)
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    val heightPx = with(density) { maxHeight.toPx() }
    val reverse = 1f - eased
    val baseX = -widthPx * 0.18f * reverse
    val baseY = -heightPx * 0.10f * reverse
    val trailStrength = (1f - kotlin.math.abs(progress * 2f - 1f)).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .offset(y = maxHeight * (centerY - heightFraction / 2f))
            .width(maxWidth)
            .height(maxHeight * heightFraction),
        contentAlignment = Alignment.Center,
    ) {
        // The source uses repeated blurred text ghosts, not a simple opacity fade.
        for (trail in 3 downTo 1) {
            Text(
                text = text,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = baseX * (1f + trail * 0.28f)
                        translationY = baseY * (1f + trail * 0.24f)
                        alpha = 0.17f * trailStrength * (4 - trail)
                    }
                    .blur((1.2f + trail * 0.8f).dp),
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = fontSize,
                lineHeight = fontSize,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = text,
            modifier = Modifier.graphicsLayer {
                translationX = baseX
                translationY = baseY
                alpha = eased
            },
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = fontSize,
            lineHeight = fontSize,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.48f),
                    offset = Offset(widthPx * 0.012f, heightPx * 0.012f),
                    blurRadius = widthPx * 0.018f,
                ),
            ),
        )
    }
}

private fun badgeMotionAt(progress: Float): BadgeMotion {
    val p = progress.coerceIn(0f, 1f)
    val upperIndex = BadgeMotionKeyframes.indexOfFirst { p <= it.at }
    if (upperIndex <= 0) {
        val first = BadgeMotionKeyframes.first()
        return BadgeMotion(
            first.scaleX,
            first.scaleY,
            first.translationX,
            first.translationY,
        )
    }
    if (upperIndex < 0) {
        val last = BadgeMotionKeyframes.last()
        return BadgeMotion(last.scaleX, last.scaleY, last.translationX, last.translationY)
    }

    val lower = BadgeMotionKeyframes[upperIndex - 1]
    val upper = BadgeMotionKeyframes[upperIndex]
    val local = smoothStep((p - lower.at) / (upper.at - lower.at))
    return BadgeMotion(
        scaleX = lerp(lower.scaleX, upper.scaleX, local),
        scaleY = lerp(lower.scaleY, upper.scaleY, local),
        translationX = lerp(lower.translationX, upper.translationX, local),
        translationY = lerp(lower.translationY, upper.translationY, local),
    )
}

private fun splitBadgeLabel(value: String): List<String> {
    val words = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    if (words.size == 1) return words
    if (words.size == 2) return words

    var bestIndex = 1
    var bestDifference = Int.MAX_VALUE
    for (index in 1 until words.size) {
        val leftLength = words.take(index).sumOf { it.length } + index - 1
        val rightLength = words.drop(index).sumOf { it.length } + words.size - index - 1
        val difference = kotlin.math.abs(leftLength - rightLength)
        if (difference < bestDifference) {
            bestDifference = difference
            bestIndex = index
        }
    }
    return listOf(
        words.take(bestIndex).joinToString(" "),
        words.drop(bestIndex).joinToString(" "),
    )
}

private fun trackProgress(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

private fun easeOutCubic(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    val inverse = 1f - t
    return 1f - inverse * inverse * inverse
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerp(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount

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
