from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected block was not found in {path}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


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
const val FADE_SECONDS = SharedContract.FADE_SECONDS
const val BODY_WIPE_SECONDS = SharedContract.BODY_WIPE_SECONDS
const val BADGE_DELAY_SECONDS = SharedContract.BADGE_DELAY_SECONDS
const val BADGE_SETTLE_SECONDS = SharedContract.BADGE_SETTLE_SECONDS
const val INTRO_TAIL_HOLD_SECONDS = SharedContract.INTRO_TAIL_HOLD_SECONDS
private const val MIN_SCROLL_STEP_SECONDS = 0.12f

data class CardPlacement(
    val cardIndex: Int,
    /** Horizontal position measured in parent-card widths. */
    val xInCards: Float,
    /** Left-to-right opening wipe. Scrolling cards are already fully uncovered. */
    val bodyReveal: Float,
    /** True once the red badge has begun its entrance. */
    val badgeVisible: Boolean,
    /** 0 = oversized/off the top edge, 1 = settled at its canonical size. */
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
        val fixedTail = END_HOLD_SECONDS + FADE_SECONDS
        return TimelineParts(intro, scrollSteps, automaticScroll, fixedTail)
    }

    fun automaticDuration(project: CtsProject): Float {
        val parts = timelineParts(project)
        return parts.introSeconds + parts.automaticScrollSeconds + parts.fixedTailSeconds
    }

    /**
     * CTS Easy custom length keeps the intro, ending hold, and fade at their normal speed.
     * Only the horizontal scrolling segment is stretched or compressed.
     */
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

    /** Actual output seconds assigned to each one-card horizontal movement. */
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
        ) {
            return output
        }
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
        ) {
            return modelTime
        }
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
                    val badgeTime = localTime - BADGE_DELAY_SECONDS
                    add(
                        CardPlacement(
                            cardIndex = index,
                            xInCards = index.toFloat(),
                            bodyReveal = materialEase(localTime / BODY_WIPE_SECONDS),
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
                    scrollStart + (index - initialCount + 1) * SCROLL_SECONDS
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
        return 1f - smoothStep((modelTime - fadeStart) / FADE_SECONDS)
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        val initialCount = min(project.cards.size, SharedContract.VISIBLE_CARDS)
        val scrollStart = initialCount * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val targetModelTime = if (safeIndex < SharedContract.VISIBLE_CARDS) {
            scrollStart
        } else {
            scrollStart + (safeIndex - SharedContract.VISIBLE_CARDS + 1) * SCROLL_SECONDS
        }
        return min(duration(project), outputTimeForModelTime(project, targetModelTime))
    }

    fun formatTime(seconds: Float): String {
        val total = seconds.coerceAtLeast(0f).toInt()
        val minutes = total / 60
        val remainder = total % 60
        return "%d:%02d".format(minutes, remainder)
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
    "android/app/src/main/java/io/github/retrofrost/cts/android/layout/CardContentLayout.kt",
    r'''package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.NormalizedRect

data class CardContentFrames(
    val image: NormalizedRect,
    val title: NormalizedRect?,
    val description: NormalizedRect?,
)

/**
 * Empty text fields consume no card height. Remaining rows stay anchored to the bottom and
 * the artwork grows into every released slot, matching CTS's optional-column behavior.
 */
object CardContentLayout {
    private const val LEFT = 0.008f
    private const val WIDTH = 0.984f
    private const val CONTENT_BOTTOM = 0.996f
    private const val TITLE_HEIGHT = 0.088f
    private const val DESCRIPTION_HEIGHT = 0.101f

    fun frames(card: CtsCard): CardContentFrames {
        var cursor = CONTENT_BOTTOM
        val description = if (card.description.isNotBlank()) {
            cursor -= DESCRIPTION_HEIGHT
            NormalizedRect(LEFT, cursor, WIDTH, DESCRIPTION_HEIGHT)
        } else {
            null
        }
        val title = if (card.title.isNotBlank()) {
            cursor -= TITLE_HEIGHT
            NormalizedRect(LEFT, cursor, WIDTH, TITLE_HEIGHT)
        } else {
            null
        }
        return CardContentFrames(
            image = NormalizedRect(LEFT, 0f, WIDTH, cursor.coerceAtLeast(0f)),
            title = title,
            description = description,
        )
    }
}
''',
)

replace_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/model/CtsProject.kt",
    "/** Null keeps the old automatic-length behavior; a value scales the whole animation. */",
    "/** Null uses automatic timing; a value retimes only horizontal card scrolling. */",
)

replace_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppWithVideoLength.kt",
    " * Automatic length is the default. A custom MM:SS target scales the entire timeline and is\n * used by both live preview and the WorkManager background encoder.",
    " * Automatic length is the default. A custom MM:SS target changes seconds per scrolling card\n * while entrances and the ending remain at normal speed in preview and background export.",
)
replace_once(
    "android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsAppWithVideoLength.kt",
    '                    "This works like previous CTS versions: keep automatic timing, or enter a target length. " +\n                        "A custom target speeds up or slows down the whole animation.",',
    '                    "This works like previous CTS versions: keep automatic timing, or enter a target length. " +\n                        "CTS changes the scrolling speed while keeping entrances and the ending unchanged.",',
)

program = "android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProgramMonitor.kt"
replace_once(
    program,
    "import io.github.retrofrost.cts.android.model.CtsCard\n",
    "import io.github.retrofrost.cts.android.layout.CardContentLayout\nimport io.github.retrofrost.cts.android.model.CtsCard\n",
)
replace_once(
    program,
    "private val ImageFrame = NormalizedRect(0.008f, 0f, 0.984f, 0.807f)\nprivate val TitleFrame = NormalizedRect(0.008f, 0.807f, 0.984f, 0.088f)\nprivate val DescriptionFrame = NormalizedRect(0.008f, 0.895f, 0.984f, 0.101f)\n",
    "",
)
replace_once(
    program,
    r'''    Frame(
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
''',
    r'''    val frames = CardContentLayout.frames(card)
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
''',
)

renderer = "android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceFrameRenderer.kt"
replace_once(
    renderer,
    "import io.github.retrofrost.cts.android.model.CtsCard\n",
    "import io.github.retrofrost.cts.android.layout.CardContentLayout\nimport io.github.retrofrost.cts.android.model.CtsCard\n",
)
replace_once(
    renderer,
    "        val image = frameRect(IMAGE_FRAME, cardWidth)\n        val title = frameRect(TITLE_FRAME, cardWidth)\n        val description = frameRect(DESCRIPTION_FRAME, cardWidth)\n",
    "        val frames = CardContentLayout.frames(card)\n        val image = frameRect(frames.image, cardWidth)\n        val title = frames.title?.let { frameRect(it, cardWidth) }\n        val description = frames.description?.let { frameRect(it, cardWidth) }\n",
)
replace_once(
    renderer,
    r'''        paint.color = Color.rgb(240, 240, 240)
        canvas.drawRect(title, paint)
        paint.color = Color.rgb(98, 95, 86)
        canvas.drawRect(description, paint)
''',
    r'''        title?.let {
            paint.color = Color.rgb(240, 240, 240)
            canvas.drawRect(it, paint)
        }
        description?.let {
            paint.color = Color.rgb(98, 95, 86)
            canvas.drawRect(it, paint)
        }
''',
)
replace_once(
    renderer,
    r'''        canvas.drawRect(0f, title.top, cardWidth, title.top + divider, paint)
        canvas.drawRect(0f, description.top, cardWidth, description.top + divider, paint)
''',
    r'''        title?.let { canvas.drawRect(0f, it.top, cardWidth, it.top + divider, paint) }
        description?.let { canvas.drawRect(0f, it.top, cardWidth, it.top + divider, paint) }
''',
)
replace_once(
    renderer,
    r'''        drawTextBlock(
            canvas = canvas,
            text = card.title,
            rect = RectF(title.left + padding, title.top + 2f, title.right - padding, title.bottom - 2f),
            color = Color.rgb(16, 16, 16),
            bold = true,
            maximumSize = height * 0.043f,
            minimumSize = height * 0.018f,
            maxLines = 2,
        )
        drawTextBlock(
            canvas = canvas,
            text = card.description,
            rect = RectF(
                description.left + padding,
                description.top + 2f,
                description.right - padding,
                description.bottom - 2f,
            ),
            color = Color.WHITE,
            bold = true,
            maximumSize = height * 0.027f,
            minimumSize = height * 0.014f,
            maxLines = 3,
        )
''',
    r'''        title?.let {
            drawTextBlock(
                canvas = canvas,
                text = card.title,
                rect = RectF(it.left + padding, it.top + 2f, it.right - padding, it.bottom - 2f),
                color = Color.rgb(16, 16, 16),
                bold = true,
                maximumSize = height * 0.043f,
                minimumSize = height * 0.018f,
                maxLines = 2,
            )
        }
        description?.let {
            drawTextBlock(
                canvas = canvas,
                text = card.description,
                rect = RectF(
                    it.left + padding,
                    it.top + 2f,
                    it.right - padding,
                    it.bottom - 2f,
                ),
                color = Color.WHITE,
                bold = true,
                maximumSize = height * 0.027f,
                minimumSize = height * 0.014f,
                maxLines = 3,
            )
        }
''',
)
replace_once(
    renderer,
    "        val IMAGE_FRAME = NormalizedRect(0.008f, 0f, 0.984f, 0.807f)\n        val TITLE_FRAME = NormalizedRect(0.008f, 0.807f, 0.984f, 0.088f)\n        val DESCRIPTION_FRAME = NormalizedRect(0.008f, 0.895f, 0.984f, 0.101f)\n",
    "",
)

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
    @Before
    fun resetDurationChoice() {
        DurationRuntime.resetForTests()
    }

    @Test
    fun androidExposesOnlyTheCanonicalFourCardModel() {
        assertEquals(listOf(VisualModel.Illustrated), VisualModel.entries)
        assertEquals(4, VisualModel.Illustrated.visibleCards)
        assertEquals(VisualModel.Illustrated, VisualModel.fromId("reference_detail"))
        assertEquals(VisualModel.Illustrated, VisualModel.fromId("classic_compact"))
    }

    @Test
    fun automaticDurationIncludesTheReferenceIntroHold() {
        val project = CtsProject(model = VisualModel.Illustrated)
        assertEquals(14.933333f, TimelineEngine.automaticDuration(project), 0.0001f)
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

        val halfOutput = scrollStart + TimelineEngine.secondsPerCard(customProject) / 2f
        assertEquals(
            scrollStart + SCROLL_SECONDS / 2f,
            TimelineEngine.modelTime(customProject, halfOutput),
            0.0001f,
        )
        assertEquals(
            automaticDuration - FADE_SECONDS,
            TimelineEngine.modelTime(customProject, TimelineEngine.duration(customProject) - FADE_SECONDS),
            0.0001f,
        )
    }

    @Test
    fun customLengthIsIgnoredWhenEveryCardAlreadyFitsOnScreen() {
        val automaticProject = CtsProject().let { it.copy(cards = it.cards.take(4)) }
        val customProject = automaticProject.copy(customDurationSeconds = 60f)
        assertEquals(
            TimelineEngine.automaticDuration(automaticProject),
            TimelineEngine.duration(customProject),
            0.0001f,
        )
        assertEquals(0f, TimelineEngine.secondsPerCard(customProject), 0.0001f)
    }

    @Test
    fun firstCardUsesAHorizontalWipeBeforeItsBadgeSettles() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val firstFrame = TimelineEngine.placements(project, 0f)
        assertEquals(1, firstFrame.size)
        assertEquals(0f, firstFrame.first().bodyReveal, 0.001f)
        assertFalse(firstFrame.first().badgeVisible)

        val entering = TimelineEngine.placements(project, 0.7f).first()
        assertTrue(entering.bodyReveal > 0.75f)
        assertTrue(entering.badgeVisible)
        assertTrue(entering.badgeSettle in 0f..0.25f)

        val settledBody = TimelineEngine.placements(project, BODY_WIPE_SECONDS).first()
        assertEquals(1f, settledBody.bodyReveal, 0.001f)
    }

    @Test
    fun scrollingMovesEachParentByOneCardWidthWithEasing() {
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
    }

    @Test
    fun incomingBadgeAppearsOnlyWhenItsCardReachesTheFourthSlot() {
        val project = CtsProject(model = VisualModel.Illustrated)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val justBeforeArrival = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS - 0.01f,
        ).first { it.cardIndex == 4 }
        assertFalse(justBeforeArrival.badgeVisible)

        val atArrival = TimelineEngine.placements(
            project,
            scrollStart + SCROLL_SECONDS,
        ).first { it.cardIndex == 4 }
        assertTrue(atArrival.badgeVisible)
        assertEquals(0f, atArrival.badgeSettle, 0.01f)
        assertEquals(3f, atArrival.xInCards, 0.001f)
    }
}
''',
)

write(
    "android/app/src/test/java/io/github/retrofrost/cts/android/layout/CardContentLayoutTest.kt",
    r'''package io.github.retrofrost.cts.android.layout

import io.github.retrofrost.cts.android.model.CtsCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardContentLayoutTest {
    @Test
    fun fullCardKeepsCanonicalFrames() {
        val frames = CardContentLayout.frames(CtsCard(title = "Title", description = "Description"))
        assertEquals(0.807f, frames.image.height, 0.0001f)
        assertEquals(0.807f, frames.title!!.y, 0.0001f)
        assertEquals(0.895f, frames.description!!.y, 0.0001f)
    }

    @Test
    fun missingDescriptionGivesItsSpaceToArtwork() {
        val frames = CardContentLayout.frames(CtsCard(title = "Title", description = ""))
        assertEquals(0.908f, frames.image.height, 0.0001f)
        assertEquals(0.908f, frames.title!!.y, 0.0001f)
        assertNull(frames.description)
    }

    @Test
    fun missingTitleGivesItsSpaceToArtwork() {
        val frames = CardContentLayout.frames(CtsCard(title = "", description = "Description"))
        assertEquals(0.895f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNotNull(frames.description)
    }

    @Test
    fun missingTextLetsArtworkFillTheCard() {
        val frames = CardContentLayout.frames(CtsCard(title = "", description = ""))
        assertEquals(0.996f, frames.image.height, 0.0001f)
        assertNull(frames.title)
        assertNull(frames.description)
    }
}
''',
)

print("Applied CTS Android segment timing and adaptive card layout")
