package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/** Shrinks badge text until every line is visible; never adds an ellipsis. */
@Composable
fun AutoFitBadgeText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 4,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val maxWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val maxHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)

        val chosenSize = remember(text, maxWidthPx, maxHeightPx, maxLines) {
            val candidates = generateSequence(10.0f) { previous ->
                (previous - 0.35f).takeIf { it >= 5.0f }
            }.toList()

            candidates.firstOrNull { size ->
                val result = measurer.measure(
                    text = AnnotatedString(text),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = size.sp,
                        lineHeight = (size * 1.02f).sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                    ),
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    maxLines = maxLines,
                    constraints = Constraints(
                        maxWidth = maxWidthPx,
                        maxHeight = maxHeightPx,
                    ),
                )
                !result.hasVisualOverflow && result.size.height <= maxHeightPx
            } ?: 5.0f
        }

        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = chosenSize.sp,
            lineHeight = (chosenSize * 1.02f).sp,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
}
