package io.github.retrofrost.cts.android.importer

import io.github.retrofrost.cts.android.model.VisualModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPanelAnalysisTest {
    @Test
    fun seamOffsetsNearPanelEdgesSnapToZero() {
        assertEquals(0, VideoPanelAnalysis.normalizeSeamOffset(0, 240))
        assertEquals(0, VideoPanelAnalysis.normalizeSeamOffset(7, 240))
        assertEquals(0, VideoPanelAnalysis.normalizeSeamOffset(237, 240))
        assertEquals(58, VideoPanelAnalysis.normalizeSeamOffset(58, 240))
    }

    @Test
    fun perceptualNeighborsFormOneCardCluster() {
        val observations = listOf(
            observation(time = 1f, hash = 0x1111L),
            observation(time = 2f, hash = 0x1113L),
            observation(time = 3f, hash = 0x7FFF0000L),
        )

        val clusters = VideoPanelAnalysis.cluster(observations)

        assertEquals(2, clusters.size)
        assertEquals(2, clusters.first().observations.size)
    }

    @Test
    fun modelChoiceUsesStrongestLayoutEvidence() {
        val relationships = VideoPanelAnalysis.chooseModel(
            listOf(
                ModelVote(males = 20f, relationships = 82f),
                ModelVote(males = 17f, relationships = 75f),
                ModelVote(males = 22f, relationships = 70f),
            ),
        )
        val males = VideoPanelAnalysis.chooseModel(
            listOf(
                ModelVote(males = 65f, relationships = 18f),
                ModelVote(males = 59f, relationships = 25f),
                ModelVote(males = 70f, relationships = 24f),
            ),
        )

        assertEquals(VisualModel.Relationships, relationships)
        assertEquals(VisualModel.Males, males)
    }

    @Test
    fun reconstructionProgressIsAlwaysBounded() {
        assertEquals(0f, VideoReconstructionProgress(VideoReconstructionPhase.Reading, 3, 0).fraction)
        assertEquals(1f, VideoReconstructionProgress(VideoReconstructionPhase.FindingCards, 12, 10).fraction)
        assertTrue(VideoReconstructionProgress(VideoReconstructionPhase.ReadingText, 3, 10).fraction in 0f..1f)
    }

    private fun observation(time: Float, hash: Long) = PanelObservation(
        timeSeconds = time,
        leftFraction = 0f,
        panelIndex = 0,
        fingerprint = hash,
        badgeScore = 0f,
        quality = 0.8f,
        edgeRatio = 1.5f,
    )
}
