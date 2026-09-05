package io.github.retrofrost.cts.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererV3ProjectDataContractTest {
    private fun spec() = RendererSpec(
        id = "watchdata-puberty-data-driven-1.4.1",
        name = "WatchData Puberty data-driven test",
        rendererApi = 3,
        engine = "scene-v3",
        precisionMode = "frame-exact",
        timelineUnit = "frames",
        minAppVersion = "3.0.300",
        canonicalCardCount = 50,
        requiredFeatures = listOf("project-card-data"),
    )

    @Test
    fun projectDataRendererAcceptsVariableCardCountsWithinMeasuredCapacity() {
        val project = StudioProject(cards = List(3) { index -> StudioCard(title = "Card ${index + 1}", value = "${index + 1}") })
        assertTrue(RendererCapabilities.report(spec()).compatible)
        assertTrue(RendererProjectGuard.check(project, spec()).compatible)
    }

    @Test
    fun projectDataRendererRejectsOverflowInsteadOfSilentlyDroppingCards() {
        val project = StudioProject(cards = List(51) { index -> StudioCard(title = "Card ${index + 1}", value = "${index + 1}") })
        val result = RendererProjectGuard.check(project, spec())
        assertFalse(result.compatible)
        assertTrue(result.issues.any { it.contains("at most 50") })
    }

    @Test
    fun legacyPubertyPackageIsMigratedToProjectDataAtRuntime() {
        val legacy = spec().copy(
            id = "watchdata-puberty-source-exact-1.4",
            requiredFeatures = emptyList(),
        )
        assertTrue(RendererV3ProjectData.enabled(legacy))
    }
}
