package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Innermost BoxScope overload used by the badge surface. Keeping this receiver explicit
 * prevents Kotlin from accidentally binding the outer card's BoxWithConstraintsScope.
 */
@Composable
internal fun BoxScope.AnimatedBadgeLine(
    text: String,
    progress: Float,
    centerY: Float,
    heightFraction: Float,
    fontSize: TextUnit,
) {
    if (text.isBlank() || progress <= 0f) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val eased = badgeLineEaseOutCubic(progress)
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val reverse = 1f - eased
        val baseX = -widthPx * 0.18f * reverse
        val baseY = -heightPx * 0.10f * reverse
        val trailStrength = (1f - abs(progress * 2f - 1f)).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .offset(y = maxHeight * (centerY - heightFraction / 2f))
                .width(maxWidth)
                .height(maxHeight * heightFraction),
            contentAlignment = Alignment.Center,
        ) {
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
}

private fun badgeLineEaseOutCubic(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    val inverse = 1f - t
    return 1f - inverse * inverse * inverse
}
