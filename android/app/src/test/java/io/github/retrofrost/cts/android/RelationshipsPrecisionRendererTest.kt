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
}
