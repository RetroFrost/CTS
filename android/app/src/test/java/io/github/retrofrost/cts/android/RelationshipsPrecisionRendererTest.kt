package io.github.retrofrost.cts.android

import org.junit.Assert.assertFalse
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

}
