#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    source = read(path)
    if source.count(old) != 1:
        raise RuntimeError(f"Expected one match in {path}, found {source.count(old)}")
    write(path, source.replace(old, new, 1))


def regex_once(path: str, pattern: str, replacement: str) -> None:
    source = read(path)
    updated, count = re.subn(pattern, replacement, source, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"Expected one regex match in {path}, found {count}")
    write(path, updated)


# Shared timing measured from the supplied 4:39 reference video.
spec_path = ROOT / "shared/cts_contract.json"
spec = json.loads(spec_path.read_text(encoding="utf-8"))
timing = spec["timing"]
timing.update(
    {
        "end_hold_seconds": 0.25,
        "outro_cover_seconds": 0.32,
        "outro_content_delay_seconds": 0.18,
        "outro_hold_seconds": 4.25,
        "fade_seconds": 1.2,
    }
)
spec_path.write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

# Extend the generated contract with the three-stage outro tail.
generator = "tools/sync_shared_contract.py"
replace_once(
    generator,
    '    "END_HOLD_SECONDS": "end_hold_seconds",\n    "FADE_SECONDS": "fade_seconds",',
    '    "END_HOLD_SECONDS": "end_hold_seconds",\n    "OUTRO_COVER_SECONDS": "outro_cover_seconds",\n    "OUTRO_CONTENT_DELAY_SECONDS": "outro_content_delay_seconds",\n    "OUTRO_HOLD_SECONDS": "outro_hold_seconds",\n    "FADE_SECONDS": "fade_seconds",',
)
replace_once(
    generator,
    'END_HOLD_SECONDS = {float(timing["end_hold_seconds"])}\nFADE_SECONDS = {float(timing["fade_seconds"])}',
    'END_HOLD_SECONDS = {float(timing["end_hold_seconds"])}\nOUTRO_COVER_SECONDS = {float(timing["outro_cover_seconds"])}\nOUTRO_CONTENT_DELAY_SECONDS = {float(timing["outro_content_delay_seconds"])}\nOUTRO_HOLD_SECONDS = {float(timing["outro_hold_seconds"])}\nFADE_SECONDS = {float(timing["fade_seconds"])}',
)
replace_once(
    generator,
    '    return reveal + scroll + END_HOLD_SECONDS + FADE_SECONDS',
    '    return (\n        reveal\n        + scroll\n        + END_HOLD_SECONDS\n        + OUTRO_COVER_SECONDS\n        + OUTRO_CONTENT_DELAY_SECONDS\n        + OUTRO_HOLD_SECONDS\n        + FADE_SECONDS\n    )',
)
replace_once(
    generator,
    '    fixed_tail = END_HOLD_SECONDS + FADE_SECONDS',
    '    fixed_tail = (\n        END_HOLD_SECONDS\n        + OUTRO_COVER_SECONDS\n        + OUTRO_CONTENT_DELAY_SECONDS\n        + OUTRO_HOLD_SECONDS\n        + FADE_SECONDS\n    )',
)
replace_once(
    generator,
    '    const val END_HOLD_SECONDS = {float(timing["end_hold_seconds"])}f\n    const val FADE_SECONDS = {float(timing["fade_seconds"])}f',
    '    const val END_HOLD_SECONDS = {float(timing["end_hold_seconds"])}f\n    const val OUTRO_COVER_SECONDS = {float(timing["outro_cover_seconds"])}f\n    const val OUTRO_CONTENT_DELAY_SECONDS = {float(timing["outro_content_delay_seconds"])}f\n    const val OUTRO_HOLD_SECONDS = {float(timing["outro_hold_seconds"])}f\n    const val FADE_SECONDS = {float(timing["fade_seconds"])}f',
)
subprocess.run([sys.executable, str(ROOT / generator)], cwd=ROOT, check=True)

# Android timing engine: intro cards slide from the preceding slot, conveyor cards are complete,
# incoming badges begin shortly before arrival, and the final four remain for the outro wipe.
write(
    "android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt",
    r'''package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.shared.SharedContract
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val REVEAL_SECONDS = SharedContract.REVEAL_SECONDS
const val SCROLL_SECONDS = SharedContract.SCROLL_SECONDS
const val END_HOLD_SECONDS = SharedContract.END_HOLD_SECONDS
const val OUTRO_COVER_SECONDS = SharedContract.OUTRO_COVER_SECONDS
const val OUTRO_CONTENT_DELAY_SECONDS = SharedContract.OUTRO_CONTENT_DELAY_SECONDS
const val OUTRO_HOLD_SECONDS = SharedContract.OUTRO_HOLD_SECONDS
const val FADE_SECONDS = SharedContract.FADE_SECONDS
const val BODY_WIPE_SECONDS = SharedContract.BODY_WIPE_SECONDS
const val BADGE_DELAY_SECONDS = SharedContract.BADGE_DELAY_SECONDS
const val BADGE_SETTLE_SECONDS = SharedContract.BADGE_SETTLE_SECONDS
const val INTRO_TAIL_HOLD_SECONDS = SharedContract.INTRO_TAIL_HOLD_SECONDS
const val MIN_SCROLL_STEP_SECONDS = SharedContract.MIN_SCROLL_STEP_SECONDS

/** One card in the reference timeline. The body is always complete; xInCards performs motion. */
data class CardPlacement(
    val cardIndex: Int,
    val xInCards: Float,
    val bodyReveal: Float,
    val badgeVisible: Boolean,
    val badgeSettle: Float,
)

private data class TimelineParts(
    val introSeconds: Float,
    val scrollSteps: Int,
    val automaticScrollSeconds: Float,
    val fixedTailSeconds: Float,
)

object TimelineEngine {
    private fun timelineParts(project: CtsProject): TimelineParts {
        val cardCount = project.cards.size
        if (cardCount <= 0) return TimelineParts(0f, 0, 0f, 0f)
        val visible = SharedContract.VISIBLE_CARDS
        val intro = min(cardCount, visible) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val scrollSteps = max(0, cardCount - visible)
        val automaticScroll = scrollSteps * SCROLL_SECONDS
        val fixedTail = END_HOLD_SECONDS + OUTRO_COVER_SECONDS +
            OUTRO_CONTENT_DELAY_SECONDS + OUTRO_HOLD_SECONDS + FADE_SECONDS
        return TimelineParts(intro, scrollSteps, automaticScroll, fixedTail)
    }

    fun automaticDuration(project: CtsProject): Float {
        val parts = timelineParts(project)
        return parts.introSeconds + parts.automaticScrollSeconds + parts.fixedTailSeconds
    }

    fun duration(project: CtsProject): Float {
        val parts = timelineParts(project)
        val automatic = automaticDuration(project)
        val custom = DurationRuntime.resolve(project.customDurationSeconds) ?: return automatic
        if (parts.scrollSteps <= 0) return automatic
        val minimum = parts.introSeconds +
            parts.scrollSteps * MIN_SCROLL_STEP_SECONDS +
            parts.fixedTailSeconds
        return max(minimum, custom)
    }

    private fun chosenScrollDuration(project: CtsProject, parts: TimelineParts): Float {
        if (parts.scrollSteps <= 0) return 0f
        if (DurationRuntime.resolve(project.customDurationSeconds) == null) {
            return parts.automaticScrollSeconds
        }
        return max(
            parts.scrollSteps * MIN_SCROLL_STEP_SECONDS,
            duration(project) - parts.introSeconds - parts.fixedTailSeconds,
        )
    }

    fun secondsPerCard(project: CtsProject): Float {
        val parts = timelineParts(project)
        if (parts.scrollSteps <= 0) return 0f
        return chosenScrollDuration(project, parts) / parts.scrollSteps
    }

    fun modelTime(project: CtsProject, outputTimeSeconds: Float): Float {
        val output = outputTimeSeconds.coerceAtLeast(0f)
        val parts = timelineParts(project)
        if (
            DurationRuntime.resolve(project.customDurationSeconds) == null ||
            parts.scrollSteps <= 0 ||
            parts.automaticScrollSeconds <= 0f
        ) return output
        if (output <= parts.introSeconds) return output

        val chosenScroll = chosenScrollDuration(project, parts)
        if (output < parts.introSeconds + chosenScroll) {
            val progress = (output - parts.introSeconds) / chosenScroll.coerceAtLeast(0.001f)
            return parts.introSeconds + progress * parts.automaticScrollSeconds
        }
        return parts.introSeconds + parts.automaticScrollSeconds +
            (output - parts.introSeconds - chosenScroll)
    }

    private fun outputTimeForModelTime(project: CtsProject, modelTimeSeconds: Float): Float {
        val modelTime = modelTimeSeconds.coerceAtLeast(0f)
        val parts = timelineParts(project)
        if (
            DurationRuntime.resolve(project.customDurationSeconds) == null ||
            parts.scrollSteps <= 0 ||
            parts.automaticScrollSeconds <= 0f
        ) return modelTime
        if (modelTime <= parts.introSeconds) return modelTime

        val chosenScroll = chosenScrollDuration(project, parts)
        if (modelTime < parts.introSeconds + parts.automaticScrollSeconds) {
            val progress = (modelTime - parts.introSeconds) /
                parts.automaticScrollSeconds.coerceAtLeast(0.001f)
            return parts.introSeconds + progress * chosenScroll
        }
        return parts.introSeconds + chosenScroll +
            (modelTime - parts.introSeconds - parts.automaticScrollSeconds)
    }

    private fun scrollEnd(project: CtsProject): Float {
        val parts = timelineParts(project)
        return parts.introSeconds + parts.automaticScrollSeconds
    }

    private fun outroStart(project: CtsProject): Float = scrollEnd(project) + END_HOLD_SECONDS

    fun introCreditsVisible(project: CtsProject, outputTimeSeconds: Float): Boolean {
        if (project.cards.isEmpty()) return false
        return modelTime(project, outputTimeSeconds) < timelineParts(project).introSeconds
    }

    fun outroCoverProgress(project: CtsProject, outputTimeSeconds: Float): Float {
        val elapsed = modelTime(project, outputTimeSeconds) - outroStart(project)
        return materialEase(elapsed / OUTRO_COVER_SECONDS.coerceAtLeast(0.001f))
    }

    fun outroContentAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        val start = outroStart(project) + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS
        return smoothStep((modelTime(project, outputTimeSeconds) - start) / 0.12f)
    }

    fun placements(project: CtsProject, outputTimeSeconds: Float): List<CardPlacement> {
        val cardCount = project.cards.size
        if (cardCount <= 0) return emptyList()

        val modelTime = modelTime(project, outputTimeSeconds)
        if (modelTime >= automaticDuration(project)) return emptyList()

        val visibleCards = SharedContract.VISIBLE_CARDS
        val initialCount = min(cardCount, visibleCards)
        val scrollStart = initialCount * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS

        if (modelTime < scrollStart) {
            return buildList {
                for (index in 0 until initialCount) {
                    val localTime = modelTime - index * REVEAL_SECONDS
                    if (localTime < 0f) continue
                    val slide = materialEase(localTime / BODY_WIPE_SECONDS)
                    val badgeTime = localTime - BADGE_DELAY_SECONDS
                    add(
                        CardPlacement(
                            cardIndex = index,
                            // Each opening card comes from exactly one slot to its left.
                            xInCards = index - 1f + slide,
                            bodyReveal = 1f,
                            badgeVisible = badgeTime >= 0f,
                            badgeSettle = materialEase(badgeTime / BADGE_SETTLE_SECONDS),
                        ),
                    )
                }
            }
        }

        val scrollElapsed = (modelTime - scrollStart).coerceAtLeast(0f)
        val maximumShift = max(0, cardCount - visibleCards)
        val rawShift = (scrollElapsed / SCROLL_SECONDS).coerceAtMost(maximumShift.toFloat())
        val completedShifts = floor(rawShift).toInt().coerceAtMost(maximumShift)
        val cycleProgress = rawShift - completedShifts
        val easedShift = if (completedShifts >= maximumShift) {
            maximumShift.toFloat()
        } else {
            completedShifts + materialEase(cycleProgress)
        }

        return buildList {
            for (index in 0 until cardCount) {
                val x = index - easedShift
                if (x >= visibleCards || x + 1f <= 0f) continue

                val badgeStart = if (index < initialCount) {
                    index * REVEAL_SECONDS + BADGE_DELAY_SECONDS
                } else {
                    // The reference badge enters just before the incoming card reaches slot four.
                    scrollStart + (index - initialCount + 1) * SCROLL_SECONDS - BADGE_DELAY_SECONDS
                }
                val badgeTime = modelTime - badgeStart
                add(
                    CardPlacement(
                        cardIndex = index,
                        xInCards = x,
                        bodyReveal = 1f,
                        badgeVisible = badgeTime >= 0f,
                        badgeSettle = materialEase(badgeTime / BADGE_SETTLE_SECONDS),
                    ),
                )
            }
        }
    }

    fun fadeAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        val modelTime = modelTime(project, outputTimeSeconds)
        val fadeStart = automaticDuration(project) - FADE_SECONDS
        if (modelTime <= fadeStart) return 1f
        return 1f - smoothStep((modelTime - fadeStart) / FADE_SECONDS.coerceAtLeast(0.001f))
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        val initialCount = min(project.cards.size, SharedContract.VISIBLE_CARDS)
        val scrollStart = initialCount * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val targetModelTime = if (safeIndex < SharedContract.VISIBLE_CARDS) {
            safeIndex * REVEAL_SECONDS + BODY_WIPE_SECONDS
        } else {
            scrollStart + (safeIndex - SharedContract.VISIBLE_CARDS + 1) * SCROLL_SECONDS
        }
        return min(duration(project), outputTimeForModelTime(project, targetModelTime))
    }

    fun formatTime(seconds: Float): String {
        val total = seconds.coerceAtLeast(0f).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun materialEase(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        repeat(12) {
            val t = (low + high) / 2f
            val curveX = cubic(t, SharedContract.MATERIAL_EASE_X1, SharedContract.MATERIAL_EASE_X2)
            if (curveX < x) low = t else high = t
        }
        return cubic(
            (low + high) / 2f,
            SharedContract.MATERIAL_EASE_Y1,
            SharedContract.MATERIAL_EASE_Y2,
        )
    }

    private fun cubic(t: Float, firstControl: Float, secondControl: Float): Float {
        val inverse = 1f - t
        return 3f * inverse * inverse * t * firstControl +
            3f * inverse * t * t * secondControl +
            t * t * t
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
''',
)

write(
    "android/app/src/main/java/io/github/retrofrost/cts/android/ui/ReferenceOverlays.kt",
    r'''package io.github.retrofrost.cts.android.ui

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
    if (coverProgress > 0f) {
        Box(
            Modifier
                .align(Alignment.TopStart)
                .width(overlayWidth)
                .height(maxHeight * coverProgress.coerceIn(0f, 1f))
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
                    OutroVideoBox("BEST VIDEO FOR YOU", Modifier.weight(1f).height(maxHeight * 0.36f))
                    OutroVideoBox("NEWEST VIDEO", Modifier.weight(1f).height(maxHeight * 0.36f))
                }
                Box(
                    modifier = Modifier
                        .width(overlayWidth * 0.36f)
                        .height(maxHeight * 0.22f)
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
        modifier = modifier.background(Color(0xFFE00000), RoundedCornerShape(8.dp)).padding(10.dp),
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
''',
)

# Replace only the ProgramMonitor shell; all card editing and adaptive layout code stays intact.
regex_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProgramMonitor.kt",
    r'@Composable\nfun ProgramMonitor\(.*?\n\}\n\n@Composable\nprivate fun ReferenceParentCard',
    r'''@Composable
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
private fun ReferenceParentCard''',
)

write(
    "android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceOverlayRenderer.kt",
    r'''package io.github.retrofrost.cts.android.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

internal object ReferenceOverlayRenderer {
    fun drawIntroCredits(canvas: Canvas, width: Int, height: Int, paint: Paint) {
        val cardWidth = width / 4f
        val left = width - cardWidth
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(32, 32, 32)
        canvas.drawRect(left, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "The values presented are average milestones", left, cardWidth, height * 0.06f, height * 0.018f, paint)
        paint.color = Color.rgb(190, 190, 190)
        canvas.drawRect(left + cardWidth * 0.12f, height * 0.20f, width - cardWidth * 0.12f, height * 0.202f, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "Credits", left, cardWidth, height * 0.30f, height * 0.042f, paint, true)
        val lines = listOf(
            "Lead Research & Sourcing", "Independent Fact Check", "Lead Graphic Designer",
            "Edit & Post-Production", "Thumbnail Designer", "Video Idea & Quality Check",
        )
        lines.forEachIndexed { index, line ->
            drawCentered(canvas, line, left, cardWidth, height * (0.39f + index * 0.075f), height * 0.018f, paint)
        }
        paint.color = Color.rgb(200, 200, 200)
        drawCentered(canvas, "DISCLAIMER · COMMUNITY DISCUSSIONS AND SOURCES", left, cardWidth, height * 0.93f, height * 0.011f, paint)
    }

    fun drawOutro(
        canvas: Canvas,
        width: Int,
        height: Int,
        coverProgress: Float,
        contentAlpha: Float,
        paint: Paint,
    ) {
        val overlayRight = width * 0.75f
        if (coverProgress > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(17, 17, 17)
            canvas.drawRect(0f, 0f, overlayRight, height * coverProgress.coerceIn(0f, 1f), paint)
        }
        if (contentAlpha <= 0f) return
        val alpha = (255f * contentAlpha.coerceIn(0f, 1f)).toInt()
        paint.color = Color.argb(alpha, 17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val margin = width * 0.02f
        val gap = width * 0.025f
        val boxTop = height * 0.17f
        val boxBottom = height * 0.53f
        val boxWidth = (overlayRight - margin * 2 - gap) / 2f
        paint.color = Color.argb(alpha, 224, 0, 0)
        canvas.drawRoundRect(RectF(margin, boxTop, margin + boxWidth, boxBottom), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(margin + boxWidth + gap, boxTop, overlayRight - margin, boxBottom), 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "BEST VIDEO FOR YOU", margin, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", margin + boxWidth + gap, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)

        val credits = RectF(overlayRight * 0.32f, height * 0.62f, overlayRight * 0.68f, height * 0.84f)
        paint.color = Color.argb(alpha, 98, 95, 86)
        canvas.drawRoundRect(credits, 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "Video Made By", credits.left, credits.width(), credits.top + credits.height() * 0.22f, height * 0.026f, paint, true)
        drawCentered(canvas, "Research · Editing · Design · Quality Check", credits.left, credits.width(), credits.top + credits.height() * 0.58f, height * 0.014f, paint)
    }

    private fun drawCentered(
        canvas: Canvas,
        text: String,
        left: Float,
        width: Float,
        baseline: Float,
        size: Float,
        paint: Paint,
        bold: Boolean = false,
    ) {
        paint.textSize = max(8f, size)
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, left + width / 2f, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
''',
)

regex_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceFrameRenderer.kt",
    r'    fun render\(target: Bitmap, outputTimeSeconds: Float\) \{.*?\n    \}\n\n    fun close',
    r'''    fun render(target: Bitmap, outputTimeSeconds: Float) {
        require(target.width == width && target.height == height)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        val cardWidth = width / 4f

        if (TimelineEngine.introCreditsVisible(project, outputTimeSeconds)) {
            ReferenceOverlayRenderer.drawIntroCredits(canvas, width, height, paint)
        }

        TimelineEngine.placements(project, outputTimeSeconds).forEach { placement ->
            val card = project.cards.getOrNull(placement.cardIndex) ?: return@forEach
            val cardX = cardWidth * placement.xInCards
            canvas.save()
            canvas.translate(cardX, 0f)
            canvas.clipRect(0f, 0f, cardWidth * placement.bodyReveal.coerceIn(0f, 1f), height.toFloat())
            drawCardBody(canvas, card, cardWidth)
            canvas.restore()
            if (placement.badgeVisible) drawBadge(canvas, card, cardX, cardWidth, placement.badgeSettle)
        }

        ReferenceOverlayRenderer.drawOutro(
            canvas,
            width,
            height,
            TimelineEngine.outroCoverProgress(project, outputTimeSeconds),
            TimelineEngine.outroContentAlpha(project, outputTimeSeconds),
            paint,
        )

        val fade = TimelineEngine.fadeAlpha(project, outputTimeSeconds).coerceIn(0f, 1f)
        if (fade < 0.999f) {
            paint.shader = null
            paint.color = Color.argb(((1f - fade) * 255f).toInt(), 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    fun close''',
)

write(
    "comparison_studio/reference_overlays.py",
    r'''from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from .shared_contract import (
    END_HOLD_SECONDS,
    FADE_SECONDS,
    INTRO_TAIL_HOLD_SECONDS,
    OUTRO_CONTENT_DELAY_SECONDS,
    OUTRO_COVER_SECONDS,
    OUTRO_HOLD_SECONDS,
    REVEAL_SECONDS,
    SCROLL_SECONDS,
    VISIBLE_CARDS,
    material_ease,
)


def _parts(card_count: int) -> tuple[float, float, float]:
    intro = min(card_count, VISIBLE_CARDS) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    scroll = max(0, card_count - VISIBLE_CARDS) * SCROLL_SECONDS
    return intro, scroll, intro + scroll


def intro_credits_visible(card_count: int, model_time: float) -> bool:
    intro, _scroll, _end = _parts(card_count)
    return card_count > 0 and model_time < intro


def outro_cover_progress(card_count: int, model_time: float) -> float:
    _intro, _scroll, scroll_end = _parts(card_count)
    return material_ease((model_time - scroll_end - END_HOLD_SECONDS) / max(0.001, OUTRO_COVER_SECONDS))


def outro_content_alpha(card_count: int, model_time: float) -> float:
    _intro, _scroll, scroll_end = _parts(card_count)
    start = scroll_end + END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS
    x = max(0.0, min(1.0, (model_time - start) / 0.12))
    return x * x * (3.0 - 2.0 * x)


def _font(size: int, bold: bool = False):
    candidates = [
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size=max(8, size))
    return ImageFont.load_default()


def _center(draw: ImageDraw.ImageDraw, box, text: str, fill, size: int, bold: bool = False):
    font = _font(size, bold)
    left, top, right, bottom = box
    draw.multiline_text(
        ((left + right) / 2, (top + bottom) / 2),
        text,
        font=font,
        fill=fill,
        anchor="mm",
        align="center",
        spacing=max(2, size // 4),
    )


def draw_intro_credits(frame: Image.Image) -> None:
    draw = ImageDraw.Draw(frame)
    width, height = frame.size
    left = round(width * 0.75)
    draw.rectangle((left, 0, width, height), fill=(32, 32, 32, 255))
    _center(draw, (left + 16, 10, width - 16, height * 0.14), "The values presented are average milestones\nand may vary.", (255, 255, 255, 255), round(height * 0.017))
    draw.line((left + width * 0.025, height * 0.21, width - width * 0.025, height * 0.21), fill=(190, 190, 190, 255), width=max(1, width // 960))
    _center(draw, (left, height * 0.24, width, height * 0.35), "Credits", (255, 255, 255, 255), round(height * 0.043), True)
    _center(draw, (left + 8, height * 0.35, width - 8, height * 0.86), "Lead Research & Sourcing\n\nIndependent Fact Check\n\nLead Graphic Designer\n\nEdit & Post-Production\n\nThumbnail Designer\n\nVideo Idea & Quality Check", (255, 255, 255, 255), round(height * 0.017), True)
    _center(draw, (left + 8, height * 0.88, width - 8, height - 8), "DISCLAIMER\nCOMMUNITY DISCUSSIONS AND RELEVANT SOURCES", (200, 200, 200, 255), round(height * 0.009))


def draw_outro(frame: Image.Image, cover: float, alpha: float) -> None:
    draw = ImageDraw.Draw(frame)
    width, height = frame.size
    right = round(width * 0.75)
    if cover > 0.0:
        draw.rectangle((0, 0, right, round(height * max(0.0, min(1.0, cover)))), fill=(17, 17, 17, 255))
    if alpha <= 0.0:
        return
    overlay = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    odraw.rectangle((0, 0, right, height), fill=(17, 17, 17, round(255 * alpha)))
    margin = round(width * 0.02)
    gap = round(width * 0.025)
    top = round(height * 0.17)
    bottom = round(height * 0.53)
    box_width = (right - margin * 2 - gap) // 2
    red = (224, 0, 0, round(255 * alpha))
    odraw.rounded_rectangle((margin, top, margin + box_width, bottom), radius=14, fill=red)
    odraw.rounded_rectangle((margin + box_width + gap, top, right - margin, bottom), radius=14, fill=red)
    _center(odraw, (margin, top, margin + box_width, top + height * 0.12), "BEST VIDEO FOR YOU", (255, 255, 255, round(255 * alpha)), round(height * 0.027), True)
    _center(odraw, (margin + box_width + gap, top, right - margin, top + height * 0.12), "NEWEST VIDEO", (255, 255, 255, round(255 * alpha)), round(height * 0.027), True)
    credits = (round(right * 0.32), round(height * 0.62), round(right * 0.68), round(height * 0.84))
    odraw.rounded_rectangle(credits, radius=14, fill=(98, 95, 86, round(255 * alpha)))
    _center(odraw, credits, "Video Made By\n\nResearch · Editing · Design · Quality Check", (255, 255, 255, round(255 * alpha)), round(height * 0.017), True)
    frame.alpha_composite(overlay)
''',
)

replace_once(
    "comparison_studio/reference_illustrated.py",
    'from .renderer import BACKGROUND, _clamp, _draw_text_box, _smoothstep\n',
    'from .renderer import BACKGROUND, _clamp, _draw_text_box, _smoothstep\nfrom .reference_overlays import (\n    draw_intro_credits,\n    draw_outro,\n    intro_credits_visible,\n    outro_content_alpha,\n    outro_cover_progress,\n)\n',
)
replace_once(
    "comparison_studio/reference_illustrated.py",
    '''                placements.append(
                    (
                        index,
                        index * card_width,
                        material_ease(local_time / BODY_WIPE_SECONDS),
                        material_ease(badge_time / BADGE_SETTLE_SECONDS)
                        if badge_time >= 0.0
                        else 0.0,
                    )
                )''',
    '''                slide = material_ease(local_time / BODY_WIPE_SECONDS)
                placements.append(
                    (
                        index,
                        (index - 1.0 + slide) * card_width,
                        1.0,
                        material_ease(badge_time / BADGE_SETTLE_SECONDS)
                        if badge_time >= 0.0
                        else 0.0,
                    )
                )''',
)
replace_once(
    "comparison_studio/reference_illustrated.py",
    '                badge_start = scroll_start + (index - initial_count + 1) * SCROLL_SECONDS',
    '                badge_start = scroll_start + (index - initial_count + 1) * SCROLL_SECONDS - BADGE_DELAY_SECONDS',
)
replace_once(
    "comparison_studio/reference_illustrated.py",
    '        placements = self._placements(len(cards), timeline_time, VISIBLE_CARDS, width, True)\n',
    '        if intro_credits_visible(len(cards), timeline_time):\n            draw_intro_credits(frame)\n        placements = self._placements(len(cards), timeline_time, VISIBLE_CARDS, width, True)\n',
)
replace_once(
    "comparison_studio/reference_illustrated.py",
    '        result = frame.convert("RGB")\n',
    '        draw_outro(\n            frame,\n            outro_cover_progress(len(cards), timeline_time),\n            outro_content_alpha(len(cards), timeline_time),\n        )\n        result = frame.convert("RGB")\n',
)

# Android behavior tests now assert sliding intro, pre-arrival badges, and the selective outro.
write(
    "android/app/src/test/java/io/github/retrofrost/cts/android/timeline/TimelineEngineTest.kt",
    r'''package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimelineEngineTest {
    @Before fun resetDurationChoice() = DurationRuntime.resetForTests()

    @Test
    fun androidExposesOnlyTheCanonicalFourCardModel() {
        assertEquals(listOf(VisualModel.Illustrated), VisualModel.entries)
        assertEquals(4, VisualModel.Illustrated.visibleCards)
    }

    @Test
    fun automaticDurationIncludesTheFullReferenceOutro() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val expected = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS + SCROLL_SECONDS +
            END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS +
            OUTRO_HOLD_SECONDS + FADE_SECONDS
        assertEquals(expected, TimelineEngine.automaticDuration(project), 0.0001f)
    }

    @Test
    fun customLengthChangesOnlySecondsPerScrollingCard() {
        val automaticProject = CtsProject(model = VisualModel.Illustrated)
        val automaticDuration = TimelineEngine.automaticDuration(automaticProject)
        val customProject = automaticProject.copy(customDurationSeconds = automaticDuration + 6f)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        assertEquals(automaticDuration + 6f, TimelineEngine.duration(customProject), 0.0001f)
        assertEquals(SCROLL_SECONDS + 6f, TimelineEngine.secondsPerCard(customProject), 0.0001f)
        assertEquals(scrollStart, TimelineEngine.modelTime(customProject, scrollStart), 0.0001f)
    }

    @Test
    fun firstCardSlidesFromOneSlotLeftInsteadOfBeingWiped() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val first = TimelineEngine.placements(project, 0f).first()
        assertEquals(-1f, first.xInCards, 0.001f)
        assertEquals(1f, first.bodyReveal, 0.001f)
        assertFalse(first.badgeVisible)

        val entering = TimelineEngine.placements(project, 0.7f).first()
        assertTrue(entering.xInCards in -1f..0f)
        assertTrue(entering.badgeVisible)
        val settled = TimelineEngine.placements(project, BODY_WIPE_SECONDS).first()
        assertEquals(0f, settled.xInCards, 0.001f)
    }

    @Test
    fun scrollingMovesEachCompleteParentByOneCardWidthWithEasing() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val before = TimelineEngine.placements(project, scrollStart)
        val halfway = TimelineEngine.placements(project, scrollStart + SCROLL_SECONDS / 2f)
        val after = TimelineEngine.placements(project, scrollStart + SCROLL_SECONDS)
        val beforeSecond = before.first { it.cardIndex == 1 }
        val halfwaySecond = halfway.first { it.cardIndex == 1 }
        val afterSecond = after.first { it.cardIndex == 1 }
        assertTrue(halfwaySecond.xInCards < beforeSecond.xInCards)
        assertTrue(halfwaySecond.xInCards > afterSecond.xInCards)
        assertEquals(1f, beforeSecond.xInCards - afterSecond.xInCards, 0.0001f)
        assertEquals(1f, halfway.first { it.cardIndex == 4 }.bodyReveal, 0.0001f)
    }

    @Test
    fun incomingBadgeBeginsBeforeTheCardFinishesArriving() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val beforeLead = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS - BADGE_DELAY_SECONDS - 0.01f,
        ).first { it.cardIndex == 4 }
        assertFalse(beforeLead.badgeVisible)
        val duringLead = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS - BADGE_DELAY_SECONDS + 0.01f,
        ).first { it.cardIndex == 4 }
        assertTrue(duringLead.badgeVisible)
        assertTrue(duringLead.xInCards > 3f)
    }

    @Test
    fun outroCoversOnlyTheFirstThreeColumnsAndThenShowsContent() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollEnd = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS + SCROLL_SECONDS
        assertEquals(0f, TimelineEngine.outroCoverProgress(project, scrollEnd), 0.001f)
        assertTrue(TimelineEngine.outroCoverProgress(project, scrollEnd + END_HOLD_SECONDS + OUTRO_COVER_SECONDS) > 0.99f)
        assertTrue(
            TimelineEngine.outroContentAlpha(
                project,
                scrollEnd + END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS + 0.12f,
            ) > 0.99f,
        )
        val finalPlacement = TimelineEngine.placements(project, scrollEnd + END_HOLD_SECONDS).last()
        assertEquals(4, finalPlacement.cardIndex)
        assertEquals(3f, finalPlacement.xInCards, 0.001f)
    }
}
''',
)

# Shared test should derive the duration from every fixed phase, not the removed fade-only tail.
shared_test = "tests/test_shared_contract.py"
regex_once(
    shared_test,
    r'    def test_duration_matches_android_reference_timeline\(self\) -> None:.*?\n    def test_material_curve_and_scroll_shift_are_bounded',
    '''    def test_duration_matches_android_reference_timeline(self) -> None:
        expected = (
            4 * shared_contract.REVEAL_SECONDS
            + shared_contract.INTRO_TAIL_HOLD_SECONDS
            + shared_contract.SCROLL_SECONDS
            + shared_contract.END_HOLD_SECONDS
            + shared_contract.OUTRO_COVER_SECONDS
            + shared_contract.OUTRO_CONTENT_DELAY_SECONDS
            + shared_contract.OUTRO_HOLD_SECONDS
            + shared_contract.FADE_SECONDS
        )
        self.assertAlmostEqual(shared_contract.automatic_duration(5), expected, places=6)
        self.assertEqual(shared_contract.automatic_duration(0), 0.0)
        custom = expected + 5.0
        self.assertAlmostEqual(shared_contract.chosen_duration(5, custom), custom)
        self.assertAlmostEqual(
            shared_contract.model_time(5, 4 * shared_contract.REVEAL_SECONDS, custom),
            4 * shared_contract.REVEAL_SECONDS,
        )

    def test_material_curve_and_scroll_shift_are_bounded''',
)

print("Applied full-video intro, conveyor, and outro animation behavior.")
