package io.github.retrofrost.cts.android

import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the 3.0.300 Puberty Renderer v3 import dialog. */
class RendererV3CompatibilityTest {
    @Test
    fun app30300AcceptsRenderer30300PlusAndAnimatedRectClip() {
        val spec = RendererSpec(
            id = "puberty-v3-compatibility-test",
            name = "Puberty Renderer v3 Compatibility Test",
            rendererApi = 3,
            engine = "scene-v3",
            precisionMode = "frame-exact",
            timelineUnit = "frames",
            minAppVersion = "3.0.300+",
            referenceWidth = 1920,
            referenceHeight = 1080,
            referenceFps = 60,
            requiredFeatures = listOf(
                "renderer-api-v3-scene-ir",
                "animated-rect-clip",
                "raw-frame-tracks",
                "source-baked-text-raster",
                "independent-shadow-resource",
                "frame-addressed-selectors",
                "exact-outro-overlay",
                "preview-export-identical-path",
            ),
        )

        val report = RendererCapabilities.report(spec)
        assertTrue(
            "3.0.300 must accept a renderer requiring 3.0.300+ with the Puberty v3 feature set; errors=${report.errors}",
            report.compatible,
        )
    }
}
