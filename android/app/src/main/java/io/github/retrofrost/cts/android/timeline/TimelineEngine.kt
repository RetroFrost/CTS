package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.ModelMode
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
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
    val artworkReveal: Float = 1f,
    val titleReveal: Float = 1f,
    val descriptionReveal: Float = 1f,
    val badgeRect: NormalizedRect? = null,
    val badgeTextAlpha: Float = 1f,
)

private data class TimelineParts(
    val preludeSeconds: Float,
    val revealSeconds: Float,
    val scrollSeconds: Float,
    val introSeconds: Float,
    val scrollSteps: Int,
    val automaticScrollSeconds: Float,
    val fixedTailSeconds: Float,
)

object TimelineEngine {
    const val RELATIONSHIPS_REFERENCE_FRAMES = 11_130
    const val RELATIONSHIPS_REFERENCE_FPS = 60
    const val RELATIONSHIPS_REFERENCE_SECONDS = 185.5f
    const val RELATIONSHIPS_INTRO_FRAMES = 374
    const val RELATIONSHIPS_INTRO_OVERLAY_END_FRAME = 550
    const val RELATIONSHIPS_CONTINUOUS_START_FRAME = 896
    const val RELATIONSHIPS_CONTINUOUS_STEP_FRAMES = 266
    const val RELATIONSHIPS_CONTENT_END_CANONICAL_FRAME = 10_738
    const val RELATIONSHIPS_END_WIPE_FRAMES = 42
    const val RELATIONSHIPS_END_RISE_FRAMES = 50
    const val RELATIONSHIPS_END_HOLD_FRAMES = 270
    const val RELATIONSHIPS_FADE_FRAMES = 30
    private const val RELATIONSHIPS_POSITION_STEP_FRAMES = 265.7158648f
    private val relationshipsOpeningStarts = intArrayOf(374, 521, 656, 795)
    private val relationshipsOpeningEnds = intArrayOf(521, 656, 795, 896)

    private fun isLockedRelationships(project: CtsProject): Boolean =
        project.model == VisualModel.Relationships && project.modelMode == ModelMode.ExactReference

    private fun preludeSeconds(project: CtsProject): Float = when (project.model) {
        VisualModel.Males -> 0f
        VisualModel.Relationships -> if (project.showIntro) RELATIONSHIPS_INTRO_FRAMES / 60f else 0f
    }

    private fun revealSeconds(project: CtsProject): Float = when (project.model) {
        VisualModel.Males -> REVEAL_SECONDS
        VisualModel.Relationships -> RELATIONSHIPS_CONTINUOUS_STEP_FRAMES / 60f
    }

    private fun baseScrollSeconds(project: CtsProject): Float = when (project.model) {
        VisualModel.Males -> SCROLL_SECONDS
        VisualModel.Relationships -> RELATIONSHIPS_CONTINUOUS_STEP_FRAMES / 60f
    }

    private fun tailSeconds(project: CtsProject): Float = if (project.showOutro) {
        END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS + OUTRO_HOLD_SECONDS + FADE_SECONDS
    } else {
        END_HOLD_SECONDS
    }

    private fun timelineParts(project: CtsProject): TimelineParts {
        val cardCount = project.cards.size
        if (cardCount <= 0) return TimelineParts(0f, REVEAL_SECONDS, SCROLL_SECONDS, 0f, 0, 0f, 0f)
        val visible = project.model.visibleCards
        val prelude = preludeSeconds(project)
        val reveal = revealSeconds(project)
        val scrollSeconds = baseScrollSeconds(project)
        val intro = prelude + min(cardCount, visible) * reveal + INTRO_TAIL_HOLD_SECONDS
        val scrollSteps = max(0, cardCount - visible)
        val automaticScroll = scrollSteps * scrollSeconds
        return TimelineParts(prelude, reveal, scrollSeconds, intro, scrollSteps, automaticScroll, tailSeconds(project))
    }

    private fun modelDuration(project: CtsProject): Float {
        val parts = timelineParts(project)
        return parts.introSeconds + parts.automaticScrollSeconds + parts.fixedTailSeconds
    }

    private fun relationshipsContentEndFrame(cardCount: Int): Int = when {
        cardCount <= 0 -> 0
        cardCount <= 4 -> relationshipsOpeningEnds[cardCount - 1]
        else -> RELATIONSHIPS_CONTINUOUS_START_FRAME +
            (cardCount - 4 + 1) * RELATIONSHIPS_CONTINUOUS_STEP_FRAMES
    }

    private fun relationshipsIntroOffset(project: CtsProject): Int =
        if (project.showIntro) 0 else RELATIONSHIPS_INTRO_FRAMES

    fun relationshipsSourceFrame(project: CtsProject, outputTimeSeconds: Float): Int =
        (outputTimeSeconds.coerceAtLeast(0f) * RELATIONSHIPS_REFERENCE_FPS).toInt() +
            relationshipsIntroOffset(project)

    fun relationshipsOutroLocalFrame(project: CtsProject, outputTimeSeconds: Float): Int =
        relationshipsSourceFrame(project, outputTimeSeconds) - relationshipsContentEndFrame(project.cards.size)

    fun automaticDuration(project: CtsProject): Float {
        val parts = timelineParts(project)
        if (isLockedRelationships(project)) {
            val content = relationshipsContentEndFrame(project.cards.size)
            val outro = if (project.showOutro) {
                RELATIONSHIPS_END_WIPE_FRAMES + RELATIONSHIPS_END_RISE_FRAMES +
                    RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            } else 0
            return (content - relationshipsIntroOffset(project) + outro)
                .coerceAtLeast(0) / RELATIONSHIPS_REFERENCE_FPS.toFloat()
        }
        return parts.introSeconds + parts.automaticScrollSeconds + parts.fixedTailSeconds
    }

    fun duration(project: CtsProject): Float {
        val parts = timelineParts(project)
        val automatic = automaticDuration(project)
        if (project.modelMode == ModelMode.ExactReference) return automatic
        val custom = DurationRuntime.resolve(project.customDurationSeconds) ?: return automatic
        if (parts.scrollSteps <= 0) return automatic
        val minimum = parts.introSeconds +
            parts.scrollSteps * MIN_SCROLL_STEP_SECONDS +
            parts.fixedTailSeconds
        return max(minimum, custom)
    }

    private fun chosenScrollDuration(project: CtsProject, parts: TimelineParts): Float {
        if (parts.scrollSteps <= 0) return 0f
        if (isLockedRelationships(project)) return parts.automaticScrollSeconds
        if (project.modelMode == ModelMode.ExactReference) return parts.automaticScrollSeconds
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
        if (project.modelMode == ModelMode.ExactReference) return output
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
        if (project.modelMode == ModelMode.ExactReference) return modelTime
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
        if (project.cards.isEmpty() || project.model != VisualModel.Males || !project.showDisclaimer) return false
        return modelTime(project, outputTimeSeconds) < timelineParts(project).introSeconds
    }

    fun relationshipsInfinityProgress(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model != VisualModel.Relationships || !project.showIntro) return 0f
        return relationshipsSourceFrame(project, outputTimeSeconds) /
            RELATIONSHIPS_INTRO_FRAMES.toFloat()
    }

    fun relationshipsDisclaimerAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model != VisualModel.Relationships || !project.showDisclaimer) return 0f
        val frame = relationshipsSourceFrame(project, outputTimeSeconds)
        if (frame !in 434 until 795) return 0f
        return ((frame - 434) / 45f).coerceIn(0f, 1f)
    }

    fun outroCoverProgress(project: CtsProject, outputTimeSeconds: Float): Float {
        if (!project.showOutro) return 0f
        if (isLockedRelationships(project)) {
            return (relationshipsOutroLocalFrame(project, outputTimeSeconds) /
                RELATIONSHIPS_END_WIPE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
        val elapsed = modelTime(project, outputTimeSeconds) - outroStart(project)
        return materialEase(elapsed / OUTRO_COVER_SECONDS.coerceAtLeast(0.001f))
    }

    fun outroContentAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        if (!project.showOutro) return 0f
        if (isLockedRelationships(project)) {
            return ((relationshipsOutroLocalFrame(project, outputTimeSeconds) - 35) / 28f)
                .coerceIn(0f, 1f)
        }
        val start = outroStart(project) + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS
        return smoothStep((modelTime(project, outputTimeSeconds) - start) / 0.12f)
    }

    fun placements(project: CtsProject, outputTimeSeconds: Float): List<CardPlacement> {
        val cardCount = project.cards.size
        if (cardCount <= 0) return emptyList()
        if (isLockedRelationships(project)) {
            return relationshipsPlacements(project, outputTimeSeconds)
        }

        val modelTime = modelTime(project, outputTimeSeconds)
        if (modelTime >= modelDuration(project)) return emptyList()

        val visibleCards = project.model.visibleCards
        val initialCount = min(cardCount, visibleCards)
        val parts = timelineParts(project)
        val scrollStart = parts.introSeconds

        if (modelTime < scrollStart) {
            return buildList {
                for (index in 0 until initialCount) {
                    val localTime = modelTime - parts.preludeSeconds - index * parts.revealSeconds
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
        val rawShift = (scrollElapsed / parts.scrollSeconds).coerceAtMost(maximumShift.toFloat())
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
                    parts.preludeSeconds + index * parts.revealSeconds + BADGE_DELAY_SECONDS
                } else {
                    // The reference badge enters just before the incoming card reaches slot four.
                    scrollStart + (index - initialCount + 1) * parts.scrollSeconds - BADGE_DELAY_SECONDS
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

    private fun relationshipsPlacements(
        project: CtsProject,
        outputTimeSeconds: Float,
    ): List<CardPlacement> {
        val frame = relationshipsSourceFrame(project, outputTimeSeconds)
        val cardCount = project.cards.size
        val contentEnd = relationshipsContentEndFrame(cardCount)
        if (frame >= contentEnd) {
            if (!project.showOutro || frame >= contentEnd + RELATIONSHIPS_END_WIPE_FRAMES +
                RELATIONSHIPS_END_RISE_FRAMES + RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            ) return emptyList()
            val local = frame - contentEnd
            val x = when {
                local <= 32 -> lerp(2f, 0f, smoothStep(local / 32f))
                local <= 42 -> lerp(0f, 320f / 480f, 1f - (1f - (local - 32) / 10f).coerceIn(0f, 1f).let { it * it * it })
                local <= 62 -> lerp(320f / 480f, 928f / 480f, 1f - (1f - (local - 42) / 20f).coerceIn(0f, 1f).let { it * it * it })
                local <= 77 -> lerp(928f / 480f, 780f / 480f, smoothStep((local - 62) / 15f))
                else -> 780f / 480f
            }
            return listOf(
                CardPlacement(
                    cardIndex = cardCount - 1,
                    xInCards = x,
                    bodyReveal = 1f,
                    badgeVisible = true,
                    badgeSettle = 1f,
                    badgeRect = relationshipsBadgeRect(61),
                ),
            )
        }

        if (frame < RELATIONSHIPS_CONTINUOUS_START_FRAME) {
            return buildList {
                for (index in 0 until min(4, cardCount)) {
                    val start = relationshipsOpeningStarts[index]
                    if (frame < start) continue
                    val local = frame - start
                    add(relationshipsPlacement(index, index.toFloat(), local, opening = true))
                }
            }
        }

        val shift = (frame - RELATIONSHIPS_CONTINUOUS_START_FRAME) /
            RELATIONSHIPS_POSITION_STEP_FRAMES
        return buildList {
            for (index in 0 until cardCount) {
                val x = index - shift
                if (x <= -1f || x >= 5f) continue
                val start = if (index < 4) relationshipsOpeningStarts[index] else {
                    RELATIONSHIPS_CONTINUOUS_START_FRAME +
                        (index - 4) * RELATIONSHIPS_CONTINUOUS_STEP_FRAMES
                }
                add(relationshipsPlacement(index, x, frame - start, opening = index < 4))
            }
        }
    }

    private fun relationshipsPlacement(
        index: Int,
        x: Float,
        localFrame: Int,
        opening: Boolean,
    ): CardPlacement {
        val artwork = if (opening) ((localFrame - 58) / 43f).coerceIn(0f, 1f) else 1f
        val title = if (opening) ((localFrame - 96) / 10f).coerceIn(0f, 1f) else 1f
        val description = if (opening) ((localFrame - 105) / 15f).coerceIn(0f, 1f) else 1f
        val textStart = if (opening) 88 else 18
        return CardPlacement(
            cardIndex = index,
            xInCards = x,
            bodyReveal = ((localFrame + 1) / 10f).coerceIn(0f, 1f),
            badgeVisible = localFrame >= 11,
            badgeSettle = relationshipsBadgeScale(localFrame),
            artworkReveal = artwork,
            titleReveal = title,
            descriptionReveal = description,
            badgeRect = relationshipsBadgeRect(localFrame),
            badgeTextAlpha = ((localFrame - textStart) / 32f).coerceIn(0f, 1f),
        )
    }

    private fun relationshipsBadgeScale(localFrame: Int): Float {
        val keys = arrayOf(
            10 to 0f, 11 to 12f / 88f, 15 to 44f / 88f, 19 to 72f / 88f,
            23 to 96f / 88f, 27 to 108f / 88f, 30 to 100f / 88f,
            33 to 1f, 36 to 80f / 88f, 40 to 1f, 44 to 92f / 88f,
            48 to 92f / 88f, 50 to 1f, 60 to 1f,
        )
        if (localFrame <= keys.first().first) return keys.first().second
        if (localFrame >= keys.last().first) return keys.last().second
        val right = keys.indexOfFirst { localFrame <= it.first }
        val (f0, v0) = keys[right - 1]
        val (f1, v1) = keys[right]
        return lerp(v0, v1, (localFrame - f0) / (f1 - f0).toFloat())
    }

    private fun relationshipsBadgeRect(localFrame: Int): NormalizedRect? {
        if (localFrame < 11) return null
        val scale = relationshipsBadgeScale(localFrame)
        val finalWidth = 352f / 480f
        val finalHeight = 348f / 1080f
        val width = finalWidth * scale
        val height = finalHeight * scale
        val centerX = 232f / 480f
        val centerY = 194f / 1080f
        return NormalizedRect(centerX - width / 2f, centerY - height / 2f, width, height)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount.coerceIn(0f, 1f)

    fun fadeAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        if (!project.showOutro) return 1f
        if (isLockedRelationships(project)) {
            val fadeStart = RELATIONSHIPS_END_WIPE_FRAMES + RELATIONSHIPS_END_RISE_FRAMES +
                RELATIONSHIPS_END_HOLD_FRAMES
            return 1f - ((relationshipsOutroLocalFrame(project, outputTimeSeconds) - fadeStart) /
                RELATIONSHIPS_FADE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
        val modelTime = modelTime(project, outputTimeSeconds)
        val fadeStart = modelDuration(project) - FADE_SECONDS
        if (modelTime <= fadeStart) return 1f
        return 1f - smoothStep((modelTime - fadeStart) / FADE_SECONDS.coerceAtLeast(0.001f))
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        if (isLockedRelationships(project)) {
            val frame = if (safeIndex < 4) relationshipsOpeningStarts[safeIndex] else {
                RELATIONSHIPS_CONTINUOUS_START_FRAME +
                    (safeIndex - 4) * RELATIONSHIPS_CONTINUOUS_STEP_FRAMES
            }
            return ((frame - relationshipsIntroOffset(project)).coerceAtLeast(0) /
                RELATIONSHIPS_REFERENCE_FPS.toFloat()).coerceAtMost(duration(project))
        }
        val parts = timelineParts(project)
        val initialCount = min(project.cards.size, project.model.visibleCards)
        val scrollStart = parts.introSeconds
        val targetModelTime = if (safeIndex < project.model.visibleCards) {
            parts.preludeSeconds + safeIndex * parts.revealSeconds + BODY_WIPE_SECONDS
        } else {
            scrollStart + (safeIndex - project.model.visibleCards + 1) * parts.scrollSeconds
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
