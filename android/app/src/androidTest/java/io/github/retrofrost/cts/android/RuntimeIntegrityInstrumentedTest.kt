package io.github.retrofrost.cts.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeIntegrityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scratch = File(context.cacheDir, "runtime-integrity-${UUID.randomUUID()}").apply { mkdirs() }

    @After
    fun cleanup() {
        scratch.deleteRecursively()
        RendererBridge.setRuntimeActive(RendererSpec.builtIn())
    }

    @Test
    fun sameIdReplacementUsesNewBytesInsteadOfArchivedActiveCopy() {
        val store = RendererStore(File(scratch, "store-root").apply { mkdirs() })
        val id = "test.same-id.renderer"
        val oldSpec = RendererSpec(id = id, name = "Old renderer")
        val newSpec = RendererSpec(
            id = id,
            name = "Corrected renderer",
            tracks = listOf(
                RendererTrack("card.0.x", listOf(RendererKeyframe(0, 0f), RendererKeyframe(1, 9f))),
            ),
        )

        val oldCandidate = store.inspect(ByteArrayInputStream(rendererBytes(oldSpec)))
        store.install(oldCandidate)
        store.activate(id)
        val oldSha = requireNotNull(store.activeSha256())
        assertEquals("Old renderer", store.active().name)

        val newCandidate = store.inspect(ByteArrayInputStream(rendererBytes(newSpec)))
        val installed = store.install(newCandidate)
        val installedSha = requireNotNull(store.installedSha256(id))

        // Same id is NOT enough to call the replacement active. The bytes differ.
        assertNotEquals(oldSha, installedSha)
        assertFalse(installed.active)
        assertFalse(store.listInstalled().single().active)
        assertEquals("Old renderer", store.active().name)
        assertEquals(oldSha, store.activeSha256())

        store.activate(id)
        assertEquals("Corrected renderer", store.active().name)
        assertEquals(installedSha, store.activeSha256())
        assertTrue(store.listInstalled().single().active)
    }

    @Test
    fun megapackArtworkKeepsNaturalCanvasInsteadOf471PixelAppFrame() {
        val sourceWidth = 803
        val sourceHeight = 607
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff2468ac.toInt())
        val png = ByteArrayOutputStream().use { output ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        source.recycle()

        val pack = File(scratch, "natural-artwork.zip")
        val manifest = JSONObject()
            .put("version", 2)
            .put("name", "Natural artwork test")
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Card")
                        .put("value", "1")
                        .put("subject", "art/subject.png"),
                ),
            )
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("megapack.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("art/subject.png"))
            zip.write(png)
            zip.closeEntry()
        }

        val assets = File(scratch, "assets")
        val project = NativeImporters.importMegaPack(pack, assets)
        val imported = BitmapFactory.decodeFile(project.cards.single().image)
        requireNotNull(imported)
        try {
            assertEquals(sourceWidth, imported.width)
            assertEquals(sourceHeight, imported.height)
        } finally {
            imported.recycle()
        }
    }

    @Test
    fun rendererOwnsArtworkFrameAndOnlyBlankBandsAreReclaimed() {
        val spec = RendererSpec(
            referenceHeight = 1080,
            imageHeight = 700f,
            titleHeight = 100f,
            descriptionTop = 950f,
        )
        val full = StudioCard(title = "Title", description = "Description")
        assertEquals(700f, RendererArtworkLayout.imageBottom(full, spec), 0.001f)
        assertEquals(800f, RendererArtworkLayout.imageBottom(full.copy(title = ""), spec), 0.001f)
        assertEquals(830f, RendererArtworkLayout.imageBottom(full.copy(description = ""), spec), 0.001f)
        assertEquals(930f, RendererArtworkLayout.imageBottom(full.copy(title = "", description = ""), spec), 0.001f)
    }

    @Test
    fun nativeTimelineHasNoArchived57CardSpecialCase() {
        val spec = RendererSpec(
            continuousStartFrame = 528,
            continuousStepFrames = 214,
        )
        val project = StudioProject(cards = List(57) { StudioCard(title = "Card $it") })
        assertEquals(528 + (57 - 4) * 214, NativeTimeline.contentEndFrame(project, spec))
    }

    @Test
    fun nativeFrameTimelineTracksAreEvaluatedInFramesNotMilliseconds() {
        val spec = RendererSpec(
            id = "test.native.frame-clock",
            engine = "native-standard",
            timelineUnit = "frames",
            backgroundColor = Color.BLACK,
            bodyInset = 0f,
            bodyWidth = 120f,
            imageHeight = 1080f,
            titleHeight = 0f,
            descriptionTop = 1080f,
            openingStarts = listOf(0),
            openingEnds = listOf(100),
            continuousStartFrame = 100,
            bodySlideFrames = 1,
            canonicalFrameCount = 180,
            tracks = listOf(
                RendererTrack(
                    "card.0.x",
                    listOf(
                        RendererKeyframe(0, 0f),
                        RendererKeyframe(60, 600f),
                    ),
                ),
            ),
        )
        val project = StudioProject(
            cards = listOf(StudioCard(title = "", value = "", description = "")),
            width = 1920,
            height = 1080,
            fps = 60,
        )
        val bitmap = RendererBridge.renderWithSpec(project, spec, 30, 1920, 1080)
        try {
            var firstPainted = -1
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, 500) != Color.BLACK) {
                    firstPainted = x
                    break
                }
            }
            // Frame 30 is halfway between x=0 and x=600. If the app incorrectly
            // converted frame 30 to 500 ms first, this would clamp near x=600.
            assertTrue("Expected frame-addressed x near 300, got $firstPainted", firstPainted in 299..301)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun projectJsonDoesNotArchiveAnOldRendererProfile() {
        val json = StudioProject(name = "No stale renderer").toJson()
        assertFalse(json.contains("what-males-learn-at-each-age"))
        assertFalse(json.contains("model_lock"))
        assertFalse(json.contains("renderer_profile"))
    }

    @Test
    fun rendererStillProducesARealFrameAfterIntegrityChanges() {
        val spec = RendererSpec.builtIn()
        val project = StudioProject(
            cards = listOf(StudioCard(title = "Smoke", value = "42", image = "")),
            width = 1920,
            height = 1080,
            fps = 60,
        )
        val bitmap = RendererBridge.renderWithSpec(project, spec, 0, 320, 180)
        try {
            assertEquals(320, bitmap.width)
            assertEquals(180, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun rendererBytes(spec: RendererSpec): ByteArray = ByteArrayOutputStream().use { output ->
        RendererBundle.write(spec, output)
        output.toByteArray()
    }
}
