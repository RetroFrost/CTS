package io.github.retrofrost.cts.android.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoReconstructionProgressTest {
    @Test
    fun overallProgressNeverMovesBackwardAcrossPhases() {
        val points = listOf(
            VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1),
            VideoReconstructionProgress(VideoReconstructionPhase.Reading, 1, 1),
            VideoReconstructionProgress(VideoReconstructionPhase.FindingCards, 0, 100),
            VideoReconstructionProgress(VideoReconstructionPhase.FindingCards, 50, 100),
            VideoReconstructionProgress(VideoReconstructionPhase.FindingCards, 100, 100),
            VideoReconstructionProgress(VideoReconstructionPhase.ReadingText, 0, 20),
            VideoReconstructionProgress(VideoReconstructionPhase.ReadingText, 10, 20),
            VideoReconstructionProgress(VideoReconstructionPhase.ReadingText, 20, 20),
            VideoReconstructionProgress(VideoReconstructionPhase.SavingArtwork, 1, 1),
        )
        points.zipWithNext().forEach { (before, after) ->
            assertTrue("${before.percent}% -> ${after.percent}%", after.percent >= before.percent)
        }
        assertEquals(100, points.last().percent)
    }
}
