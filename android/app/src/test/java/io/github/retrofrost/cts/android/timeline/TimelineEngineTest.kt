package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {
    @Test
    fun automaticDurationMatchesDesktopCtsFormula() {
        val project = CtsProject(model = VisualModel.Reference)
        // Five cards: 4*2s reveal + 1*(10/3)s scroll + 2s hold + 0.8s fade.
        assertEquals(14.133333f, TimelineEngine.automaticDuration(project), 0.0001f)
    }

    @Test
    fun firstCardStartsInvisibleAndRevealsAsAParentGroup() {
        val project = CtsProject(model = VisualModel.Reference)
        val firstFrame = TimelineEngine.placements(project, 0f)
        assertEquals(1, firstFrame.size)
        assertEquals(0f, firstFrame.first().alpha, 0.0001f)

        val later = TimelineEngine.placements(project, 0.7f)
        assertTrue(later.first().alpha > 0.9f)
    }

    @Test
    fun scrollingMovesEachParentByOneCardWidth() {
        val project = CtsProject(model = VisualModel.Reference)
        val before = TimelineEngine.placements(project, 8f)
        val after = TimelineEngine.placements(project, 8f + SCROLL_SECONDS)
        val beforeSecond = before.first { it.cardIndex == 1 }
        val afterSecond = after.first { it.cardIndex == 1 }
        assertEquals(1f, beforeSecond.xInCards - afterSecond.xInCards, 0.0001f)
    }
}
