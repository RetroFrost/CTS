package io.github.retrofrost.cts.android.renderer3

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WrappedRibbonV3Test {
    private fun envelope(magic: String, json: String): ByteArray {
        val zip = ByteArrayOutputStream()
        GZIPOutputStream(zip).use { it.write(json.toByteArray()) }
        val payload = zip.toByteArray()
        return ByteBuffer.allocate(20 + payload.size).order(ByteOrder.BIG_ENDIAN).apply {
            put(magic.toByteArray())
            putInt(1)
            putInt(payload.size)
            putInt(CRC32().apply { update(payload) }.value.toInt())
            put(payload)
        }.array()
    }
    private val desc = "64 100 60\np 1 0 base\n"
    private val smart = """{"marker":"jsparse","transparentBackground":true,"emptyText":true,"fields":[{"name":"title","source":"title","token":"jsparse","rect":[0,0,64,100]}]}"""
    private val wrapped = """{"id":"ribbon.sample","rendererApi":2,"engine":"ribbon-exact","canonicalCardCount":4,"canonicalFrameCount":120,"continuousStartFrame":60,"continuousStepFrames":30,"openingEnds":[20,40,60,80],"endHoldFrames":20}"""
    private val scene = """{"api":3,"id":"renderer3.wrap.test","timeline":{"clock":"absolute","frames":120,"implicitAnimation":false},"canvas":{"width":640,"height":360,"fps":60},"wrappedRenderer":"renderer.renderer","features":["project-card-data"],"resources":{"main":{"type":"smart-card-animation","sequenceRoot":"cards/demo"}},"objects":[{"id":"smartCard@0","kind":"smartCard","frame":0,"cardIndex":0,"resource":"main"}]}"""
    private fun archive(includeWrapped: Boolean = true): File {
        val temp = File.createTempFile("cts-wrapped", ".renderer3")
        ZipOutputStream(temp.outputStream()).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("renderer.renderer3", envelope("CCRNDR03", scene))
            if (includeWrapped) put("renderer.renderer", envelope("CCRNDR01", wrapped))
            put("cards/demo/desc.txt", desc.toByteArray())
            put("cards/demo/smart.json", smart.toByteArray())
            put("cards/demo/base/0000.png", byteArrayOf(0, 1))
        }
        return temp
    }

    @Test fun wrappedRibbonCanBeOpenedAndRetimeFourCards() {
        val temp = archive()
        try {
            RendererV3Container.open(temp).use { pkg ->
                assertEquals("ribbon-exact", pkg.wrappedRibbon?.optString("engine"))
                assertEquals("renderer3.wrap.test", pkg.selection.id)
                assertEquals(80, pkg.selection.frameCountForCards(4)) // openingEnd 60 + 20 outro
                val seq = SmartSequenceV3.fromSidecars(pkg, "cards/demo")
                assertEquals(60, seq.fps)
                assertEquals(0, seq.select(0)?.template)
                assertNotNull(seq.select(12))
            }
        } finally { temp.delete() }
    }

    @Test fun absentLegacyWrapperIsRejected() {
        val temp = archive(false)
        try {
            assertThrows(IllegalStateException::class.java) { RendererV3Container.open(temp) }
        } finally { temp.delete() }
    }

    @Test fun unsupportedWrappedEngineIsRejected() {
        val temp = archive()
        try {
            val bytes = envelope("CCRNDR01", wrapped.replace("ribbon-exact", "infinite-timeline-exact"))
            val changed = File.createTempFile("cts-bad-engine", ".renderer3")
            try {
                ZipOutputStream(changed.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("renderer.renderer3"))
                    zip.write(envelope("CCRNDR03", scene))
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry("renderer.renderer"))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                val error = assertThrows(IllegalArgumentException::class.java) {
                    RendererV3Container.open(changed)
                }
                assertTrue(error.message.orEmpty().contains("unsupported legacy engine"))
            } finally { changed.delete() }
        } finally { temp.delete() }
    }
}
