package io.github.retrofrost.cts.android.renderer3

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RendererV3ContainerTest {
    private fun container(json: String): ByteArray {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val payload = compressed.toByteArray()
        val checksum = CRC32().apply { update(payload) }.value
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put("CCRNDR03".toByteArray(Charsets.US_ASCII))
            putInt(1)
            putInt(payload.size)
            putInt(checksum.toInt())
            put(payload)
        }.array()
    }

    @Test fun validRenderer3GzipAndCrcRoundTrip() {
        val scene = """{"id":"android.scene","api":3,"timeline":{"frames":120}}"""
        assertEquals(scene, RendererV3Container.decode(container(scene)))
    }

    @Test fun corruptedPayloadIsRejected() {
        val file = container("""{"id":"broken"}""")
        file[file.lastIndex] = (file.last().toInt() xor 0x20).toByte()
        assertThrows(IllegalArgumentException::class.java) { RendererV3Container.decode(file) }
    }

    @Test fun wrongMagicAndVersionAreRejected() {
        val file = container("{}")
        file[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) { RendererV3Container.decode(file) }
        val version = container("{}")
        version[11] = 2
        assertThrows(IllegalArgumentException::class.java) { RendererV3Container.decode(version) }
    }

    @Test fun maliciousOrAmbiguousPathsAreRejected() {
        for (bad in listOf("../x.png", "/etc/passwd", "a/../x", "a//b", "C:/windows/x")) {
            assertThrows(IllegalArgumentException::class.java) { RendererV3Container.safeName(bad) }
        }
        assertEquals("media/cards/001.png", RendererV3Container.safeName("media\\cards\\001.png"))
    }

    @Test fun gzipBombIsRejected() {
        val tooLarge = "a".repeat(33 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            RendererV3Container.decode(container(tooLarge))
        }
    }
}
