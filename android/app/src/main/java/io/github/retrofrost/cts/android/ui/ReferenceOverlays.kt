package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
internal fun BoxWithConstraintsScope.ReferenceIntroCreditsPanel(cardWidth: Dp) {
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
                Text("Credits", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Lead Research & Sourcing\nIndependent Fact Check\nLead Graphic Designer\nEdit & Post-Production\nThumbnail Designer\nVideo Idea & Quality Check",
                    color = Color.White,
                    fontSize = 7.sp,
                    lineHeight = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "DISCLAIMER\nTHIS VIDEO IS BASED ON COMMUNITY DISCUSSIONS AND RELEVANT SOURCES.",
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
                        "Video Made By\n\nLead Research & Sourcing     Edit & Post-Production\nIndependent Fact Check       Thumbnail Designer\nLead Graphic Designer        Video Idea & Quality Check",
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
