package dev.infinitycomparison.cc

import kotlin.math.max

object NativeTimeline {
    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)
    const val referenceWidth = 1920f
    const val referenceHeight = 1080f
    const val slotPitch = 476f
    const val bodyInset = 9f
    const val bodyWidth = 471
    const val bodyHeight = 1080
    const val continuousStart = 528
    const val continuousStep = 214
    const val outroFrames = 409

    private val openingStarts = intArrayOf(0, 120, 240, 360)
    private val openingEnds = intArrayOf(120, 240, 360, 528)
    private val bodyProgress = arrayOf(
        0.000f to 0.000f, 0.033f to 0.000f, 0.083f to 0.019f,
        0.166f to 0.101f, 0.250f to 0.300f, 0.333f to 0.515f,
        0.416f to 0.653f, 0.500f to 0.746f, 0.583f to 0.813f,
        0.666f to 0.864f, 0.750f to 0.901f, 0.833f to 0.931f,
        0.916f to 0.954f, 1.000f to 0.971f, 1.083f to 0.983f,
        1.166f to 0.994f, 1.250f to 0.998f, 1.333f to 1.000f,
    )
    private val conveyorCorrection = arrayOf(
        0 to 0f, 10 to 9.7f, 20 to 10f, 30 to 5.2f, 40 to -3.5f,
        50 to -17.3f, 60 to -32.5f, 70 to -49.8f, 80 to -67.6f,
        90 to -82.3f, 100 to -84.1f,
    )
    private val badgeFall = arrayOf(
        122 to -430f, 142 to -410f, 151 to -386f, 152 to -381f,
        156 to -341f, 160 to -300f, 164 to -266f, 168 to -226f,
        172 to -187f, 176 to -156f, 180 to -121f, 184 to -94f,
        188 to -66f, 192 to -41f, 196 to -25f, 200 to -10f,
        204 to -2f, 206 to 0f,
    )
    private val outroCover = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        28, 72, 128, 196, 273, 357, 445, 535, 626, 714, 798, 874,
        942, 999, 1042, 1070, 1080, 1080, 1080, 1080, 1080, 1080,
        1080, 1080, 1080, 1080, 1080, 1080, 1080, 1080, 1080, 1080,
        1080, 1080,
    )

    fun metadata(project: StudioProject): RenderMetadata {
        val fps = project.fps.coerceIn(1, 120)
        val frames = totalFrames(project)
        return RenderMetadata(frames, frames.toDouble() / fps, fps)
    }

    fun totalFrames(project: StudioProject): Int = contentEnd(project) + outroFrames

    fun contentEnd(project: StudioProject): Int {
        val count = project.cards.size.coerceAtLeast(1)
        val automatic = when {
            count <= 4 -> openingEnds[count - 1]
            count == 57 -> 11_858
            else -> continuousStart + (count - 4) * continuousStep
        }
        if (project.autoLength) return automatic
        val requested = (project.customLengthSeconds * project.fps.coerceAtLeast(1)).toInt() - outroFrames
        val minimum = if (count <= 4) openingEnds[count - 1] else continuousStart + count - 4
        return max(minimum, requested)
    }

    fun cardStarts(project: StudioProject): IntArray {
        val count = project.cards.size
        if (count == 0) return IntArray(0)
        val starts = IntArray(count)
        for (index in 0 until minOf(4, count)) starts[index] = openingStarts[index]
        if (count <= 4) return starts
        if (project.autoLength) {
            for (index in 4 until count) starts[index] = continuousStart + (index - 4) * continuousStep
        } else {
            val intervals = max(1, count - 4)
            val span = contentEnd(project) - continuousStart
            for (index in 4 until count) {
                starts[index] = continuousStart + (span.toLong() * (index - 4) / intervals).toInt()
            }
        }
        return starts
    }

    fun sceneFrame(project: StudioProject, frame: Int): Int =
        frame.coerceIn(0, (contentEnd(project) - 1).coerceAtLeast(0))

    fun sceneAlpha(project: StudioProject, frame: Int): Float {
        val local = frame - contentEnd(project)
        if (local < 322) return 1f
        if (local < 401) return 1f - (local - 322) / 79f
        return 0f
    }

    fun positions(project: StudioProject, requestedFrame: Int): Map<Int, Float> {
        val frame = sceneFrame(project, requestedFrame)
        val count = project.cards.size
        if (count == 0) return emptyMap()
        val starts = cardStarts(project)
        if (frame >= continuousStart && count > 4) {
            val step = if (project.autoLength) continuousStep.toFloat() else {
                if (starts.size > 5) (starts[5] - starts[4]).coerceAtLeast(1).toFloat()
                else (contentEnd(project) - continuousStart).coerceAtLeast(1).toFloat()
            }
            val local = frame - continuousStart
            val correction = if (project.autoLength) sampleFrames(conveyorCorrection, local.coerceAtMost(100)) else 0f
            val origin = 9.5f - local * slotPitch / step + correction
            return buildMap {
                for (index in 0 until count) {
                    val x = origin + index * slotPitch
                    if (x > -slotPitch && x < referenceWidth + slotPitch) put(index, x)
                }
            }
        }
        var active = -1
        for (index in 0 until minOf(4, count)) if (frame >= starts[index]) active = index
        if (active < 0) return emptyMap()
        val result = mutableMapOf<Int, Float>()
        for (index in 0 until active) result[index] = index * slotPitch
        val movement = sampleSeconds(bodyProgress, (frame - starts[active]) / 60f)
        result[active] = if (active == 0) {
            -slotPitch + slotPitch * movement
        } else {
            (active - 1) * slotPitch + slotPitch * movement
        }
        return result
    }

    fun creditsX(project: StudioProject, requestedFrame: Int): Float? {
        if (!project.creditsEnabled || project.cards.isEmpty()) return null
        val frame = sceneFrame(project, requestedFrame)
        if (frame >= continuousStart && project.cards.size > 4) return null
        val starts = cardStarts(project)
        var active = -1
        for (index in 0 until minOf(4, starts.size)) if (frame >= starts[index]) active = index
        if (active < 0) return referenceWidth
        val movement = sampleSeconds(bodyProgress, (frame - starts[active]) / 60f)
        return when {
            active == 0 -> referenceWidth - slotPitch * movement
            active < 3 -> referenceWidth - slotPitch
            active == 3 -> referenceWidth - slotPitch + slotPitch * movement
            else -> null
        }
    }

    fun badgeOffset(index: Int, localFrame: Int): Float? {
        if (localFrame < 0) return null
        if (index < 4) return 0f
        if (localFrame < 122) return null
        return sampleFrames(badgeFall, localFrame)
    }

    fun badgeShineProgress(index: Int, localFrame: Int): Float? {
        val start = if (index < 4) 108 else 208
        val end = if (index < 4) 133 else 241
        if (localFrame !in start until end) return null
        return (localFrame - start).toFloat() / (end - start)
    }

    fun badgeTextProgress(index: Int, line: Int, localFrame: Int): Float {
        val age = if (index < 4) {
            ((localFrame - 35) / 85f).coerceIn(0f, 1f) * 2.9f
        } else {
            ((localFrame - 122) / 103f) * 2.25f
        }
        val start = 0.9f + line * 0.1f
        return ((age - start) / 0.42f).coerceIn(0f, 1f)
    }

    fun easeOutCubic(value: Float): Float {
        val p = value.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p) * (1f - p)
    }

    fun outroLocal(project: StudioProject, frame: Int): Int = frame - contentEnd(project)

    fun outroCoverY(localFrame: Int): Float =
        outroCover[localFrame.coerceIn(0, outroCover.lastIndex)].toFloat()

    fun outroGroupTop(localFrame: Int): Float? {
        if (localFrame < 43) return null
        if (localFrame >= 53) return 0f
        val positions = floatArrayOf(-210f, -210f, -183f, -144f, -108f, -78f, -51f, -30f, -14f, -4f, 0f)
        return positions[localFrame - 43]
    }

    fun outroActionBar(localFrame: Int): Bounds? {
        if (localFrame < 54) return null
        val progress = easeOutCubic(((localFrame - 54) / 48f).coerceIn(0f, 1f))
        val width = 42f + (540f - 42f) * progress
        val height = 8f + (130f - 8f) * progress
        return Bounds(738f - width / 2f, 98f - 61f * progress, width, height)
    }

    fun outroSubscribe(localFrame: Int): Bounds? {
        if (localFrame < 74) return null
        val progress = easeOutCubic(((localFrame - 74) / 28f).coerceIn(0f, 1f))
        val width = 22f + (185f - 22f) * progress
        val height = 7f + (53f - 7f) * progress
        return Bounds(810.5f - width / 2f, 103f - 26f * progress, width, height)
    }

    private fun sampleFrames(points: Array<Pair<Int, Float>>, value: Int): Float {
        if (value <= points.first().first) return points.first().second
        if (value >= points.last().first) return points.last().second
        for (index in 1 until points.size) {
            val right = points[index]
            if (value <= right.first) {
                val left = points[index - 1]
                val amount = (value - left.first).toFloat() / (right.first - left.first)
                return left.second + (right.second - left.second) * amount
            }
        }
        return points.last().second
    }

    private fun sampleSeconds(points: Array<Pair<Float, Float>>, value: Float): Float {
        if (value <= points.first().first) return points.first().second
        if (value >= points.last().first) return points.last().second
        for (index in 1 until points.size) {
            val right = points[index]
            if (value <= right.first) {
                val left = points[index - 1]
                val amount = (value - left.first) / (right.first - left.first)
                return left.second + (right.second - left.second) * amount
            }
        }
        return points.last().second
    }
}
