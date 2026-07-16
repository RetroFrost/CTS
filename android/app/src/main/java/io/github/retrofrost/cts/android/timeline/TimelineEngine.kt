package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import kotlin.math.max
import kotlin.math.min

const val REVEAL_SECONDS = 2f
const val SCROLL_SECONDS = 10f / 3f
const val END_HOLD_SECONDS = 2f
const val FADE_SECONDS = 0.8f
private const val MINIMUM_SCROLL_WINDOW_SECONDS = 1f

data class CardPlacement(
    val cardIndex: Int,
    /** Horizontal position measured in parent-card widths. */
    val xInCards: Float,
    val alpha: Float,
)

object TimelineEngine {
    fun revealDuration(project: CtsProject): Float {
        if (project.cards.isEmpty()) return 0f
        return min(project.cards.size, project.model.visibleCards) * REVEAL_SECONDS
    }

    private fun maximumShift(project: CtsProject): Int =
        max(0, project.cards.size - project.model.visibleCards)

    fun automaticScrollDuration(project: CtsProject): Float =
        maximumShift(project) * SCROLL_SECONDS

    fun automaticDuration(project: CtsProject): Float {
        if (project.cards.isEmpty()) return 0f
        return revealDuration(project) + automaticScrollDuration(project) +
            END_HOLD_SECONDS + FADE_SECONDS
    }

    /** Smallest duration that can still preserve reveals, a scroll window, hold and fade. */
    fun minimumDuration(project: CtsProject): Float {
        if (project.cards.isEmpty()) return 1f
        val scrollWindow = if (maximumShift(project) > 0) MINIMUM_SCROLL_WINDOW_SECONDS else 0f
        return revealDuration(project) + scrollWindow + END_HOLD_SECONDS + FADE_SECONDS
    }

    /** Exact output length; it never changes card reveal animation timing. */
    fun duration(project: CtsProject): Float =
        project.customDurationSeconds
            ?.coerceAtLeast(minimumDuration(project))
            ?: automaticDuration(project)

    /**
     * Time available for horizontal movement after fixed reveals and before fixed hold/fade.
     * A longer chosen video therefore scrolls more slowly; a shorter one scrolls faster.
     */
    fun scrollDuration(project: CtsProject): Float {
        if (maximumShift(project) <= 0) return 0f
        return (duration(project) - revealDuration(project) - END_HOLD_SECONDS - FADE_SECONDS)
            .coerceAtLeast(MINIMUM_SCROLL_WINDOW_SECONDS)
    }

    fun modelTime(project: CtsProject, outputTimeSeconds: Float): Float =
        outputTimeSeconds.coerceAtLeast(0f)

    fun placements(project: CtsProject, outputTimeSeconds: Float): List<CardPlacement> {
        val cardCount = project.cards.size
        if (cardCount <= 0 || outputTimeSeconds >= duration(project)) return emptyList()

        val modelTime = modelTime(project, outputTimeSeconds)
        val visibleCards = project.model.visibleCards
        val initialCount = min(cardCount, visibleCards)
        val introDuration = revealDuration(project)

        if (modelTime < introDuration) {
            return buildList {
                for (index in 0 until initialCount) {
                    val localTime = modelTime - index * REVEAL_SECONDS
                    if (localTime < 0f) continue
                    add(
                        CardPlacement(
                            cardIndex = index,
                            xInCards = index.toFloat(),
                            alpha = smoothStep(localTime / 0.62f),
                        ),
                    )
                }
            }
        }

        val maximumShift = maximumShift(project).toFloat()
        val scrollWindow = scrollDuration(project)
        val scrollElapsed = (modelTime - introDuration).coerceAtLeast(0f)
        val progress = if (maximumShift <= 0f || scrollWindow <= 0f) {
            1f
        } else {
            (scrollElapsed / scrollWindow).coerceIn(0f, 1f)
        }
        val shift = maximumShift * progress

        return buildList {
            for (index in 0 until cardCount) {
                val x = index - shift
                if (x >= visibleCards || x + 1f <= 0f) continue
                add(CardPlacement(index, x, 1f))
            }
        }
    }

    fun fadeAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        val chosenDuration = duration(project)
        val fadeStart = chosenDuration - FADE_SECONDS
        if (outputTimeSeconds <= fadeStart) return 1f
        return 1f - smoothStep(
            (outputTimeSeconds - fadeStart) / FADE_SECONDS.coerceAtLeast(0.001f),
        )
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        val visible = project.model.visibleCards
        val introTime = revealDuration(project)
        if (safeIndex < visible) return min(duration(project), introTime)

        val shifts = maximumShift(project).coerceAtLeast(1)
        val cardShift = (safeIndex - visible + 1).coerceIn(0, shifts)
        val scrollTimePerCard = scrollDuration(project) / shifts
        return min(duration(project), introTime + cardShift * scrollTimePerCard)
    }

    fun formatTime(seconds: Float): String {
        val total = seconds.coerceAtLeast(0f).toInt()
        val minutes = total / 60
        val remainder = total % 60
        return "%d:%02d".format(minutes, remainder)
    }

    private fun smoothStep(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
