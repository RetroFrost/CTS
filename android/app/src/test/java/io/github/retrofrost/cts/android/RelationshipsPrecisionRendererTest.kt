package io.github.retrofrost.cts.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipsPrecisionRendererTest {
    @Test
    fun exactV2IsStrictlyOptIn() {
        assertFalse(RelationshipsPrecisionFrameRenderer.enabled(RendererSpec(engine = "relationships-exact")))
        assertTrue(
            RelationshipsPrecisionFrameRenderer.enabled(
                RendererSpec(
                    engine = "relationships-exact",
                    tags = listOf("relationships.exact.v2=true"),
                ),
            ),
        )
        assertTrue(
            RelationshipsPrecisionFrameRenderer.enabled(
                RendererSpec(
                    engine = "relationships-exact",
                    tags = listOf("relationships.exact.v2=1"),
                ),
            ),
        )
    }


    @Test
    fun sourceLockedRendererRejectsProjectShapeChanges() {
        val spec = RendererSpec(
            id = "relationships.source.locked",
            name = "Relationships Source Exact",
            engine = "relationships-exact",
            precisionMode = "frame-exact",
            referenceWidth = 1920,
            referenceHeight = 1080,
            referenceFps = 60,
            canonicalCardCount = 40,
            canonicalFrameCount = 11130,
            tags = listOf("relationships.exact.v2=true"),
        )
        val changed = StudioProject(
            cards = List(8) { StudioCard(title = "Card ${it + 1}") },
            autoLength = false,
            customLengthSeconds = 153.867,
        )

        val result = RendererProjectGuard.check(changed, spec)

        assertFalse(result.compatible)
        assertTrue(result.issues.any { it.startsWith("card count") })
        assertTrue(result.issues.any { it.startsWith("custom duration") })
    }

    @Test
    fun adaptiveRendererStillAcceptsAnyProjectShape() {
        val project = StudioProject(
            cards = List(3) { StudioCard(title = "Card ${it + 1}") },
            autoLength = false,
            customLengthSeconds = 12.0,
        )
        assertTrue(RendererProjectGuard.check(project, RendererSpec.builtIn()).compatible)
    }



    @Test
    fun renderPassLedgerRejectsDuplicateLogicalElements() {
        val ledger = RenderPassLedger()
        assertTrue(ledger.claim("card.0.body"))
        assertFalse(ledger.claim("card.0.body"))
        assertTrue(ledger.claim("card.0.badge"))
        assertFalse(ledger.claim("card.0.badge"))
        assertTrue(ledger.claim("intro"))
        assertFalse(ledger.claim("intro"))
    }

    @Test
    fun rendererAdvertisesGlobalAntiDuplicationCapabilities() {
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-mask-v1"))
        assertTrue(RendererCapabilities.features.contains("relationships-shadow-outside-v2"))
        assertTrue(RendererCapabilities.features.contains("relationships-single-owner-pass-v1"))
    }



    @Test
    fun frameExactOutputResolutionIsCanonical() {
        val spec = RendererSpec(
            id = "relationships.test.output",
            engine = "relationships-exact",
            precisionMode = "frame-exact",
            referenceWidth = 1920,
            referenceHeight = 1080,
            referenceFps = 60,
            tags = listOf("relationships.exact.v2=true"),
        )
        val project = StudioProject(width = 1280, height = 720, fps = 30)
        val resolved = RenderOutputPolicy.resolve(project, spec)
        assertEquals(1920, resolved.width)
        assertEquals(1080, resolved.height)
        assertEquals(60, resolved.fps)
    }



    @Test
    fun openingCardPositionTrackExpiresBeforeContinuousScroll() {
        val spec = RendererSpec(
            tracks = listOf(
                RendererTrack(
                    target = "card.0.x",
                    keyframes = listOf(
                        RendererKeyframe(374, 0f),
                        RendererKeyframe(899, 0f),
                    ),
                ),
            ),
        )

        assertEquals(0f, spec.trackWindowed("card.0.x", 374))
        assertEquals(0f, spec.trackWindowed("card.0.x", 899))
        assertNull(spec.trackWindowed("card.0.x", 900))

        // Persistent track semantics are unchanged for properties that need them.
        assertEquals(0f, spec.track("card.0.x", 900))
    }

    @Test
    fun rendererAdvertisesWindowedPerCardAnimationTracks() {
        assertTrue(RendererCapabilities.features.contains("relationships-windowed-card-tracks-v1"))
    }

}
