package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Two seconds between the beginning of each opening-card reveal. */
const val REVEAL_SECONDS = 2f

/** One card-width movement, measured from one settled viewport to the next. */
const val SCROLL_SECONDS = 10f / 3f

const val END_HOLD_SECONDS = 2f
const val FADE_SECONDS = 0.8f

/** The opening wipe measured from a card's left edge. */
const val BODY_WIPE_SECONDS = 1.1f

/** The red badge first becomes visible after the card body is mostly uncovered. */
const val BADGE_DELAY_SECONDS = 0.55f

/** Frame-measured duration of the diagonal grow-and-slide badge entrance. */
const val BADGE_SETTLE_SECONDS = 1.45f

/** The supplied reference pauses briefly after the fourth opening card. */
const val INTRO_TAIL_HOLD_SECONDS = 0.8f

data class CardPlacement(
    val cardIndex: Int,
    /** Horizontal position measured in parent-card widths. */
    val xInCards: Float,
    /** Left-to-right opening wipe. Scrolling cards are already fully uncovered. */
    val bodyReveal: Float,
    /** True once the red badge has begun its entrance. */
    val badgeVisible: Boolean,
    /** 0 = half-size beyond the upper-left edge, 1 = settled at its reference position. */
    val badgeSettle: Float,
)

object TimelineEngine {
    fun automaticDuration(project: CtsProject): Float {
        val cardCount = project.cards.size
        if (cardCount <= 0) return 0f
        val visible = project.model.visibleCards
        val reveal = min(cardCount, visible) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val scroll = max(0, cardCount - visible) * SCROLL_SECONDS
        return reveal + scroll + END_HOLD_SECONDS + FADE_SECONDS
    }

    fun duration(project: CtsProject): Float =
        project.customDurationSeconds?.coerceAtLeast(1f) ?: automaticDuration(project)

    fun modelTime(project: CtsProject, outputTimeSeconds: Float): Float {
        val automatic = automaticDuration(project)
        val chosen = duration(project)
        val speed = if (automatic > 0f && chosen > 0f) automatic / chosen else 1f
        return outputTimeSeconds.coerceAtLeast(0f) * speed
    }

    fun placements(project: CtsProject, outputTimeSeconds: Float): List<CardPlacement> {
        val cardCount = project.cards.size
        if (cardCount <= 0) return emptyList()

        val modelTime = modelTime(project, outputTimeSeconds)
        if (modelTime >= automaticDuration(project)) return emptyList()

        val visibleCards = project.model.visibleCards
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
                            badgeSettle = referenceBadgeEase(badgeTime / BADGE_SETTLE_SECONDS),
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
                        badgeSettle = referenceBadgeEase(badgeTime / BADGE_SETTLE_SECONDS),
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
        val visible = project.model.visibleCards
        val initialCount = min(project.cards.size, visible)
        val scrollStart = initialCount * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        val targetModelTime = if (safeIndex < visible) {
            scrollStart
        } else {
            scrollStart + (safeIndex - visible + 1) * SCROLL_SECONDS
        }
        val automatic = automaticDuration(project)
        val chosen = duration(project)
        val speed = if (automatic > 0f && chosen > 0f) automatic / chosen else 1f
        return min(chosen, targetModelTime / speed.coerceAtLeast(0.001f))
    }

    fun formatTime(seconds: Float): String {
        val total = seconds.coerceAtLeast(0f).toInt()
        val minutes = total / 60
        val remainder = total % 60
        return "%d:%02d".format(minutes, remainder)
    }

    /** Material fast-out-slow-in curve for the card wipe and horizontal strip. */
    private fun materialEase(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        var low = 0f
        var high = 1f
        repeat(12) {
            val t = (low + high) / 2f
            val curveX = cubic(t, 0.4f, 0.2f)
            if (curveX < x) low = t else high = t
        }
        return cubic((low + high) / 2f, 0f, 1f)
    }

    /**
     * The badge in the source accelerates immediately, then spends most of its time
     * easing into the final position. A cubic ease-out follows the measured frames.
     */
    private fun referenceBadgeEase(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        val inverse = 1f - t
        return 1f - inverse * inverse * inverse
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
