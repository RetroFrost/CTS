package dev.infinitycomparison.cc

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.InflaterInputStream

/** Frame-addressed geometry decoded from Comparison: Worst Things To Hear [V0kKJ1NGzT0]. */
internal object ReferenceFrameData {
    const val continuousStart = 528
    const val contentEnd = 10_428
    private const val continuousFrames = contentEnd - continuousStart
    private const val fieldsPerFrame = 8

    data class VisibleFrame(val firstIndex: Int, val slotX: IntArray)

    private val openingActiveX: IntArray by lazy {
        val deltas = shorts(inflate(openingDeltaZlib))
        require(deltas.size == continuousStart)
        var value = 0
        IntArray(deltas.size) { index ->
            value += deltas[index]
            value
        }
    }

    private val continuous: Array<VisibleFrame> by lazy {
        val deltas = shorts(inflate(continuousDeltaZlib))
        require(deltas.size == continuousFrames * fieldsPerFrame)
        val previous = IntArray(fieldsPerFrame)
        Array(continuousFrames) { frame ->
            val base = frame * fieldsPerFrame
            for (field in 0 until fieldsPerFrame) previous[field] += deltas[base + field]
            val count = previous[1].coerceIn(0, 6)
            VisibleFrame(previous[0], IntArray(count) { previous[it + 2] })
        }
    }

    private val outroGroupX: IntArray by lazy { accumulate(inflate(outroGroupDeltaZlib), 1) }

    private val outroActionBounds: Array<IntArray> by lazy {
        val values = accumulateFields(inflate(outroActionDeltaZlib), 4)
        Array(values.size / 4) { frame -> values.copyOfRange(frame * 4, frame * 4 + 4) }
    }

    fun openingActiveSlotX(frame: Int): Int = openingActiveX[frame.coerceIn(0, continuousStart - 1)]

    fun visible(frame: Int): VisibleFrame = continuous[(frame - continuousStart).coerceIn(0, continuousFrames - 1)]

    fun outroGroupX(localFrame: Int): Int = outroGroupX[localFrame.coerceIn(0, outroGroupX.lastIndex)]

    /** left, top, width and height; measured for local frames 96 through 140. */
    fun outroActionBounds(localFrame: Int): IntArray? {
        if (localFrame < 96) return null
        return outroActionBounds[(localFrame - 96).coerceIn(0, outroActionBounds.lastIndex)].copyOf()
    }

    private fun inflate(encoded: String): ByteArray = InflaterInputStream(
        Base64.getDecoder().decode(encoded).inputStream(),
    ).use { input ->
        val output = ByteArrayOutputStream()
        input.copyTo(output)
        output.toByteArray()
    }

    private fun shorts(bytes: ByteArray): IntArray {
        require(bytes.size % 2 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 2) { buffer.short.toInt() }
    }

    private fun accumulate(bytes: ByteArray, fields: Int): IntArray = accumulateFields(bytes, fields)

    private fun accumulateFields(bytes: ByteArray, fields: Int): IntArray {
        val deltas = shorts(bytes)
        require(deltas.size % fields == 0)
        val previous = IntArray(fields)
        return IntArray(deltas.size) { index ->
            val field = index % fields
            previous[field] += deltas[index]
            previous[field]
        }
    }

    private const val openingDeltaZlib =
        "eNrNkdkOgkAMRcui7KIo+K7//xt+j3HfoF46w7DoIyb2pHeAkElPuq3oozYUUkIpZZTTWshpRQswAzEIQUC+4NFUmEi7oE5HusUGKi2TqruQSTLZfxqn6klcmdiHRwzbBQwLcS1gu8R7ChKKhEDbeh3bibF1er626aFt37frav3IU91p6zk9GESwnQ82m+FL2ttsIP/Wrp7ea2PrDnbbmtodY+tjt983O77vzhnrpqra84GPfOIzX/gKbuAOHsJTeIEnl3KWmsq0gnWyzqaImlRdV3P+S70BPIFyYg=="

    private const val outroGroupDeltaZlib =
        "eNpNw8kJgDAURVGwjvRfgsW4zEYQUeI8JLl+niLhcFxV0+Bp6egZGAlMzGZhZZPdHHLK9bs/UV9JS1lL2AeevXXH"

    private const val outroActionDeltaZlib =
        "eNpdzTEKwlAQBNCJJEEMYlTQQkHsxdLexsLOm4gHsPIknsPGzjuIYGOhVQiIkfgz7i+y/GS6x87uHvydt0EfZa78cI2e+sKUW4zVZybcY6I+8skT5uo7My4Rqx/8coE26nkx5wyR+iZ/V+iqUxqOEKrf4mHNA8cJf5yiqc5YsANfnZOM0Ki45dj2Y+mTgCc2Mg+deSEOHJcx0g8qru7Ze1Z/CO5VcA=="

    private const val continuousDeltaZlib =
        "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"
}
