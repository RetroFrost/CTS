package io.github.retrofrost.cts.android.timeline

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
