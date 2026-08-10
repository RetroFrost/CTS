package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.retrofrost.cts.android.model.CreditsSettings

@Composable
internal fun BoxWithConstraintsScope.RelationshipsInfinityIntro(frame: Int) {
    val shapeFrame = frame.coerceAtMost(373)
    val opacity = when {
        frame < 34 -> 0f
        frame < 70 -> ((frame - 34) / 36f).coerceIn(0f, 1f)
        frame < 450 -> 1f
        else -> 1f - smoothStep((frame - 450) / 100f)
    }
    val yellow = relationshipLoopState(shapeFrame, yellow = true)
    val pink = relationshipLoopState(shapeFrame, yellow = false)
    val opening = sampleRelationshipOpening(shapeFrame)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (frame < 374) Modifier.background(Color(0xFF090909)) else Modifier)
            .zIndex(80f),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            fun loop(state: Triple<Float, Float, Float>, start: Float, colour: Color) {
                val (cx, cy, radius) = state
                val sx = size.width / 1920f
                val sy = size.height / 1080f
                val r = radius * minOf(sx, sy)
                drawArc(
                    color = Color(0xFFF4F2E3).copy(alpha = opacity),
                    startAngle = start + 46f,
                    sweepAngle = 268f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(cx * sx - r, cy * sy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                    style = Stroke(width = (16f + 0.055f * radius) * minOf(sx, sy), cap = StrokeCap.Round),
                )
                drawArc(
                    color = colour.copy(alpha = opacity),
                    startAngle = start + 46f,
                    sweepAngle = 268f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(cx * sx - r, cy * sy - r),
                    size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                    style = Stroke(width = (5f + 0.014f * radius) * minOf(sx, sy), cap = StrokeCap.Round),
                )
            }
            loop(yellow, opening, Color(0xFFC6E900))
            loop(pink, opening + 180f, Color(0xFFEE5B7F))
        }
        if (frame >= 240) {
            val first = "Infinite".take((((shapeFrame - 240) / 50f).coerceIn(0f, 1f) * 8).toInt())
            val second = "Comparison".take((((shapeFrame - 288) / 62f).coerceIn(0f, 1f) * 10).toInt())
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 126.dp)
                    .alpha(opacity),
            ) {
                Text(first, color = Color(0xFFF5F5F5), fontSize = 15.sp, fontWeight = FontWeight.Light)
                Text(second, color = Color(0xFFF5F5F5), fontSize = 15.sp, fontWeight = FontWeight.Light)
            }
        }
    }
}

@Composable
internal fun BoxWithConstraintsScope.RelationshipsDisclaimer(alpha: Float, cardWidth: Dp) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(cardWidth)
            .fillMaxHeight()
            .background(Color(0xFF121212).copy(alpha = 0.67f * alpha.coerceIn(0f, 1f)))
            .padding(horizontal = 7.dp)
            .zIndex(81f),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DISCLAIMER:", color = Color(0xFFE0111B), fontSize = 7.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(7.dp))
            Text(
                "This comparison video is based on public data, surveys, public comments and discussions. Values are approximate estimates and may vary.",
                color = Color(0xFFD2D2D2),
                fontSize = 5.5.sp,
                lineHeight = 7.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun BoxWithConstraintsScope.RelationshipsOutroOverlay(
    cardWidth: Dp,
    localFrame: Int,
    contentAlpha: Float,
    credits: CreditsSettings,
) {
    if (contentAlpha > 0f && localFrame >= 35) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(cardWidth * 3f)
                .fillMaxHeight()
                .background(Color(0xFF120A18))
                .alpha(contentAlpha.coerceIn(0f, 1f))
                .zIndex(101f),
            contentAlignment = Alignment.TopStart,
        ) {
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 38.dp)) {
                val question = "Which relationship type are you in right now?"
                val q = question.take((((localFrame - 42) / 80f).coerceIn(0f, 1f) * question.length).toInt())
                Text(q, color = Color.White, fontSize = 14.sp, lineHeight = 15.sp)
                val comment = "Comment below!"
                val c = comment.take((((localFrame - 142) / 40f).coerceIn(0f, 1f) * comment.length).toInt())
                Text(c, color = Color(0xFFEA7F1C), fontSize = 15.sp)
                Spacer(Modifier.height(55.dp))
                val subscribe = "SUBSCRIBE for more comparison videos."
                val s = subscribe.take((((localFrame - 206) / 94f).coerceIn(0f, 1f) * subscribe.length).toInt())
                Text(s, color = Color.White, fontSize = 17.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(28.dp))
                Text(
                    credits.endingHeading,
                    color = Color(0xFFD8D8D8),
                    fontSize = 8.sp,
                    lineHeight = 9.sp,
                )
                Text(
                    credits.endingDetails,
                    color = Color(0xFFBEBEBE),
                    fontSize = 6.sp,
                    lineHeight = 7.sp,
                )
            }
        }
    }
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun relationshipLoopState(frame: Int, yellow: Boolean): Triple<Float, Float, Float> {
    val keys = if (yellow) {
        arrayOf(
            floatArrayOf(34f, 987.6f, -142.2f, 478.8f), floatArrayOf(50f, 778.3f, 71.9f, 357.9f),
            floatArrayOf(70f, 677.3f, 286f, 276.1f), floatArrayOf(100f, 678.5f, 478.9f, 207.7f),
            floatArrayOf(120f, 708.9f, 523.2f, 181.4f), floatArrayOf(150f, 740.5f, 526.4f, 158.5f),
            floatArrayOf(180f, 752.2f, 527.3f, 150f), floatArrayOf(373f, 752.6f, 527.5f, 149.7f),
        )
    } else {
        arrayOf(
            floatArrayOf(34f, 997.6f, 1169f, 475.5f), floatArrayOf(50f, 1178.4f, 959.6f, 360.9f),
            floatArrayOf(70f, 1253.4f, 744.6f, 278.6f), floatArrayOf(100f, 1227.2f, 561.2f, 209.5f),
            floatArrayOf(120f, 1192.7f, 523.8f, 182.8f), floatArrayOf(150f, 1163.2f, 525f, 159.7f),
            floatArrayOf(180f, 1152.3f, 525.8f, 151.2f), floatArrayOf(373f, 1152.1f, 525.8f, 151.1f),
        )
    }
    return sampleRelationshipState(keys, frame)
}

private fun sampleRelationshipState(keys: Array<FloatArray>, frame: Int): Triple<Float, Float, Float> {
    if (frame <= keys.first()[0]) return Triple(keys.first()[1], keys.first()[2], keys.first()[3])
    if (frame >= keys.last()[0]) return Triple(keys.last()[1], keys.last()[2], keys.last()[3])
    val right = keys.indexOfFirst { frame <= it[0] }
    val a = keys[right - 1]
    val b = keys[right]
    val t = (frame - a[0]) / (b[0] - a[0])
    return Triple(a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t)
}

private fun sampleRelationshipOpening(frame: Int): Float {
    val keys = arrayOf(34f to 90f, 50f to 65f, 70f to 37.5f, 100f to 7.5f, 120f to -0.5f, 373f to -1f)
    if (frame <= keys.first().first) return keys.first().second
    if (frame >= keys.last().first) return keys.last().second
    val right = keys.indexOfFirst { frame <= it.first }
    val (f0, v0) = keys[right - 1]
    val (f1, v1) = keys[right]
    return v0 + (v1 - v0) * (frame - f0) / (f1 - f0)
}

@Composable
internal fun BoxWithConstraintsScope.ReferenceIntroCreditsPanel(
    cardWidth: Dp,
    credits: CreditsSettings,
) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .width(cardWidth)
            .fillMaxHeight()
            .background(Color(0xFF202020))
            .padding(horizontal = 12.dp, vertical = 14.dp)
            .zIndex(0f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "The values presented are average milestones and may vary.",
                color = Color.White,
                fontSize = 7.sp,
                lineHeight = 8.sp,
                textAlign = TextAlign.Center,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFBEBEBE)))
                Spacer(Modifier.height(14.dp))
                Text(credits.heading, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    credits.lines,
                    color = Color.White,
                    fontSize = 7.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                credits.footer,
                color = Color(0xFFC8C8C8),
                fontSize = 5.sp,
                lineHeight = 6.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun BoxWithConstraintsScope.ReferenceOutroOverlay(
    cardWidth: Dp,
    coverProgress: Float,
    contentAlpha: Float,
    credits: CreditsSettings,
) {
    val overlayWidth = cardWidth * 3f
    val overlayHeight = maxHeight
    if (coverProgress > 0f) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .width(overlayWidth)
                .height(overlayHeight * coverProgress.coerceIn(0f, 1f))
                .background(Color(0xFF111111))
                .zIndex(100f),
        )
    }
    if (contentAlpha > 0f) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(overlayWidth)
                .fillMaxHeight()
                .background(Color(0xFF111111))
                .alpha(contentAlpha.coerceIn(0f, 1f))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .zIndex(101f),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutroVideoBox(
                        "BEST VIDEO FOR YOU",
                        Modifier.weight(1f).height(overlayHeight * 0.36f),
                    )
                    OutroVideoBox(
                        "NEWEST VIDEO",
                        Modifier.weight(1f).height(overlayHeight * 0.36f),
                    )
                }
                Box(
                    modifier = Modifier
                        .width(overlayWidth * 0.36f)
                        .height(overlayHeight * 0.22f)
                        .background(Color(0xFF625F56), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${credits.endingHeading}\n\n${credits.endingDetails}",
                        color = Color.White,
                        fontSize = 6.sp,
                        lineHeight = 8.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutroVideoBox(label: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xFFE00000), RoundedCornerShape(8.dp))
            .padding(10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}
