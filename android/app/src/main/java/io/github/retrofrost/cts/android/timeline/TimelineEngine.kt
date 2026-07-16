package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import kotlin.math.max
import kotlin.math.min

const val REVEAL_SECONDS = 2f
const val SCROLL_SECONDS = 10f / 3f
const val END_HOLD_SECONDS = 2f
const val FADE_SECONDS = 0.8f

data class CardPlacement(
    val cardIndex: Int,
    /** Horizontal position measured in parent-card widths. */
    val xInCards: Float,
    val alpha: Float,
)

object TimelineEngine {
    /** Time at which all normal reveal and scrolling animation has completed. */
    fun motionDuration(project: CtsProject): Float {
        val cardCount = project.cards.size
        if (cardCount <= 0) return 0f
        val visible = project.model.visibleCards
        val reveal = min(cardCount, visible) * REVEAL_SECONDS
        val scroll = max(0, cardCount - visible) * SCROLL_SECONDS
        return reveal + scroll
    }

    fun automaticDuration(project: CtsProject): Float {
        if (project.cards.isEmpty()) return 0f
        return motionDuration(project) + END_HOLD_SECONDS + FADE_SECONDS
    }

    /**
     * Output length only. Changing this value never retimes card motion.
     *
     * A longer duration holds the completed strip before the final fade. A shorter duration
     * simply ends at that timestamp; the reveal and scrolling rates remain unchanged.
     */
    fun duration(project: CtsProject): Float =
        project.customDurationSeconds?.coerceAtLeast(1f) ?: automaticDuration(project)

    /** Animation clock stays one real second per timeline second. */
    fun modelTime(project: CtsProject, outputTimeSeconds: Float): Float =
        outputTimeSeconds.coerceAtLeast(0f)

    fun placements(project: CtsProject, outputTimeSeconds: Float): List<CardPlacement> {
        val cardCount = project.cards.size
        if (cardCount <= 0) return emptyList()
        if (outputTimeSeconds >= duration(project)) return emptyList()

        val modelTime = modelTime(project, outputTimeSeconds)
        val visibleCards = project.model.visibleCards
        val initialCount = min(cardCount, visibleCards)
        val introDuration = initialCount * REVEAL_SECONDS

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

        val scrollElapsed = (modelTime - introDuration).coerceAtLeast(0f)
        val maximumShift = max(0, cardCount - visibleCards).toFloat()
        val shift = min(maximumShift, scrollElapsed / SCROLL_SECONDS)

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
        val fadeLength = min(FADE_SECONDS, chosenDuration)
        val fadeStart = chosenDuration - fadeLength
        if (outputTimeSeconds <= fadeStart) return 1f
        return 1f - smoothStep((outputTimeSeconds - fadeStart) / fadeLength.coerceAtLeast(0.001f))
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        val visible = project.model.visibleCards
        val introTime = min(project.cards.size, visible) * REVEAL_SECONDS
        val normalTime = if (safeIndex < visible) {
            introTime
        } else {
            introTime + (safeIndex - visible + 1) * SCROLL_SECONDS
        }
        return min(duration(project), normalTime)
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
