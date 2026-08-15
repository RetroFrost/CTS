package io.github.retrofrost.cts.android.timeline

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
    const val MALES_CARD_PITCH_PX = 476f
    private const val MALES_CANONICAL_CONVEYOR_END = 11_841
    const val MALES_CARD_WIDTH_PX = 472f
    const val MALES_FADE_START = 12_180

    data class BodyTransform(
        val xPx: Float,
        val yPx: Float,
        val scaleX: Float,
        val scaleY: Float,
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
    fun malesConveyorCardX(sourceFrame: Int, cardIndex: Int): Float? {
        if (sourceFrame < MALES_CONVEYOR_START) return null
        val heldFrame = sourceFrame.coerceAtMost(MALES_CANONICAL_CONVEYOR_END)
        val origin = malesOrigins[heldFrame - MALES_CONVEYOR_START] / 2f
        return origin + (cardIndex + 1) * MALES_CARD_PITCH_PX - MALES_CARD_WIDTH_PX
    }

    fun malesOpeningCardX(sourceFrame: Int, cardIndex: Int): Float? {
        if (cardIndex !in 0..3) return null
        val local = sourceFrame - malesCardStartFrame(cardIndex)
        if (local !in 0..119) return null
        val right = i16(malesOpeningBytes, local) ?: return null
        return 0.5f + cardIndex * MALES_CARD_PITCH_PX + right - 479f
    }

    fun malesCardStartFrame(cardIndex: Int): Int = startFrame(malesStartsBytes, cardIndex)

    private fun startFrame(data: ByteArray, cardIndex: Int): Int {
        val count = data.size / 2
        if (cardIndex <= 0) return u16(data, 0)
        if (cardIndex < count) return u16(data, cardIndex * 2)
        val previous = u16(data, (count - 2) * 2)
        val last = u16(data, (count - 1) * 2)
        return last + (cardIndex - count + 1) * (last - previous)
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

    fun malesOutroCoverProgress(sourceFrame: Int): Float {
        if (sourceFrame < 11_858) return 0f
        if (sourceFrame > 11_901) return 1f
        return ((i16(malesCoverBytes, sourceFrame - 11_858) ?: 0) / 1080f).coerceIn(0f, 1f)
    }

    fun malesOutroContentYOffsetPx(sourceFrame: Int): Float? {
        if (sourceFrame < 11_888) return null
        if (sourceFrame > 11_937) return 0f
        return i16(malesContentYBytes, sourceFrame - 11_888)?.toFloat()
    }

    fun malesFadeAlpha(sourceFrame: Int): Float =
        exactFade(sourceFrame, MALES_FADE_START, 12_258, malesFadeBytes)

    private fun exactFade(sourceFrame: Int, start: Int, end: Int, data: ByteArray): Float {
        if (sourceFrame < start) return 1f
        if (sourceFrame > end) return 0f
        return (data[sourceFrame - start].toInt() and 0xff) / 255f
    }

    private val malesOpeningBytes by lazy { inflate(MALES_OPENING) }
    private val malesOpeningBadgeBytes by lazy { inflate(MALES_OPENING_BADGE) }
    private val malesPostBadgeBytes by lazy { inflate(MALES_POST_BADGE) }
    private val malesCoverBytes by lazy { inflate(MALES_COVER) }
    private val malesContentYBytes by lazy { inflate(MALES_CONTENT_Y) }
    private val malesFadeBytes by lazy { inflate(MALES_FADE) }
    private val malesStartsBytes by lazy { inflate(MALES_STARTS) }
}
