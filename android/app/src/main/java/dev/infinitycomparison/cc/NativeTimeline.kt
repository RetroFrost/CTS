package dev.infinitycomparison.cc

import kotlin.math.max

object NativeTimeline {
    data class Bounds(val left: Float, val top: Float, val width: Float, val height: Float)
    data class Point(val x: Float, val y: Float)
    data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)
    const val referenceWidth = 1920f
    const val referenceHeight = 1080f
    const val slotPitch = 477f
    const val bodyInset = 10f
    const val bodyWidth = 470
    const val bodyHeight = 1080
    const val continuousStart = ReferenceFrameData.continuousStart
    const val continuousStep = 214
    const val outroFrames = 494

    private const val openingShineStart = 95
    private const val openingShineEndExclusive = 120
    private const val scrollingShineStart = 208
    private const val scrollingShineEndExclusive = 241

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
    private val activeToMediumFaceWidth = intArrayOf(
        355, 352, 346, 342, 339, 336, 335, 334, 332, 330, 330, 329, 328,
        327, 326, 326, 326, 326, 325, 324, 324, 325, 324, 324, 323,
    )
    private val mediumToSmallFaceWidth = intArrayOf(
        320, 320, 314, 310, 308, 303, 302, 299, 296, 296, 294, 293, 292,
        291, 291, 291, 290, 289, 288, 288, 289, 288, 288, 287, 287, 288,
    )
    private val smallToBackgroundFaceWidth = intArrayOf(
        284, 282, 276, 272, 267, 266, 264, 262, 262, 260, 258, 258, 256,
        256, 254, 254, 254, 253, 254, 252, 252, 252, 252, 252, 252,
    )
    private val openingBadgeAffine = arrayOf(
        35 to Affine(.493398f, -.085460f, -.331527f, 1.161492f, -150.997648f, -39.870887f),
        40 to Affine(.592169f, -.078765f, -.283855f, 1.188568f, -125.999331f, -19.155194f),
        44 to Affine(.653847f, -.078786f, -.293365f, 1.172057f, -105.417801f, -5.568381f),
        48 to Affine(.696013f, -.090493f, -.273790f, 1.202435f, -82.436779f, -19.898183f),
        52 to Affine(.721844f, -.076480f, -.273350f, 1.200237f, -60.473294f, -18.705733f),
        56 to Affine(.729938f, -.029255f, -.225309f, 1.111702f, -45.263002f, -10.894799f),
        60 to Affine(.774691f, -.031915f, -.189815f, 1.114362f, -38.958629f, -14.476950f),
        64 to Affine(.817901f, -.031915f, -.168210f, 1.114362f, -34.569740f, -17.032506f),
        68 to Affine(.859568f, -.039894f, -.121914f, 1.087766f, -30.989953f, -17.599882f),
        72 to Affine(.898148f, -.037234f, -.114198f, 1.069149f, -29.794326f, -14.969267f),
        76 to Affine(.922840f, -.026596f, -.101852f, 1.053191f, -28.178487f, -11.698582f),
        80 to Affine(.945988f, -.029255f, -.067901f, 1.058511f, -23.818558f, -15.196217f),
        84 to Affine(.964506f, -.018617f, -.064815f, 1.042553f, -23.258274f, -10.258865f),
        88 to Affine(.979938f, -.023936f, -.038580f, 1.039894f, -19.316194f, -13.121158f),
        92 to Affine(.987654f, -.021277f, -.023148f, 1.029255f, -16.898345f, -10.625887f),
        96 to Affine(1.001543f, -.013298f, -.021605f, 1.021277f, -15.978132f, -8.657210f),
        100 to Affine(1.010802f, -.013298f, -.006173f, 1.005319f, -14.144799f, -6.608747f),
        104 to Affine(1.013889f, -.007979f, -.006173f, 1.010638f, -11.420213f, -6.661939f),
        108 to Affine(1.010802f, .002660f, -.015432f, 1.015957f, -8.304374f, -3.548463f),
        112 to Affine(1.021605f, -.010638f, .009259f, 1.005319f, -4.449173f, -6.219858f),
        116 to Affine(1.024691f, -.010638f, .020062f, .986702f, -2.171395f, -1.311466f),
        120 to Affine(1f, 0f, 0f, 1f, 0f, 0f),
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
            count == 50 -> 10_428
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
        if (local < 355) return 1f
        if (local < 457) return 1f - (local - 355) / 102f
        return 0f
    }

    fun positions(project: StudioProject, requestedFrame: Int): Map<Int, Float> {
        val frame = sceneFrame(project, requestedFrame)
        val count = project.cards.size
        if (count == 0) return emptyMap()
        val starts = cardStarts(project)
        if (project.autoLength && count == 50 && frame >= continuousStart) {
            val measured = ReferenceFrameData.visible(frame)
            return buildMap {
                measured.slotX.forEachIndexed { offset, x ->
                    val index = measured.firstIndex + offset
                    if (index in 0 until count) put(index, x.toFloat())
                }
            }
        }
        if (project.autoLength && count == 50) {
            val active = minOf(frame / 120, 3)
            return buildMap {
                for (index in 0 until active) put(index, index * slotPitch)
                put(active, ReferenceFrameData.openingActiveSlotX(frame).toFloat())
            }
        }
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
        if (project.autoLength && project.cards.size == 50) {
            if (active < 3) return 3f * slotPitch
            // The fourth body enters from the right while the disclaimer exits to the right.
            // Both positions are driven by the measured opening-frame table.
            val activeX = ReferenceFrameData.openingActiveSlotX(frame).toFloat()
            return (referenceWidth + 3f * slotPitch - activeX).coerceIn(3f * slotPitch, referenceWidth)
        }
        val movement = sampleSeconds(bodyProgress, (frame - starts[active]) / 60f)
        return when {
            active == 0 -> referenceWidth - slotPitch * movement
            active < 3 -> referenceWidth - slotPitch
            active == 3 -> referenceWidth - slotPitch + slotPitch * movement
            else -> null
        }
    }

    fun badgeOffset(index: Int, localFrame: Int, settledScrollingBadges: Boolean = false): Float? {
        if (localFrame < 0) return null
        if (index < 4) return if (localFrame >= 35) 0f else null
        if (settledScrollingBadges) return 0f
        if (localFrame < 122) return null
        return sampleFrames(badgeFall, localFrame)
    }

    fun badgeShineProgress(index: Int, localFrame: Int): Float? {
        val start = if (index < 4) openingShineStart else scrollingShineStart
        val end = if (index < 4) openingShineEndExclusive else scrollingShineEndExclusive
        if (localFrame !in start until end) return null
        return (localFrame - start).toFloat() / (end - start)
    }

    fun badgeAffine(index: Int, localFrame: Int): Affine {
        if (index >= 4 || localFrame >= 120) return Affine(1f, 0f, 0f, 1f, 0f, 0f)
        return sampleAffine(openingBadgeAffine, localFrame)
    }

    fun badgeScale(index: Int, localFrame: Int, globalFrame: Int): Float {
        val active = 1.25f
        val medium = 1.095f
        val small = .975f
        val background = .855f
        if (index >= 4) {
            fun measured(start: Int, widths: IntArray): Float? =
                if (localFrame in start until start + widths.size) widths[localFrame - start] / 296f else null
            return measured(391, activeToMediumFaceWidth)
                ?: measured(603, mediumToSmallFaceWidth)
                ?: measured(816, smallToBackgroundFaceWidth)
                ?: when {
                    localFrame < 391 -> 360f / 296f
                    localFrame < 603 -> medium
                    localFrame < 816 -> small
                    else -> background
                }
        }
        val base = when (index) {
            0 -> if (globalFrame < 120) active else if (globalFrame < 240) medium else if (globalFrame < 360) small else background
            1 -> if (globalFrame < 240) active else if (globalFrame < 360) medium else if (globalFrame < 480) small else background
            2 -> if (globalFrame < 360) active else if (globalFrame < 480) medium else background
            else -> if (globalFrame < 480) active else if (globalFrame < 650) medium else background
        }
        return base
    }

    @Suppress("UNUSED_PARAMETER")
    fun badgeTextProgress(
        index: Int,
        line: Int,
        localFrame: Int,
        settledScrollingBadges: Boolean = false,
    ): Float {
        return if (badgeOffset(index, localFrame, settledScrollingBadges) != null) 1f else 0f
    }

    fun outroLocal(project: StudioProject, frame: Int): Int = frame - contentEnd(project)

    fun outroGroupX(localFrame: Int): Float = ReferenceFrameData.outroGroupX(localFrame).toFloat()

    fun outroActionBar(localFrame: Int): Bounds? {
        val measured = ReferenceFrameData.outroActionBounds(localFrame) ?: return null
        return Bounds(measured[0].toFloat(), measured[1].toFloat(), measured[2].toFloat(), measured[3].toFloat())
    }

    fun outroActionState(localFrame: Int): Int = when {
        localFrame >= 372 -> 3
        localFrame >= 312 -> 2
        localFrame >= 252 -> 1
        else -> 0
    }

    fun outroCursor(localFrame: Int): Point? {
        val keys = arrayOf(
            180 to Point(1100f, 650f), 245 to Point(820f, 390f),
            260 to Point(820f, 390f), 300 to Point(1320f, 390f),
            320 to Point(1320f, 390f), 350 to Point(1600f, 390f),
            372 to Point(1600f, 390f),
        )
        if (localFrame !in keys.first().first..keys.last().first) return null
        for (i in 1 until keys.size) {
            if (localFrame <= keys[i].first) {
                val left = keys[i - 1]
                val right = keys[i]
                val t = (localFrame - left.first).toFloat() / (right.first - left.first)
                return Point(lerp(left.second.x, right.second.x, t), lerp(left.second.y, right.second.y, t))
            }
        }
        return keys.last().second
    }

    private fun lerp(left: Float, right: Float, amount: Float): Float = left + (right - left) * amount

    private fun sampleAffine(points: Array<Pair<Int, Affine>>, value: Int): Affine {
        if (value <= points.first().first) return points.first().second
        if (value >= points.last().first) return points.last().second
        for (i in 1 until points.size) if (value <= points[i].first) {
            val left = points[i - 1]
            val right = points[i]
            val t = (value - left.first).toFloat() / (right.first - left.first)
            return Affine(
                lerp(left.second.a, right.second.a, t), lerp(left.second.b, right.second.b, t),
                lerp(left.second.c, right.second.c, t), lerp(left.second.d, right.second.d, t),
                lerp(left.second.e, right.second.e, t), lerp(left.second.f, right.second.f, t),
            )
        }
        return points.last().second
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
