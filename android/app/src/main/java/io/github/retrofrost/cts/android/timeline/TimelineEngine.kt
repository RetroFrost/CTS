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
    fun automaticDuration(project: CtsProject): Float {
        val cardCount = project.cards.size
        if (cardCount <= 0) return 0f
        val visible = project.model.visibleCards
        val reveal = min(cardCount, visible) * REVEAL_SECONDS
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
        val modelTime = modelTime(project, outputTimeSeconds)
        val fadeStart = automaticDuration(project) - FADE_SECONDS
        if (modelTime <= fadeStart) return 1f
        return 1f - smoothStep((modelTime - fadeStart) / FADE_SECONDS)
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        val visible = project.model.visibleCards
        val introTime = min(project.cards.size, visible) * REVEAL_SECONDS
        val modelTime = if (safeIndex < visible) {
            introTime
        } else {
            introTime + (safeIndex - visible + 1) * SCROLL_SECONDS
        }
        val automatic = automaticDuration(project)
        val chosen = duration(project)
        val speed = if (automatic > 0f && chosen > 0f) automatic / chosen else 1f
        return min(chosen, modelTime / speed.coerceAtLeast(0.001f))
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
