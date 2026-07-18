package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Test

class IllustratedReferenceVideoTest {
    @Test
    fun illustratedModelShowsFourCardsLikeTheReferenceVideo() {
        assertEquals(4, VisualModel.Illustrated.visibleCards)
    }

    @Test
    fun fortyCardReferenceRunUsesMeasuredScrollRate() {
        val project = CtsProject(
            model = VisualModel.Illustrated,
            cards = List(40) { index -> CtsCard(title = "Card ${index + 1}") },
        ).normalized()

        // Four 2-second reveals + 36 shifts at 4.4 seconds + 2-second hold + 0.8 fade.
        assertEquals(169.2f, TimelineEngine.automaticDuration(project), 0.001f)
    }
}
