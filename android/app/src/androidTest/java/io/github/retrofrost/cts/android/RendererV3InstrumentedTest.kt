package io.github.retrofrost.cts.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RendererV3InstrumentedTest {
    @Test
    fun rawRenderer3LoadsAndSelectorCascadeIsDeterministic() {
        val scene = sourceScene()
        val read = RendererV3Bundle.read(renderer3(scene))

        assertEquals(3, read.spec.rendererApi)
        assertEquals("scene-v3", read.spec.engine)
        assertEquals(240, read.spec.canonicalFrameCount)
        assertEquals("v3-smoke", read.scene.id)

        val first = requireNotNull(read.scene.objectById("badge@0"))
        val firstProperties = RendererV3Evaluator.properties(read.scene, first, 0)
        // Object property wins over badge[*] on only the property it declares.
        assertEquals(9.0, (firstProperties["movement.x"] as Number).toDouble(), 0.0001)
        assertEquals(-100.0, (firstProperties["movement.y"] as Number).toDouble(), 0.0001)

        val second = requireNotNull(read.scene.objectById("badge@120"))
        val secondProperties = RendererV3Evaluator.properties(read.scene, second, 121)
        assertEquals(1.0, (secondProperties["movement.x"] as Number).toDouble(), 0.0001)
        assertEquals(-50.0, (secondProperties["movement.y"] as Number).toDouble(), 0.0001)
    }

    @Test
    fun renderer3ZipRetainsSidecarResources() {
        val sceneBytes = renderer3(sourceScene())
        val sidecar = byteArrayOf(1, 3, 3, 7)
        val zip = ByteArrayOutputStream().use { destination ->
            ZipOutputStream(destination).use { output ->
                output.putNextEntry(ZipEntry("renderer.renderer3"))
                output.write(sceneBytes)
                output.closeEntry()
                output.putNextEntry(ZipEntry("assets/sample.bin"))
                output.write(sidecar)
                output.closeEntry()
            }
            destination.toByteArray()
        }

        val read = RendererV3Bundle.read(zip)
        assertArrayEquals(sidecar, read.scene.asset("assets/sample.bin"))
        assertArrayEquals(sidecar, read.scene.asset("sample.bin"))
    }

    @Test
    fun appBuildAdvertisesRendererApi3AfterBuildPatch() {
        assertEquals("3.0.300", BuildConfig.VERSION_NAME.substringBefore('-'))
        assertEquals(3, RendererCapabilities.RENDERER_API)
        assertTrue("scene-v3" in RendererCapabilities.engines)
        assertTrue("renderer-api-v3-scene-ir" in RendererCapabilities.features)
    }

    private fun sourceScene(): JSONObject = JSONObject().apply {
        put("api", 3)
        put("id", "v3-smoke")
        put("name", "Renderer v3 Smoke")
        put("canvas", JSONObject().put("width", 1920).put("height", 1080).put("fps", 60))
        put("timeline", JSONObject().put("frames", 240).put("clock", "absolute").put("implicitAnimation", false))
        put("features", JSONArray().put("renderer-api-v3-scene-ir"))
        put("resources", JSONObject().put("badge", JSONObject()
            .put("type", "polygon")
            .put("points", JSONArray()
                .put(JSONArray().put(0).put(0))
                .put(JSONArray().put(100).put(0))
                .put(JSONArray().put(100).put(100))
                .put(JSONArray().put(0).put(100)))))
        put("objects", JSONArray()
            .put(JSONObject()
                .put("id", "badge@0")
                .put("kind", "badge")
                .put("frame", 0)
                .put("resource", "badge")
                .put("properties", JSONObject()
                    .put("movement", JSONObject().put("x", JSONObject().put("value", 9)))))
            .put(JSONObject()
                .put("id", "badge@120")
                .put("kind", "badge")
                .put("frame", 120)
                .put("resource", "badge")))
        put("selectors", JSONArray()
            .put(JSONObject()
                .put("select", "badge[*]")
                .put("timeline", "relative")
                .put("properties", JSONObject()
                    .put("movement", JSONObject()
                        .put("x", JSONObject().put("value", 1)))))
            .put(JSONObject()
                .put("select", "badge[frame>=0]")
                .put("timeline", "relative")
                .put("properties", JSONObject()
                    .put("movement", JSONObject()
                        .put("y", JSONObject()
                            .put("dense", JSONObject()
                                .put("start", 0)
                                .put("values", JSONArray().put(-100).put(-50).put(0)))
                            .put("interpolation", "raw")
                            .put("extrapolate", "hold")))))
        put("layers", JSONArray().put("badge@0").put("badge@120"))
        put("checkpoints", JSONArray().put(JSONObject().put("frame", 0)).put(JSONObject().put("frame", 120)))
    }

    private fun renderer3(scene: JSONObject): ByteArray {
        val manifest = scene.toString().toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream().use { destination ->
            GZIPOutputStream(destination).use { it.write(manifest) }
            destination.toByteArray()
        }
        val crc = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream().use { destination ->
            DataOutputStream(destination).use { data ->
                data.write("CCRNDR03".toByteArray(Charsets.US_ASCII))
                data.writeInt(1)
                data.writeInt(payload.size)
                data.writeInt(crc.toInt())
                data.write(payload)
            }
            destination.toByteArray()
        }
    }
}
