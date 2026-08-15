package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.DurationRuntime
import io.github.retrofrost.cts.android.model.ModelMode
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.shared.SharedContract
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val REVEAL_SECONDS = SharedContract.REVEAL_SECONDS
const val SCROLL_SECONDS = SharedContract.SCROLL_SECONDS
const val END_HOLD_SECONDS = SharedContract.END_HOLD_SECONDS
const val OUTRO_COVER_SECONDS = SharedContract.OUTRO_COVER_SECONDS
const val OUTRO_CONTENT_DELAY_SECONDS = SharedContract.OUTRO_CONTENT_DELAY_SECONDS
const val OUTRO_HOLD_SECONDS = SharedContract.OUTRO_HOLD_SECONDS
const val FADE_SECONDS = SharedContract.FADE_SECONDS
const val BODY_WIPE_SECONDS = SharedContract.BODY_WIPE_SECONDS
const val BADGE_DELAY_SECONDS = SharedContract.BADGE_DELAY_SECONDS
const val BADGE_SETTLE_SECONDS = SharedContract.BADGE_SETTLE_SECONDS
const val INTRO_TAIL_HOLD_SECONDS = SharedContract.INTRO_TAIL_HOLD_SECONDS
const val MIN_SCROLL_STEP_SECONDS = SharedContract.MIN_SCROLL_STEP_SECONDS

/** One card in the reference timeline. The body is always complete; xInCards performs motion. */
data class CardPlacement(
    val cardIndex: Int,
    val xInCards: Float,
    val bodyReveal: Float,
    val badgeVisible: Boolean,
    val badgeSettle: Float,
    val artworkReveal: Float = 1f,
    val titleReveal: Float = 1f,
    val descriptionReveal: Float = 1f,
    val badgeRect: NormalizedRect? = null,
    val badgeTextAlpha: Float = 1f,
    val badgeAgeSeconds: Float = Float.POSITIVE_INFINITY,
    val badgeAffine: BadgeAffine = BadgeAffine.Identity,
    val bodyTransform: ExactReferenceFrames.BodyTransform? = null,
)

/** Source-space affine used by the measured Males badge renderer (480 x 430). */
data class BadgeAffine(
    val m00: Float,
    val m01: Float,
    val m10: Float,
    val m11: Float,
    val tx: Float,
    val ty: Float,
) {
    companion object {
        val Identity = BadgeAffine(1f, 0f, 0f, 1f, 0f, 0f)
    }
}

private data class TimelineParts(
    val preludeSeconds: Float,
    val revealSeconds: Float,
    val scrollSeconds: Float,
    val introSeconds: Float,
    val scrollSteps: Int,
    val automaticScrollSeconds: Float,
    val fixedTailSeconds: Float,
)

object TimelineEngine {
    /** Exact Reference maps one output frame to one measured source frame. */
    const val EXACT_REFERENCE_PLAYBACK_RATE = 1f
    const val MALES_REFERENCE_FRAMES = 16_741
    const val MALES_REFERENCE_FPS = 60
    private const val MALES_CANONICAL_CARD_COUNT = 78
    private const val MALES_CONVEYOR_START_FRAME = 528
    private const val MALES_STEADY_START_FRAME = 620
    private const val MALES_STEADY_START_SHIFT = 0.614439f
    private const val MALES_STEADY_END_FRAME = 16_335
    private const val MALES_STEADY_END_SHIFT = 74f
    private const val MALES_STEADY_PERIOD_FRAMES = 214.14294f
    private const val MALES_FINAL_HOLD_FRAMES = 37
    private const val MALES_OUTRO_START_FRAME = 16_372
    private const val MALES_END_WIPE_FRAMES = 25
    private const val MALES_END_RISE_FRAMES = 23
    private const val MALES_END_HOLD_FRAMES = 273
    private const val MALES_FADE_FRAMES = 48
    private const val MALES_BADGE_DEEMPHASIS_SECONDS = 1f
    private const val MALES_BADGE_ACTIVE_SCALE = 1f
    private const val MALES_BADGE_MEDIUM_SCALE = 272f / 298f
    private const val MALES_BADGE_SMALL_SCALE = 248f / 298f

    // Full-video separator tracking from the canonical MP4. The opening hand-off is
    // not a generic easing curve: it has a one-time acceleration before the steady conveyor.
    private val malesPhasePullKeys = arrayOf(
        528f to 0.000000f,
        535f to 0.035055f,
        540f to 0.047559f,
        550f to 0.089242f,
        560f to 0.160102f,
        570f to 0.230962f,
        580f to 0.301822f,
        590f to 0.385186f,
        600f to 0.464382f,
        610f to 0.535242f,
        620f to MALES_STEADY_START_SHIFT,
    )
    const val MALES_BODY_SECONDS = 1.34f
    private const val MALES_CONVEYOR_STRIDE = 477f / 480f
    private const val MALES_PHASE_PULL_START_FRAME = 535
    private const val MALES_PHASE_PULL_END_FRAME = 620
    private const val MALES_POST_BADGE_DELAY = 2.06f
    private const val MALES_POST_BADGE_DURATION = 1.10f
    private const val MALES_BADGE_ENTRY_END = 2.90f
    private const val MALES_POST_BADGE_SPEED = MALES_BADGE_ENTRY_END / MALES_POST_BADGE_DURATION

    private val malesBodyProgressKeys = arrayOf(
        0.000f to 0.000f, 0.033f to 0.000f, 0.083f to 0.019f,
        0.166f to 0.101f, 0.250f to 0.300f, 0.333f to 0.515f,
        0.416f to 0.653f, 0.500f to 0.746f, 0.583f to 0.813f,
        0.666f to 0.864f, 0.750f to 0.901f, 0.833f to 0.931f,
        0.916f to 0.954f, 1.000f to 0.971f, 1.083f to 0.983f,
        1.166f to 0.994f, 1.250f to 0.998f, 1.333f to 1.000f,
    )

    private val malesOpeningBadgeKeys = arrayOf(
        floatArrayOf(0.00f, 0.3200f, 0.0400f, -0.4300f, 1.0500f, -230.00f, -220.00f),
        floatArrayOf(0.10f, 0.3300f, 0.0400f, -0.4300f, 1.0500f, -190.00f, -170.00f),
        floatArrayOf(0.20f, 0.3400f, 0.0400f, -0.4300f, 1.0500f, -185.00f, -130.00f),
        floatArrayOf(0.30f, 0.3500f, 0.0380f, -0.4300f, 1.0500f, -215.00f, -115.00f),
        floatArrayOf(0.40f, 0.3600f, 0.0360f, -0.4300f, 1.0500f, -154.00f, -107.00f),
        floatArrayOf(0.50f, 0.7200f, -0.0300f, -0.7200f, 2.3500f, -160.00f, -454.00f),
        floatArrayOf(0.65f, 0.9600f, -0.0250f, -0.5400f, 2.0500f, -145.00f, -325.00f),
        floatArrayOf(0.80f, 1.1818f, -0.0169f, -0.3636f, 1.7548f, -150.25f, -235.22f),
        floatArrayOf(0.90f, 1.2222f, -0.0181f, -0.2694f, 1.6357f, -141.90f, -203.87f),
        floatArrayOf(1.00f, 1.2492f, 0.0509f, -0.2054f, 1.5002f, -152.51f, -164.83f),
        floatArrayOf(1.20f, 1.2559f, 0.0158f, -0.1010f, 1.4099f, -121.12f, -140.77f),
        floatArrayOf(1.50f, 1.2088f, -0.0758f, -0.0337f, 1.2452f, -56.12f, -83.62f),
        floatArrayOf(1.80f, 1.1302f, -0.0309f, -0.0064f, 1.1508f, -37.57f, -46.97f),
        floatArrayOf(2.30f, 1.0808f, 0.0209f, 0.0000f, 1.0698f, -25.80f, -16.16f),
        floatArrayOf(2.50f, 1.0202f, 0.0110f, 0.0067f, 1.0114f, -9.13f, -1.95f),
        floatArrayOf(2.70f, 1.0067f, 0.0114f, -0.0067f, 0.9886f, -3.95f, 5.95f),
        floatArrayOf(2.90f, 1.0000f, 0.0000f, 0.0000f, 1.0000f, 0.00f, 0.00f),
    )

    private val malesPostBadgeKeys = arrayOf(
        floatArrayOf(0.00f, 1.120f, -420f), floatArrayOf(0.28f, 1.118f, -376f),
        floatArrayOf(0.55f, 1.112f, -292f), floatArrayOf(0.82f, 1.102f, -194f),
        floatArrayOf(1.05f, 1.090f, -105f), floatArrayOf(1.25f, 1.075f, -38f),
        floatArrayOf(1.42f, 1.058f, 16f), floatArrayOf(1.60f, 1.034f, -9f),
        floatArrayOf(1.80f, 1.016f, 5f), floatArrayOf(2.02f, 1.005f, -2f),
        floatArrayOf(2.25f, 1.000f, 0f), floatArrayOf(2.90f, 1.000f, 0f),
    )
    const val RELATIONSHIPS_REFERENCE_FRAMES = 11_130
    const val RELATIONSHIPS_REFERENCE_FPS = 60
    const val RELATIONSHIPS_REFERENCE_SECONDS = 185.5f
    const val RELATIONSHIPS_INTRO_FRAMES = 374
    const val RELATIONSHIPS_INTRO_OVERLAY_END_FRAME = 550
    const val RELATIONSHIPS_CONTINUOUS_START_FRAME = 896
    const val RELATIONSHIPS_CONTINUOUS_STEP_FRAMES = 266
    const val RELATIONSHIPS_CONTENT_END_CANONICAL_FRAME = 10_738
    const val RELATIONSHIPS_END_WIPE_FRAMES = 42
    const val RELATIONSHIPS_END_RISE_FRAMES = 50
    // Raw source luminance begins falling at f11076 and remains non-black on
    // the last source frame. Keep the 392-frame outro total while preserving
    // its measured 54-frame fade instead of the old 30-frame approximation.
    const val RELATIONSHIPS_END_HOLD_FRAMES = 246
    const val RELATIONSHIPS_FADE_FRAMES = 54
    private const val RELATIONSHIPS_POSITION_STEP_FRAMES = 265.7158648f
    private val relationshipsOpeningStarts = intArrayOf(374, 521, 656, 795)
    private val relationshipsOpeningEnds = intArrayOf(521, 656, 795, 896)

    private fun isSealedReference(project: CtsProject): Boolean =
        project.model == VisualModel.Males || project.model == VisualModel.Relationships

    private fun isLockedRelationships(project: CtsProject): Boolean =
        project.model == VisualModel.Relationships

    private fun isCanonicalMales(project: CtsProject): Boolean =
        project.model == VisualModel.Males && project.cards.size == MALES_CANONICAL_CARD_COUNT

    private fun isCanonicalRelationships(project: CtsProject): Boolean =
        project.model == VisualModel.Relationships && project.cards.size == 40

    private fun playbackRate(project: CtsProject): Float =
        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) {
            EXACT_REFERENCE_PLAYBACK_RATE
        } else {
            1f
        }

    private fun sourceFrameAt(seconds: Float, fps: Int): Int =
        (seconds * fps).roundToInt()

    fun customIntroDuration(project: CtsProject): Float = if (
        !project.introVideo.uri.isNullOrBlank()
    ) {
        project.introVideo.durationSeconds.coerceAtLeast(0f)
    } else {
        0f
    }

    fun customIntroVisible(project: CtsProject, outputTimeSeconds: Float): Boolean =
        customIntroDuration(project) > 0f &&
            outputTimeSeconds >= 0f &&
            outputTimeSeconds < customIntroDuration(project)

    private fun contentOutputTime(project: CtsProject, outputTimeSeconds: Float): Float =
        (outputTimeSeconds - customIntroDuration(project)).coerceAtLeast(0f)

    private fun builtInIntroEnabled(project: CtsProject): Boolean =
        isSealedReference(project) || project.showIntro

    private fun outputDuration(project: CtsProject, modelDurationSeconds: Float): Float =
        modelDurationSeconds / playbackRate(project)

    private fun preludeSeconds(project: CtsProject): Float = when (project.model) {
        VisualModel.Males -> 0f
        VisualModel.Relationships -> if (builtInIntroEnabled(project)) RELATIONSHIPS_INTRO_FRAMES / 60f else 0f
    }

    private fun revealSeconds(project: CtsProject): Float = when (project.model) {
        VisualModel.Males -> REVEAL_SECONDS
        VisualModel.Relationships -> RELATIONSHIPS_CONTINUOUS_STEP_FRAMES / 60f
    }

    private fun baseScrollSeconds(project: CtsProject): Float = when (project.model) {
        VisualModel.Males -> SCROLL_SECONDS
        VisualModel.Relationships -> RELATIONSHIPS_CONTINUOUS_STEP_FRAMES / 60f
    }

    private fun tailSeconds(project: CtsProject): Float = if (isSealedReference(project) || project.showOutro) {
        END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS + OUTRO_HOLD_SECONDS + FADE_SECONDS
    } else {
        END_HOLD_SECONDS
    }

    private fun timelineParts(project: CtsProject): TimelineParts {
        val cardCount = project.cards.size
        if (cardCount <= 0) return TimelineParts(0f, REVEAL_SECONDS, SCROLL_SECONDS, 0f, 0, 0f, 0f)
        val visible = project.model.visibleCards
        val prelude = preludeSeconds(project)
        val reveal = revealSeconds(project)
        val scrollSeconds = baseScrollSeconds(project)
        val intro = prelude + min(cardCount, visible) * reveal + INTRO_TAIL_HOLD_SECONDS
        val scrollSteps = max(0, cardCount - visible)
        val automaticScroll = scrollSteps * scrollSeconds
        return TimelineParts(prelude, reveal, scrollSeconds, intro, scrollSteps, automaticScroll, tailSeconds(project))
    }

    private fun modelDuration(project: CtsProject): Float {
        val parts = timelineParts(project)
        return parts.introSeconds + parts.automaticScrollSeconds + parts.fixedTailSeconds
    }

    private fun relationshipsContentEndFrame(cardCount: Int): Int = when {
        cardCount <= 0 -> 0
        cardCount <= 4 -> relationshipsOpeningEnds[cardCount - 1]
        else -> RELATIONSHIPS_CONTINUOUS_START_FRAME +
            (cardCount - 4 + 1) * RELATIONSHIPS_CONTINUOUS_STEP_FRAMES
    }

    private fun relationshipsIntroOffset(project: CtsProject): Int =
        if (builtInIntroEnabled(project)) 0 else RELATIONSHIPS_INTRO_FRAMES

    fun relationshipsSourceFrame(project: CtsProject, outputTimeSeconds: Float): Int =
        sourceFrameAt(
            contentOutputTime(project, outputTimeSeconds) * playbackRate(project),
            RELATIONSHIPS_REFERENCE_FPS,
        ) +
            relationshipsIntroOffset(project)

    fun relationshipsOutroLocalFrame(project: CtsProject, outputTimeSeconds: Float): Int =
        relationshipsSourceFrame(project, outputTimeSeconds) - relationshipsContentEndFrame(project.cards.size)

    fun automaticDuration(project: CtsProject): Float {
        val parts = timelineParts(project)
        if (project.model == VisualModel.Males && isSealedReference(project)) {
            return customIntroDuration(project) +
                malesReferenceFrameCount(project.cards.size) / MALES_REFERENCE_FPS.toFloat()
        }
        if (isLockedRelationships(project)) {
            val content = relationshipsContentEndFrame(project.cards.size)
            val outro = if (isSealedReference(project) || project.showOutro) {
                RELATIONSHIPS_END_WIPE_FRAMES + RELATIONSHIPS_END_RISE_FRAMES +
                    RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            } else 0
            val sourceDuration = (content - relationshipsIntroOffset(project) + outro)
                .coerceAtLeast(0) / RELATIONSHIPS_REFERENCE_FPS.toFloat()
            return customIntroDuration(project) + outputDuration(project, sourceDuration)
        }
        return customIntroDuration(project) + outputDuration(
            project,
            parts.introSeconds + parts.automaticScrollSeconds + parts.fixedTailSeconds,
        )
    }

    fun duration(project: CtsProject): Float {
        val parts = timelineParts(project)
        val automatic = automaticDuration(project)
        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) return automatic
        val custom = DurationRuntime.resolve(project.customDurationSeconds) ?: return automatic
        if (parts.scrollSteps <= 0) return automatic
        val minimum = customIntroDuration(project) + parts.introSeconds +
            parts.scrollSteps * MIN_SCROLL_STEP_SECONDS +
            parts.fixedTailSeconds
        return max(minimum, custom)
    }

    private fun chosenScrollDuration(project: CtsProject, parts: TimelineParts): Float {
        if (parts.scrollSteps <= 0) return 0f
        if (isLockedRelationships(project)) return parts.automaticScrollSeconds
        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) return parts.automaticScrollSeconds
        if (DurationRuntime.resolve(project.customDurationSeconds) == null) {
            return parts.automaticScrollSeconds
        }
        return max(
            parts.scrollSteps * MIN_SCROLL_STEP_SECONDS,
            duration(project) - customIntroDuration(project) - parts.introSeconds - parts.fixedTailSeconds,
        )
    }

    fun secondsPerCard(project: CtsProject): Float {
        val parts = timelineParts(project)
        if (parts.scrollSteps <= 0) return 0f
        return chosenScrollDuration(project, parts) / parts.scrollSteps / playbackRate(project)
    }

    fun modelTime(project: CtsProject, outputTimeSeconds: Float): Float {
        val output = contentOutputTime(project, outputTimeSeconds)
        val parts = timelineParts(project)
        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) return output * playbackRate(project)
        if (
            DurationRuntime.resolve(project.customDurationSeconds) == null ||
            parts.scrollSteps <= 0 ||
            parts.automaticScrollSeconds <= 0f
        ) return output
        if (output <= parts.introSeconds) return output

        val chosenScroll = chosenScrollDuration(project, parts)
        if (output < parts.introSeconds + chosenScroll) {
            val progress = (output - parts.introSeconds) / chosenScroll.coerceAtLeast(0.001f)
            return parts.introSeconds + progress * parts.automaticScrollSeconds
        }
        return parts.introSeconds + parts.automaticScrollSeconds +
            (output - parts.introSeconds - chosenScroll)
    }

    private fun outputTimeForModelTime(project: CtsProject, modelTimeSeconds: Float): Float {
        val modelTime = modelTimeSeconds.coerceAtLeast(0f)
        val parts = timelineParts(project)
        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) {
            return customIntroDuration(project) + modelTime / playbackRate(project)
        }
        if (
            DurationRuntime.resolve(project.customDurationSeconds) == null ||
            parts.scrollSteps <= 0 ||
            parts.automaticScrollSeconds <= 0f
        ) return customIntroDuration(project) + modelTime
        if (modelTime <= parts.introSeconds) return customIntroDuration(project) + modelTime

        val chosenScroll = chosenScrollDuration(project, parts)
        if (modelTime < parts.introSeconds + parts.automaticScrollSeconds) {
            val progress = (modelTime - parts.introSeconds) /
                parts.automaticScrollSeconds.coerceAtLeast(0.001f)
            return customIntroDuration(project) + parts.introSeconds + progress * chosenScroll
        }
        return customIntroDuration(project) + parts.introSeconds + chosenScroll +
            (modelTime - parts.introSeconds - parts.automaticScrollSeconds)
    }

    private fun scrollEnd(project: CtsProject): Float {
        val parts = timelineParts(project)
        return parts.introSeconds + parts.automaticScrollSeconds
    }

    private fun outroStart(project: CtsProject): Float = scrollEnd(project) + END_HOLD_SECONDS

    fun introCreditsVisible(project: CtsProject, outputTimeSeconds: Float): Boolean {
        if (
            project.cards.isEmpty() || project.model != VisualModel.Males ||
            (!isSealedReference(project) && !project.showDisclaimer) ||
            customIntroVisible(project, outputTimeSeconds)
        ) return false
        return modelTime(project, outputTimeSeconds) < timelineParts(project).introSeconds
    }

    fun relationshipsInfinityProgress(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model != VisualModel.Relationships || !builtInIntroEnabled(project)) return 0f
        return relationshipsSourceFrame(project, outputTimeSeconds) /
            RELATIONSHIPS_INTRO_FRAMES.toFloat()
    }

    fun relationshipsDisclaimerAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model != VisualModel.Relationships || (!isSealedReference(project) && !project.showDisclaimer)) return 0f
        val frame = relationshipsSourceFrame(project, outputTimeSeconds)
        if (frame !in 434 until 795) return 0f
        return ((frame - 434) / 45f).coerceIn(0f, 1f)
    }

    fun outroCoverProgress(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model == VisualModel.Males && isSealedReference(project)) {
            val frame = sourceFrameAt(contentOutputTime(project, outputTimeSeconds), MALES_REFERENCE_FPS)
            if (isCanonicalMales(project)) return ExactReferenceFrames.malesOutroCoverProgress(frame)
            return smoothStep((frame - malesOutroStartFrame(project.cards.size)) / MALES_END_WIPE_FRAMES.toFloat())
        }
        if (!isSealedReference(project) && !project.showOutro) return 0f
        if (isLockedRelationships(project)) {
            return (relationshipsOutroLocalFrame(project, outputTimeSeconds) /
                RELATIONSHIPS_END_WIPE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
        val elapsed = modelTime(project, outputTimeSeconds) - outroStart(project)
        return materialEase(elapsed / OUTRO_COVER_SECONDS.coerceAtLeast(0.001f))
    }

    fun outroContentAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model == VisualModel.Males && isSealedReference(project)) {
            val frame = sourceFrameAt(contentOutputTime(project, outputTimeSeconds), MALES_REFERENCE_FPS)
            val riseStart = malesOutroStartFrame(project.cards.size) + MALES_END_WIPE_FRAMES
            return ((frame - riseStart) / MALES_END_RISE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
        if (!isSealedReference(project) && !project.showOutro) return 0f
        if (isLockedRelationships(project)) {
            return ((relationshipsOutroLocalFrame(project, outputTimeSeconds) - 35) / 28f)
                .coerceIn(0f, 1f)
        }
        val start = outroStart(project) + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS
        return smoothStep((modelTime(project, outputTimeSeconds) - start) / 0.12f)
    }

    fun placements(project: CtsProject, outputTimeSeconds: Float): List<CardPlacement> {
        val cardCount = project.cards.size
        if (cardCount <= 0) return emptyList()
        if (isLockedRelationships(project)) {
            return relationshipsPlacements(project, outputTimeSeconds)
        }

        val modelTime = modelTime(project, outputTimeSeconds)
        val activeDuration = if (project.model == VisualModel.Males && isSealedReference(project)) {
            malesReferenceFrameCount(project.cards.size) / MALES_REFERENCE_FPS.toFloat()
        } else {
            modelDuration(project)
        }
        if (modelTime >= activeDuration) return emptyList()

        val visibleCards = project.model.visibleCards
        val initialCount = min(cardCount, visibleCards)
        val parts = timelineParts(project)
        val scrollStart = parts.introSeconds

        val lockedMales = project.model == VisualModel.Males && isSealedReference(project)
        if (modelTime < scrollStart) {
            return buildList {
                for (index in 0 until initialCount) {
                    val localTime = modelTime - parts.preludeSeconds - index * parts.revealSeconds
                    if (localTime < 0f) continue
                    val slide = if (lockedMales) malesBodyProgress(localTime) else {
                        materialEase(localTime / BODY_WIPE_SECONDS)
                    }
                    val badgeTime = localTime - BADGE_DELAY_SECONDS
                    val badgeAge = if (lockedMales) localTime else badgeTime
                    add(
                        CardPlacement(
                            cardIndex = index,
                            // Each opening card comes from exactly one slot to its left.
                            xInCards = if (lockedMales) {
                                val sourceFrame = sourceFrameAt(modelTime, MALES_REFERENCE_FPS)
                                ExactReferenceFrames.malesOpeningCardX(sourceFrame, index)?.div(480f)
                                    ?: (index - 1f + slide)
                            } else index - 1f + slide,
                            bodyReveal = 1f,
                            badgeVisible = if (lockedMales) localTime >= 0f else badgeTime >= 0f,
                            badgeSettle = if (lockedMales) {
                                (badgeAge / MALES_BADGE_ENTRY_END).coerceIn(0f, 1f)
                            } else materialEase(badgeTime / BADGE_SETTLE_SECONDS),
                            badgeAgeSeconds = badgeAge,
                            badgeAffine = if (lockedMales) malesOpeningBadgeAffine(badgeAge) else BadgeAffine.Identity,
                        ),
                    )
                }
            }
        }

        val scrollElapsed = (modelTime - scrollStart).coerceAtLeast(0f)
        val maximumShift = max(0, cardCount - visibleCards)
        val rawShift = (scrollElapsed / parts.scrollSeconds).coerceAtMost(maximumShift.toFloat())
        val completedShifts = floor(rawShift).toInt().coerceAtMost(maximumShift)
        val cycleProgress = rawShift - completedShifts
        val easedShift = if (lockedMales) {
            val sourceFrame = sourceFrameAt(modelTime, MALES_REFERENCE_FPS)
            malesConveyorShift(
                sourceFrame = sourceFrame,
                maximumShift = maximumShift.toFloat(),
                exact = isCanonicalMales(project),
            )
        } else if (completedShifts >= maximumShift) {
            maximumShift.toFloat()
        } else {
            completedShifts + materialEase(cycleProgress)
        }

        return buildList {
            for (index in 0 until cardCount) {
                val sourceFrame = sourceFrameAt(modelTime, MALES_REFERENCE_FPS)
                val x = if (lockedMales && isCanonicalMales(project)) {
                    ExactReferenceFrames.malesConveyorCardX(sourceFrame, index)?.div(480f)
                        ?: (index - easedShift)
                } else index - easedShift
                if (x >= visibleCards || x + 1f <= 0f) continue

                val badgeStart = if (index < initialCount) {
                    parts.preludeSeconds + index * parts.revealSeconds + BADGE_DELAY_SECONDS
                } else {
                    // The reference badge enters just before the incoming card reaches slot four.
                    scrollStart + (index - initialCount + 1) * parts.scrollSeconds - BADGE_DELAY_SECONDS
                }
                val badgeTime = modelTime - badgeStart
                val exactBadgeAge = if (index < initialCount) {
                    modelTime - parts.preludeSeconds - index * parts.revealSeconds
                } else {
                    val cardStart = malesCardStartFrame(index) / MALES_REFERENCE_FPS
                    (modelTime - cardStart - MALES_POST_BADGE_DELAY) * MALES_POST_BADGE_SPEED
                }
                add(
                    CardPlacement(
                        cardIndex = index,
                        xInCards = x,
                        bodyReveal = 1f,
                        badgeVisible = if (lockedMales) exactBadgeAge >= 0f else badgeTime >= 0f,
                        badgeSettle = if (lockedMales) {
                            (exactBadgeAge / MALES_BADGE_ENTRY_END).coerceIn(0f, 1f)
                        } else materialEase(badgeTime / BADGE_SETTLE_SECONDS),
                        badgeAgeSeconds = exactBadgeAge,
                        badgeAffine = if (!lockedMales) {
                            BadgeAffine.Identity
                        } else {
                            malesMeasuredBadgeAffine(
                                index = index,
                                age = exactBadgeAge,
                                sourceFrame = sourceFrame,
                                cardCount = cardCount,
                                initialCount = initialCount,
                            )
                        },
                    ),
                )
            }
        }
    }

    private fun relationshipsPlacements(
        project: CtsProject,
        outputTimeSeconds: Float,
    ): List<CardPlacement> {
        val frame = relationshipsSourceFrame(project, outputTimeSeconds)
        val cardCount = project.cards.size
        val contentEnd = relationshipsContentEndFrame(cardCount)
        if (frame >= contentEnd) {
            if ((!isSealedReference(project) && !project.showOutro) ||
                frame >= contentEnd + RELATIONSHIPS_END_WIPE_FRAMES +
                RELATIONSHIPS_END_RISE_FRAMES + RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            ) return emptyList()
            val local = frame - contentEnd
            val x = ExactReferenceFrames.relationshipsFinalLastCardX(frame)?.div(480f) ?: when {
                local <= 32 -> lerp(2f, 0f, smoothStep(local / 32f))
                local <= 42 -> lerp(0f, 320f / 480f, 1f - (1f - (local - 32) / 10f).coerceIn(0f, 1f).let { it * it * it })
                local <= 62 -> lerp(320f / 480f, 928f / 480f, 1f - (1f - (local - 42) / 20f).coerceIn(0f, 1f).let { it * it * it })
                local <= 77 -> lerp(928f / 480f, 780f / 480f, smoothStep((local - 62) / 15f))
                else -> 780f / 480f
            }
            return listOf(
                CardPlacement(
                    cardIndex = cardCount - 1,
                    xInCards = x,
                    bodyReveal = 1f,
                    badgeVisible = true,
                    badgeSettle = 1f,
                    badgeRect = relationshipsBadgeRect(61),
                ),
            )
        }

        if (frame < RELATIONSHIPS_CONTINUOUS_START_FRAME) {
            return buildList {
                for (index in 0 until min(4, cardCount)) {
                    val start = ExactReferenceFrames.relationshipsCardStartFrame(index)
                    if (frame < start) continue
                    val local = frame - start
                    add(relationshipsPlacement(index, index.toFloat(), local, opening = true, sourceFrame = frame))
                }
            }
        }

        return buildList {
            for (index in 0 until cardCount) {
                val finalCardX = if (index == cardCount - 1) {
                    ExactReferenceFrames.relationshipsFinalLastCardX(frame)?.div(480f)
                } else null
                val x = finalCardX
                    ?: ExactReferenceFrames.relationshipsConveyorCardX(frame, index)?.div(480f)
                    ?: index - (frame - RELATIONSHIPS_CONTINUOUS_START_FRAME) /
                    RELATIONSHIPS_POSITION_STEP_FRAMES
                if (x <= -1f || x >= 5f) continue
                val start = ExactReferenceFrames.relationshipsCardStartFrame(index)
                add(relationshipsPlacement(index, x, frame - start, opening = index < 4, sourceFrame = frame))
            }
        }
    }

    private fun malesConveyorShift(
        sourceFrame: Int,
        maximumShift: Float,
        exact: Boolean,
    ): Float {
        if (maximumShift <= 0f || sourceFrame <= MALES_CONVEYOR_START_FRAME) return 0f
        if (exact) {
            val cardX = ExactReferenceFrames.malesConveyorCardX(sourceFrame, 0) ?: return 0f
            return (-cardX / 480f).coerceIn(0f, maximumShift)
        }
        val measured = if (sourceFrame <= MALES_STEADY_START_FRAME) {
            val frame = sourceFrame.toFloat()
            val right = malesPhasePullKeys.indexOfFirst { frame <= it.first }
            when {
                right <= 0 -> malesPhasePullKeys.first().second
                else -> {
                    val (f0, s0) = malesPhasePullKeys[right - 1]
                    val (f1, s1) = malesPhasePullKeys[right]
                    lerp(s0, s1, (frame - f0) / (f1 - f0))
                }
            }
        } else {
            MALES_STEADY_START_SHIFT +
                (sourceFrame - MALES_STEADY_START_FRAME) / MALES_STEADY_PERIOD_FRAMES
        }
        return measured.coerceIn(0f, maximumShift)
    }

    private fun malesFrameForShift(targetShift: Float): Float {
        if (targetShift <= 0f) return MALES_CONVEYOR_START_FRAME.toFloat()
        if (targetShift <= MALES_STEADY_START_SHIFT) {
            val right = malesPhasePullKeys.indexOfFirst { targetShift <= it.second }
            if (right <= 0) return malesPhasePullKeys.first().first
            val (f0, s0) = malesPhasePullKeys[right - 1]
            val (f1, s1) = malesPhasePullKeys[right]
            val amount = ((targetShift - s0) / (s1 - s0).coerceAtLeast(0.000001f)).coerceIn(0f, 1f)
            return lerp(f0, f1, amount)
        }
        return MALES_STEADY_START_FRAME +
            (targetShift - MALES_STEADY_START_SHIFT) * MALES_STEADY_PERIOD_FRAMES
    }

    private fun malesCardStartFrame(index: Int): Float =
        ExactReferenceFrames.malesCardStartFrame(index).toFloat()

    private fun malesReferenceFrameCount(cardCount: Int): Int {
        if (cardCount <= 0) return 0
        if (cardCount == MALES_CANONICAL_CARD_COUNT) return MALES_REFERENCE_FRAMES
        val settledFrame = when (cardCount) {
            1 -> 120
            2 -> 240
            3 -> 360
            4 -> 535
            else -> kotlin.math.ceil(malesFrameForShift((cardCount - 4).toFloat()).toDouble()).toInt()
        }
        return settledFrame + MALES_FINAL_HOLD_FRAMES +
            MALES_END_WIPE_FRAMES + MALES_END_RISE_FRAMES + MALES_END_HOLD_FRAMES + MALES_FADE_FRAMES
    }

    private fun malesOutroStartFrame(cardCount: Int): Int =
        malesReferenceFrameCount(cardCount) -
            (MALES_END_WIPE_FRAMES + MALES_END_RISE_FRAMES + MALES_END_HOLD_FRAMES + MALES_FADE_FRAMES)

    private fun malesBadgeClockFrame(index: Int, animationAge: Float): Float {
        val start = malesCardStartFrame(index)
        return if (index < 4) {
            start + animationAge * MALES_REFERENCE_FPS
        } else {
            start + MALES_POST_BADGE_DELAY * MALES_REFERENCE_FPS +
                animationAge / MALES_POST_BADGE_SPEED * MALES_REFERENCE_FPS
        }
    }

    private fun easeInOutCubic(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        val q = -2f * x + 2f
        return if (x < 0.5f) 4f * x * x * x else 1f - q * q * q / 2f
    }

    private fun malesStageScale(index: Int, sourceFrame: Int, cardCount: Int): Float {
        var scale = MALES_BADGE_ACTIVE_SCALE
        if (index + 1 < cardCount) {
            val next = index + 1
            val speed = if (next < 4) 1f else MALES_POST_BADGE_SPEED
            val trigger = malesBadgeClockFrame(next, 1.72f)
            val duration = MALES_BADGE_DEEMPHASIS_SECONDS / speed * MALES_REFERENCE_FPS
            scale = lerp(scale, MALES_BADGE_MEDIUM_SCALE, easeInOutCubic((sourceFrame - trigger) / duration))
        }
        if (index + 2 < cardCount) {
            val next = index + 2
            val speed = if (next < 4) 1f else MALES_POST_BADGE_SPEED
            val trigger = malesBadgeClockFrame(next, 1.72f)
            val duration = MALES_BADGE_DEEMPHASIS_SECONDS / speed * MALES_REFERENCE_FPS
            val p = easeInOutCubic((sourceFrame - trigger) / duration)
            if (p > 0f) scale = lerp(MALES_BADGE_MEDIUM_SCALE, MALES_BADGE_SMALL_SCALE, p)
        }
        return scale
    }

    private fun malesMeasuredBadgeAffine(
        index: Int,
        age: Float,
        sourceFrame: Int,
        cardCount: Int,
        initialCount: Int,
    ): BadgeAffine {
        if (index < initialCount) {
            ExactReferenceFrames.malesOpeningBadgeAffine(sourceFrame, index)?.let { return it }
        } else {
            ExactReferenceFrames.malesPostBadgeAffine(sourceFrame, index)?.let { return it }
        }
        if (age < MALES_BADGE_ENTRY_END) return BadgeAffine.Identity
        val scale = malesStageScale(index, sourceFrame, cardCount)
        val cx = 243.5f
        val cy = 203.5f
        return BadgeAffine(scale, 0f, 0f, scale, cx * (1f - scale), cy * (1f - scale))
    }

    private fun malesBodyProgress(localTime: Float): Float {
        if (localTime <= malesBodyProgressKeys.first().first) return malesBodyProgressKeys.first().second
        if (localTime >= malesBodyProgressKeys.last().first) return malesBodyProgressKeys.last().second
        val right = malesBodyProgressKeys.indexOfFirst { localTime <= it.first }
        val (t0, v0) = malesBodyProgressKeys[right - 1]
        val (t1, v1) = malesBodyProgressKeys[right]
        return lerp(v0, v1, smoothStep((localTime - t0) / (t1 - t0)))
    }

    private fun malesOpeningBadgeAffine(age: Float): BadgeAffine {
        if (age >= MALES_BADGE_ENTRY_END) return BadgeAffine.Identity
        if (age <= malesOpeningBadgeKeys.first()[0]) return malesOpeningBadgeKeys.first().toBadgeAffine()
        val right = malesOpeningBadgeKeys.indexOfFirst { age <= it[0] }
        val left = malesOpeningBadgeKeys[right - 1]
        val upper = malesOpeningBadgeKeys[right]
        val t = smoothStep((age - left[0]) / (upper[0] - left[0]))
        return BadgeAffine(
            lerp(left[1], upper[1], t), lerp(left[2], upper[2], t),
            lerp(left[3], upper[3], t), lerp(left[4], upper[4], t),
            lerp(left[5], upper[5], t), lerp(left[6], upper[6], t),
        )
    }

    private fun FloatArray.toBadgeAffine(): BadgeAffine =
        BadgeAffine(this[1], this[2], this[3], this[4], this[5], this[6])

    private fun malesPostBadgeAffine(age: Float): BadgeAffine {
        if (age >= MALES_BADGE_ENTRY_END) return BadgeAffine.Identity
        val key = when {
            age <= malesPostBadgeKeys.first()[0] -> malesPostBadgeKeys.first()
            else -> {
                val right = malesPostBadgeKeys.indexOfFirst { age <= it[0] }
                if (right < 1) malesPostBadgeKeys.last() else {
                    val left = malesPostBadgeKeys[right - 1]
                    val upper = malesPostBadgeKeys[right]
                    val t = smoothStep((age - left[0]) / (upper[0] - left[0]))
                    floatArrayOf(age, lerp(left[1], upper[1], t), lerp(left[2], upper[2], t))
                }
            }
        }
        val scale = key[1]
        val cx = 243.5f
        val cy = 203.5f
        return BadgeAffine(scale, 0f, 0f, scale, cx * (1f - scale), cy * (1f - scale) + key[2])
    }

    private fun relationshipsPlacement(
        index: Int,
        x: Float,
        localFrame: Int,
        opening: Boolean,
        sourceFrame: Int,
    ): CardPlacement {
        val exactBody = if (opening) {
            ExactReferenceFrames.relationshipsOpeningTransform(sourceFrame, index)
        } else null
        val artwork = if (opening) {
            ExactReferenceFrames.relationshipsArtworkReveal(sourceFrame, index)
        } else 1f
        val title = if (opening) ((localFrame - 96) / 10f).coerceIn(0f, 1f) else 1f
        val description = if (opening) ((localFrame - 105) / 15f).coerceIn(0f, 1f) else 1f
        val textStart = if (opening) 88 else 18
        val textProgress = ((localFrame - textStart) / 32f).coerceIn(0f, 1f)
        return CardPlacement(
            cardIndex = index,
            xInCards = x,
            bodyReveal = if (opening) {
                // The measured transform only covers the 121-frame entrance.
                // Once that table ends the card is fully settled, not hidden.
                if (localFrame > 120 || exactBody != null) 1f else 0f
            } else 1f,
            badgeVisible = localFrame >= 11,
            badgeSettle = relationshipsBadgeScale(localFrame),
            artworkReveal = artwork,
            titleReveal = title,
            descriptionReveal = description,
            // The compressed table tracks the inner red component. The full
            // badge renderer needs the measured outer octagon bounds here.
            badgeRect = relationshipsBadgeRect(localFrame),
            badgeTextAlpha = textProgress,
            // The Relationships reference drives its gloss from the same
            // canonical 0.9..2.3 badge clock used by the desktop renderer.
            badgeAgeSeconds = 0.9f + textProgress * 1.4f,
            bodyTransform = exactBody,
        )
    }

    private val relationshipsBadgeBounds = arrayOf(
        floatArrayOf(11f, 208f, 170.3f, 256f, 217.7f),
        floatArrayOf(15f, 144f, 107f, 320f, 281f),
        floatArrayOf(19f, 88f, 51.6f, 376f, 336.4f),
        floatArrayOf(23f, 40f, 4.2f, 424f, 383.8f),
        floatArrayOf(27f, 16f, -19.5f, 448f, 407.5f),
        floatArrayOf(30f, 32f, -3.7f, 432f, 391.7f),
        floatArrayOf(33f, 56f, 20f, 408f, 368f),
        floatArrayOf(36f, 72f, 35.8f, 392f, 352.2f),
        floatArrayOf(40f, 56f, 20f, 408f, 368f),
        floatArrayOf(44f, 48f, 12.1f, 416f, 375.9f),
        floatArrayOf(48f, 48f, 12.1f, 416f, 375.9f),
        floatArrayOf(50f, 56f, 20f, 408f, 368f),
        floatArrayOf(60f, 56f, 20f, 408f, 368f),
    )

    private fun relationshipsBadgeScale(localFrame: Int): Float {
        val rect = relationshipsBadgeRect(localFrame) ?: return 0f
        return rect.width / (352f / 480f)
    }

    private fun relationshipsBadgeRect(localFrame: Int): NormalizedRect? {
        if (localFrame < 11) return null
        val values = if (localFrame >= relationshipsBadgeBounds.last()[0]) {
            relationshipsBadgeBounds.last()
        } else {
            val right = relationshipsBadgeBounds.indexOfFirst { localFrame <= it[0] }
            if (right <= 0) relationshipsBadgeBounds.first() else {
                val left = relationshipsBadgeBounds[right - 1]
                val upper = relationshipsBadgeBounds[right]
                val t = (localFrame - left[0]) / (upper[0] - left[0])
                floatArrayOf(
                    localFrame.toFloat(), lerp(left[1], upper[1], t), lerp(left[2], upper[2], t),
                    lerp(left[3], upper[3], t), lerp(left[4], upper[4], t),
                )
            }
        }
        return NormalizedRect(
            values[1] / 480f,
            values[2] / 1080f,
            (values[3] - values[1]) / 480f,
            (values[4] - values[2]) / 1080f,
        )
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount.coerceIn(0f, 1f)

    fun fadeAlpha(project: CtsProject, outputTimeSeconds: Float): Float {
        if (project.model == VisualModel.Males && isSealedReference(project)) {
            val frame = sourceFrameAt(contentOutputTime(project, outputTimeSeconds), MALES_REFERENCE_FPS)
            if (isCanonicalMales(project)) return ExactReferenceFrames.malesFadeAlpha(frame)
            val fadeStart = malesReferenceFrameCount(project.cards.size) - MALES_FADE_FRAMES
            return 1f - ((frame - fadeStart) / MALES_FADE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
        if (!isSealedReference(project) && !project.showOutro) return 1f
        if (isLockedRelationships(project)) {
            if (isCanonicalRelationships(project)) {
                return ExactReferenceFrames.relationshipsFadeAlpha(
                    relationshipsSourceFrame(project, outputTimeSeconds),
                )
            }
            val fadeStart = RELATIONSHIPS_END_WIPE_FRAMES + RELATIONSHIPS_END_RISE_FRAMES +
                RELATIONSHIPS_END_HOLD_FRAMES
            return 1f - ((relationshipsOutroLocalFrame(project, outputTimeSeconds) - fadeStart) /
                RELATIONSHIPS_FADE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
        val modelTime = modelTime(project, outputTimeSeconds)
        val fadeStart = modelDuration(project) - FADE_SECONDS
        if (modelTime <= fadeStart) return 1f
        return 1f - smoothStep((modelTime - fadeStart) / FADE_SECONDS.coerceAtLeast(0.001f))
    }

    fun editingTimeForCard(project: CtsProject, cardIndex: Int): Float {
        if (project.cards.isEmpty()) return 0f
        val safeIndex = cardIndex.coerceIn(0, project.cards.lastIndex)
        if (isLockedRelationships(project)) {
            val frame = if (safeIndex < 4) relationshipsOpeningStarts[safeIndex] else {
                RELATIONSHIPS_CONTINUOUS_START_FRAME +
                    (safeIndex - 4) * RELATIONSHIPS_CONTINUOUS_STEP_FRAMES
            }
            val sourceSeconds = (frame - relationshipsIntroOffset(project)).coerceAtLeast(0) /
                RELATIONSHIPS_REFERENCE_FPS.toFloat()
            return (sourceSeconds / playbackRate(project)).coerceAtMost(duration(project))
        }
        val parts = timelineParts(project)
        val initialCount = min(project.cards.size, project.model.visibleCards)
        val scrollStart = parts.introSeconds
        val targetModelTime = if (safeIndex < project.model.visibleCards) {
            parts.preludeSeconds + safeIndex * parts.revealSeconds + BODY_WIPE_SECONDS
        } else {
            scrollStart + (safeIndex - project.model.visibleCards + 1) * parts.scrollSeconds
        }
        return min(duration(project), outputTimeForModelTime(project, targetModelTime))
    }

    fun formatTime(seconds: Float): String {
        val total = seconds.coerceAtLeast(0f).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun materialEase(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var low = 0f
        var high = 1f
        repeat(12) {
            val t = (low + high) / 2f
            val curveX = cubic(t, SharedContract.MATERIAL_EASE_X1, SharedContract.MATERIAL_EASE_X2)
            if (curveX < x) low = t else high = t
        }
        return cubic(
            (low + high) / 2f,
            SharedContract.MATERIAL_EASE_Y1,
            SharedContract.MATERIAL_EASE_Y2,
        )
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
