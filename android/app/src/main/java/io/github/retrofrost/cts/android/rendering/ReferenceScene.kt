package io.github.retrofrost.cts.android.rendering

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.timeline.CardPlacement
import io.github.retrofrost.cts.android.timeline.TimelineEngine

/**
 * Immutable description of a single output frame.
 *
 * Timeline sampling lives here so preview and export cannot accidentally use
 * different frame boundaries, fades, or model-specific outro clocks.
 */
data class ReferenceScene(
    val outputTimeSeconds: Float,
    val customIntroVisible: Boolean,
    val introCreditsVisible: Boolean,
    val placements: List<CardPlacement>,
    val relationshipsSourceFrame: Int,
    val relationshipsDisclaimerAlpha: Float,
    val relationshipsOutroLocalFrame: Int,
    val outroCoverProgress: Float,
    val outroContentAlpha: Float,
    val fadeAlpha: Float,
    val relationships: Boolean,
)

object ReferenceSceneBuilder {
    fun build(project: CtsProject, outputTimeSeconds: Float): ReferenceScene {
        val time = outputTimeSeconds.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f
        return ReferenceScene(
            outputTimeSeconds = time,
            customIntroVisible = TimelineEngine.customIntroVisible(project, time),
            introCreditsVisible = TimelineEngine.introCreditsVisible(project, time),
            placements = TimelineEngine.placements(project, time),
            relationshipsSourceFrame = TimelineEngine.relationshipsSourceFrame(project, time),
            relationshipsDisclaimerAlpha = TimelineEngine.relationshipsDisclaimerAlpha(project, time),
            relationshipsOutroLocalFrame = TimelineEngine.relationshipsOutroLocalFrame(project, time),
            outroCoverProgress = TimelineEngine.outroCoverProgress(project, time),
            outroContentAlpha = TimelineEngine.outroContentAlpha(project, time),
            fadeAlpha = TimelineEngine.fadeAlpha(project, time).coerceIn(0f, 1f),
            relationships = project.model == VisualModel.Relationships,
        )
    }
}
