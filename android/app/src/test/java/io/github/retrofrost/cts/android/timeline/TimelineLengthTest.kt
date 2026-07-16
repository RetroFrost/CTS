package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLengthTest {
    private fun project(cardCount: Int = 8): CtsProject = CtsProject(
        model = VisualModel.Reference,
        cards = List(cardCount) { index -> CtsCard(title = "Card ${index + 1}") },
    ).normalized()

    @Test
    fun customLengthDoesNotChangeRevealAnimationTiming() {
        val automatic = project()
        val longer = automatic.copy(
            customDurationSeconds = TimelineEngine.automaticDuration(automatic) + 30f,
        )
        val sampleTime = 4.25f

        val automaticPlacement = TimelineEngine.placements(automatic, sampleTime)
            .first { it.cardIndex == 2 }
        val longerPlacement = TimelineEngine.placements(longer, sampleTime)
            .first { it.cardIndex == 2 }

        assertEquals(automaticPlacement.xInCards, longerPlacement.xInCards, 0.0001f)
        assertEquals(automaticPlacement.alpha, longerPlacement.alpha, 0.0001f)
    }

    @Test
    fun longerVideoScrollsMoreSlowlyAtTheSameTimestamp() {
        val automatic = project()
        val longer = automatic.copy(
            customDurationSeconds = TimelineEngine.automaticDuration(automatic) + 30f,
        )
        val sampleTime = TimelineEngine.revealDuration(automatic) + 5f

        val automaticX = TimelineEngine.placements(automatic, sampleTime)
            .first { it.cardIndex == 2 }
            .xInCards
        val longerX = TimelineEngine.placements(longer, sampleTime)
            .first { it.cardIndex == 2 }
            .xInCards

        assertTrue("Longer videos should have advanced less through the strip", longerX > automaticX)
    }

    @Test
    fun automaticModeUsesCalculatedDuration() {
        val automatic = project().copy(customDurationSeconds = null)

        assertEquals(
            TimelineEngine.automaticDuration(automatic),
            TimelineEngine.duration(automatic),
            0.0001f,
        )
    }

    @Test
    fun customDurationCannotRemoveFixedRevealHoldAndFadeSections() {
        val project = project().copy(customDurationSeconds = 1f)

        assertEquals(
            TimelineEngine.minimumDuration(project),
            TimelineEngine.duration(project),
            0.0001f,
        )
    }
}
