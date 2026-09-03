#!/usr/bin/env python3
"""Extend the Android Renderer v3 smoke suite with pixel-level dedicated feature tests."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / "android/app/src/androidTest/java/io/github/retrofrost/cts/android/RendererV3InstrumentedTest.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


text = TEST.read_text()
if 'import android.graphics.Bitmap\n' not in text:
    text = text.replace('package io.github.retrofrost.cts.android\n\n', 'package io.github.retrofrost.cts.android\n\nimport android.graphics.Bitmap\nimport android.graphics.Color\n', 1)

anchor = '''    private fun sourceScene(): JSONObject {
'''
tests = '''    @Test
    fun dedicatedRendererV3FeaturesAreRealAndPreviewExportPixelsMatch() {
        val scene = dedicatedFeatureScene()
        val packageBytes = renderer3Zip(
            scene,
            mapOf(
                "assets/text.png" to solidPng(Color.MAGENTA, 4, 4),
                "assets/outro-2.png" to solidPng(Color.GREEN, 64, 64),
            ),
        )
        val read = RendererV3Bundle.read(packageBytes)

        assertTrue("raw-frame-tracks" in RendererCapabilities.features)
        assertTrue("source-baked-text-raster" in RendererCapabilities.features)
        assertTrue("independent-shadow-resource" in RendererCapabilities.features)
        assertTrue("frame-addressed-selectors" in RendererCapabilities.features)
        assertTrue("exact-outro-overlay" in RendererCapabilities.features)
        assertTrue("preview-export-identical-path" in RendererCapabilities.features)

        val project = StudioProject(width = 64, height = 64, fps = 60)
        val renderer = RendererV3FrameRenderer()
        val frame0 = renderer.render(project, read.spec, 0, 64, 64)

        // Dedicated text-raster resource: these pixels came from the PNG sidecar,
        // not Android text shaping.
        assertEquals(Color.MAGENTA, frame0.getPixel(3, 3))
        // Independent shadow is a separate layer borrowing the badge silhouette.
        assertEquals(Color.BLACK, frame0.getPixel(23, 15))
        // Target badge remains independently rendered above its shadow.
        assertEquals(Color.RED, frame0.getPixel(15, 15))

        val rgba = renderer.renderRgba(project, read.spec, 0, 64, 64)
        assertArrayEquals(bitmapRgba(frame0), rgba)
        frame0.recycle()

        // Exact outro overlay owns its declared frame and replaces earlier pixels.
        val outro = renderer.render(project, read.spec, 2, 64, 64)
        assertEquals(Color.GREEN, outro.getPixel(32, 32))
        outro.recycle()

        val badge = requireNotNull(read.scene.objectById("badge@0"))
        val frame1Props = RendererV3Evaluator.properties(read.scene, badge, 1)
        assertEquals(2.0, (frame1Props["movement.x"] as Number).toDouble(), 0.0001)
    }

    private fun dedicatedFeatureScene(): JSONObject {
        val badgePoints = JSONArray()
            .put(JSONArray().put(10).put(10))
            .put(JSONArray().put(20).put(10))
            .put(JSONArray().put(20).put(20))
            .put(JSONArray().put(10).put(20))
        val resources = JSONObject()
            .put("badge-shape", JSONObject().put("type", "polygon").put("points", badgePoints).put("fill", "#FFFF0000"))
            .put(
                "badge-shadow",
                JSONObject()
                    .put("type", "independent-shadow")
                    .put("target", "badge@0")
                    .put("offsetX", 6)
                    .put("offsetY", 0)
                    .put("blur", 0)
                    .put("alpha", 1.0)
                    .put("color", "#FF000000"),
            )
            .put(
                "baked-text",
                JSONObject()
                    .put("type", "text-raster")
                    .put("source", "assets/text.png")
                    .put("sampling", "nearest")
                    .put("x", 2)
                    .put("y", 2)
                    .put("width", 4)
                    .put("height", 4),
            )
            .put(
                "exact-outro",
                JSONObject()
                    .put("type", "outro-overlay")
                    .put("startFrame", 2)
                    .put("endFrame", 2)
                    .put("assetPattern", "assets/outro-{frame}.png")
                    .put("replaceCanvas", true)
                    .put("sampling", "nearest")
                    .put("x", 0)
                    .put("y", 0)
                    .put("width", 64)
                    .put("height", 64),
            )

        val shadow = JSONObject()
            .put("id", "shadow@0")
            .put("kind", "shadow")
            .put("frame", 0)
            .put("resource", "badge-shadow")
        val badge = JSONObject()
            .put("id", "badge@0")
            .put("kind", "badge")
            .put("frame", 0)
            .put("resource", "badge-shape")
            .put(
                "properties",
                JSONObject().put(
                    "movement",
                    JSONObject().put(
                        "x",
                        JSONObject()
                            .put("dense", JSONObject().put("start", 0).put("values", JSONArray().put(0).put(2).put(4)))
                            .put("interpolation", "raw"),
                    ),
                ),
            )
        val raster = JSONObject()
            .put("id", "text@0")
            .put("kind", "text-raster")
            .put("frame", 0)
            .put("resource", "baked-text")
        val outro = JSONObject()
            .put("id", "outro@2")
            .put("kind", "outro")
            .put("frame", 2)
            .put("resource", "exact-outro")
            .put("lifespan", JSONObject().put("start", 2).put("end", 2))

        val selector = JSONObject()
            .put("select", "badge[frame=0]")
            .put("timeline", "relative")
            .put("properties", JSONObject().put("opacity", JSONObject().put("value", 1.0)))

        return JSONObject()
            .put("api", 3)
            .put("id", "v3-dedicated-features")
            .put("name", "Renderer v3 Dedicated Features")
            .put("background", "#FFFFFFFF")
            .put("canvas", JSONObject().put("width", 64).put("height", 64).put("fps", 60))
            .put("timeline", JSONObject().put("frames", 3).put("clock", "absolute").put("implicitAnimation", false))
            .put(
                "features",
                JSONArray()
                    .put("renderer-api-v3-scene-ir")
                    .put("raw-frame-tracks")
                    .put("source-baked-text-raster")
                    .put("independent-shadow-resource")
                    .put("frame-addressed-selectors")
                    .put("exact-outro-overlay")
                    .put("preview-export-identical-path"),
            )
            .put("resources", resources)
            .put("objects", JSONArray().put(shadow).put(badge).put(raster).put(outro))
            .put("selectors", JSONArray().put(selector))
            .put("layers", JSONArray().put("shadow@0").put("badge@0").put("text@0").put("outro@2"))
            .put("checkpoints", JSONArray().put(0).put(2))
    }

    private fun solidPng(color: Int, width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun renderer3Zip(scene: JSONObject, assets: Map<String, ByteArray>): ByteArray {
        val sceneBytes = renderer3(scene)
        return ByteArrayOutputStream().use { destination ->
            ZipOutputStream(destination).use { output ->
                output.putNextEntry(ZipEntry("renderer.renderer3"))
                output.write(sceneBytes)
                output.closeEntry()
                assets.forEach { (name, bytes) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            destination.toByteArray()
        }
    }

    private fun bitmapRgba(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteArray(pixels.size * 4)
        var cursor = 0
        pixels.forEach { argb ->
            bytes[cursor++] = Color.red(argb).toByte()
            bytes[cursor++] = Color.green(argb).toByte()
            bytes[cursor++] = Color.blue(argb).toByte()
            bytes[cursor++] = Color.alpha(argb).toByte()
        }
        return bytes
    }

'''
if tests not in text:
    if text.count(anchor) != 1:
        raise SystemExit("Renderer v3 instrumentation test anchor changed")
    text = text.replace(anchor, tests + anchor, 1)

TEST.write_text(text)
print("Added pixel-level Renderer v3 dedicated feature tests")
