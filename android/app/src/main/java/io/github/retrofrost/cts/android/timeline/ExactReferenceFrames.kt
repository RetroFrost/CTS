package io.github.retrofrost.cts.android.timeline

import io.github.retrofrost.cts.android.model.NormalizedRect
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * Per-source-frame model animation state decoded from the canonical CTS videos.
 *
 * This deliberately stores the frame state rather than easing/keyframe guesses.
 * The reference videos themselves are not bundled in the app.
 */
object ExactReferenceFrames {
    const val MALES_CONVEYOR_START = 528
    const val MALES_CONVEYOR_END = 16_335
    const val RELATIONSHIPS_CONVEYOR_START = 896
    const val RELATIONSHIPS_CONVEYOR_END = 10_701
    const val RELATIONSHIPS_FINAL_START = 10_670
    const val MALES_CARD_PITCH_PX = 476f
    const val RELATIONSHIPS_CARD_PITCH_PX = 483f
    const val MALES_CARD_WIDTH_PX = 480f
    const val RELATIONSHIPS_CARD_WIDTH_PX = 475f
    const val MALES_FADE_START = 16_666
    const val RELATIONSHIPS_FADE_START = 11_076

    data class BodyTransform(
        val xPx: Float,
        val yPx: Float,
        val scaleX: Float,
        val scaleY: Float,
    )

    data class LoopState(
        val centerXPx: Float,
        val centerYPx: Float,
        val radiusPx: Float,
        val startDegrees: Float,
        val sweepDegrees: Float,
        val alpha: Float,
    )

    private const val SENTINEL = -32768

    private fun inflate(encoded: String): ByteArray =
        InflaterInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { it.readBytes() }

    private fun s16(data: ByteArray, offset: Int): Int =
        (((data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)).toShort()).toInt()

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun i32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)

    private fun f32(data: ByteArray, offset: Int): Float = Float.fromBits(i32(data, offset))

    private fun decodeOrigins(encoded: String, frameCount: Int): IntArray {
        val packed = inflate(encoded)
        require(packed.size == frameCount + 3)
        val values = IntArray(frameCount)
        values[0] = i32(packed, 0)
        for (index in 1 until frameCount) {
            values[index] = values[index - 1] + packed[index + 3].toInt()
        }
        return values
    }

    private fun i16(data: ByteArray, index: Int): Int? {
        val value = s16(data, index * 2)
        return value.takeUnless { it == SENTINEL }
    }

    private fun rect(data: ByteArray, index: Int): IntArray? {
        val base = index * 8
        val values = IntArray(4) { s16(data, base + it * 2) }
        return if (values.any { it == SENTINEL }) null else values
    }

    private val malesOrigins by lazy {
        decodeOrigins(MALES_ORIGINS, MALES_CONVEYOR_END - MALES_CONVEYOR_START + 1)
    }
    private val relationshipsOrigins by lazy {
        decodeOrigins(
            RELATIONSHIPS_ORIGINS,
            RELATIONSHIPS_CONVEYOR_END - RELATIONSHIPS_CONVEYOR_START + 1,
        )
    }

    fun malesConveyorCardX(sourceFrame: Int, cardIndex: Int): Float? {
        if (sourceFrame < MALES_CONVEYOR_START) return null
        val heldFrame = sourceFrame.coerceAtMost(MALES_CONVEYOR_END)
        val origin = malesOrigins[heldFrame - MALES_CONVEYOR_START] / 2f
        return origin + (cardIndex + 1) * MALES_CARD_PITCH_PX - MALES_CARD_WIDTH_PX
    }

    fun relationshipsConveyorCardX(sourceFrame: Int, cardIndex: Int): Float? {
        if (sourceFrame !in RELATIONSHIPS_CONVEYOR_START..RELATIONSHIPS_CONVEYOR_END) return null
        val origin = relationshipsOrigins[sourceFrame - RELATIONSHIPS_CONVEYOR_START] / 2f
        return origin + (cardIndex + 1) * RELATIONSHIPS_CARD_PITCH_PX - RELATIONSHIPS_CARD_WIDTH_PX
    }

    fun malesOpeningCardX(sourceFrame: Int, cardIndex: Int): Float? {
        if (cardIndex !in 0..3) return null
        val local = sourceFrame - malesCardStartFrame(cardIndex)
        if (local !in 0..119) return null
        val right = i16(malesOpeningBytes, local) ?: return null
        return 0.5f + cardIndex * 477f + right - 479f
    }

    fun malesCardStartFrame(cardIndex: Int): Int = startFrame(malesStartsBytes, cardIndex)

    fun relationshipsCardStartFrame(cardIndex: Int): Int = startFrame(relationshipsStartsBytes, cardIndex)

    private fun startFrame(data: ByteArray, cardIndex: Int): Int {
        val count = data.size / 2
        if (cardIndex <= 0) return u16(data, 0)
        if (cardIndex < count) return u16(data, cardIndex * 2)
        val previous = u16(data, (count - 2) * 2)
        val last = u16(data, (count - 1) * 2)
        return last + (cardIndex - count + 1) * (last - previous)
    }

    fun relationshipsOpeningTransform(sourceFrame: Int, cardIndex: Int): BodyTransform? {
        if (cardIndex !in 0..3) return null
        val local = sourceFrame - relationshipsCardStartFrame(cardIndex)
        if (local !in 0..120) return null
        val values = rect(relationshipsTransformBytes, local) ?: return null
        val scaleX = values[2] / 474f
        val scaleY = values[3] / 118f
        val targetX = floatArrayOf(1f, 482.5f, 965f, 1447.5f)[cardIndex]
        return BodyTransform(
            xPx = targetX + values[0],
            yPx = values[1] - 788f * scaleY,
            scaleX = scaleX,
            scaleY = scaleY,
        )
    }

    fun relationshipsArtworkReveal(sourceFrame: Int, cardIndex: Int): Float {
        if (cardIndex !in 0..3) return 1f
        val local = sourceFrame - relationshipsCardStartFrame(cardIndex)
        if (local < 0) return 0f
        if (local >= 121) return 1f
        return (i16(relationshipsArtworkBytes, local) ?: 0).coerceIn(0, 788) / 788f
    }

    fun relationshipsBadgeRect(sourceFrame: Int, cardIndex: Int): NormalizedRect? {
        if (cardIndex !in 0..3) return null
        val local = sourceFrame - relationshipsCardStartFrame(cardIndex)
        if (local !in 0..120) return null
        val values = rect(relationshipsBadgeBytes, local) ?: return null
        return NormalizedRect(
            x = values[0] / 480f,
            y = values[1] / 1080f,
            width = values[2] / 480f,
            height = values[3] / 1080f,
        )
    }

    fun relationshipLoop(sourceFrame: Int, lime: Boolean): LoopState? {
        if (sourceFrame !in 0..373) return null
        val index = sourceFrame * 2 + if (lime) 0 else 1
        val base = index * 12
        val cx = s16(relationshipsLoopBytes, base)
        if (cx == SENTINEL) return null
        return LoopState(
            centerXPx = cx / 10f,
            centerYPx = s16(relationshipsLoopBytes, base + 2) / 10f,
            radiusPx = s16(relationshipsLoopBytes, base + 4) / 10f,
            startDegrees = s16(relationshipsLoopBytes, base + 6) / 2f,
            sweepDegrees = s16(relationshipsLoopBytes, base + 8) / 2f,
            alpha = (s16(relationshipsLoopBytes, base + 10) / 1000f).coerceIn(0f, 1f),
        )
    }

    fun malesOpeningBadgeAffine(sourceFrame: Int, cardIndex: Int): BadgeAffine? {
        if (cardIndex !in 0..3) return null
        val local = sourceFrame - malesCardStartFrame(cardIndex)
        if (local !in 0..180) return null
        val base = local * 24
        val m00 = f32(malesOpeningBadgeBytes, base)
        if (m00.isNaN()) return null
        return BadgeAffine(
            m00,
            f32(malesOpeningBadgeBytes, base + 4),
            f32(malesOpeningBadgeBytes, base + 8),
            f32(malesOpeningBadgeBytes, base + 12),
            f32(malesOpeningBadgeBytes, base + 16),
            f32(malesOpeningBadgeBytes, base + 20),
        )
    }

    fun malesPostBadgeAffine(sourceFrame: Int, cardIndex: Int): BadgeAffine? {
        if (cardIndex < 4) return null
        val local = sourceFrame - malesCardStartFrame(cardIndex)
        if (local !in 0..900) return null
        val values = rect(malesPostBadgeBytes, local) ?: return null
        val sx = values[2] / 298f
        val sy = values[3] / 344f
        return BadgeAffine(
            sx,
            0f,
            0f,
            sy,
            values[0] - 96f * sx,
            values[1] - 32f * sy,
        )
    }

    fun relationshipsFinalLastCardX(sourceFrame: Int): Float? {
        if (sourceFrame !in RELATIONSHIPS_FINAL_START..11_129) return null
        return i16(relationshipsLastXBytes, sourceFrame - RELATIONSHIPS_FINAL_START)?.toFloat()
    }

    fun malesOutroCoverProgress(sourceFrame: Int): Float {
        if (sourceFrame < 16_360) return 0f
        if (sourceFrame > 16_403) return 1f
        return ((i16(malesCoverBytes, sourceFrame - 16_360) ?: 0) / 1080f).coerceIn(0f, 1f)
    }

    fun malesOutroContentYOffsetPx(sourceFrame: Int): Float? {
        if (sourceFrame < 16_390) return null
        if (sourceFrame > 16_439) return 0f
        return i16(malesContentYBytes, sourceFrame - 16_390)?.toFloat()
    }

    fun malesFadeAlpha(sourceFrame: Int): Float =
        exactFade(sourceFrame, MALES_FADE_START, 16_740, malesFadeBytes)

    fun relationshipsFadeAlpha(sourceFrame: Int): Float =
        exactFade(sourceFrame, RELATIONSHIPS_FADE_START, 11_129, relationshipsFadeBytes)

    private fun exactFade(sourceFrame: Int, start: Int, end: Int, data: ByteArray): Float {
        if (sourceFrame < start) return 1f
        if (sourceFrame > end) return 0f
        return (data[sourceFrame - start].toInt() and 0xff) / 255f
    }

    private val malesOpeningBytes by lazy { inflate(MALES_OPENING) }
    private val relationshipsLastXBytes by lazy { inflate(RELATIONSHIPS_LAST_X) }
    private val relationshipsTransformBytes by lazy { inflate(RELATIONSHIPS_TRANSFORM) }
    private val relationshipsArtworkBytes by lazy { inflate(RELATIONSHIPS_ARTWORK) }
    private val relationshipsBadgeBytes by lazy { inflate(RELATIONSHIPS_BADGE) }
    private val relationshipsLoopBytes by lazy { inflate(RELATIONSHIPS_LOOPS) }
    private val malesOpeningBadgeBytes by lazy { inflate(MALES_OPENING_BADGE) }
    private val malesPostBadgeBytes by lazy { inflate(MALES_POST_BADGE) }
    private val malesCoverBytes by lazy { inflate(MALES_COVER) }
    private val malesContentYBytes by lazy { inflate(MALES_CONTENT_Y) }
    private val malesFadeBytes by lazy { inflate(MALES_FADE) }
    private val relationshipsFadeBytes by lazy { inflate(RELATIONSHIPS_FADE) }
    private val malesStartsBytes by lazy { inflate(MALES_STARTS) }
    private val relationshipsStartsBytes by lazy { inflate(RELATIONSHIPS_STARTS) }
}
