package io.github.retrofrost.cts.android.importer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class CardRaster(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

data class CardImageAnalysis(
    val blank: Boolean,
    val duplicateOf: Int?,
    val confidence: Float,
    val suggestedFocusX: Float,
    val suggestedFocusY: Float,
    val suggestedZoom: Float,
)

/** Lightweight on-device quality and saliency analysis; no network model is required. */
object CardImageRecognizer {
    private const val GRID = 20

    fun analyze(rasters: List<CardRaster>): List<CardImageAnalysis> =
        analyzeFeatures(rasters.map(::features))

    fun analyze(rasters: Sequence<CardRaster>): List<CardImageAnalysis> =
        analyzeFeatures(rasters.map(::features).toList())

    private fun analyzeFeatures(features: List<Features>): List<CardImageAnalysis> {
        return features.mapIndexed { index, feature ->
            val duplicate = (0 until index).firstOrNull { earlier ->
                fingerprintDistance(feature.fingerprint, features[earlier].fingerprint) < 0.022f
            }
            val blank = feature.variance < 0.0014f || feature.visibleFraction < 0.04f
            CardImageAnalysis(
                blank = blank,
                duplicateOf = duplicate,
                confidence = when {
                    blank -> 0.18f
                    duplicate != null -> 0.48f
                    else -> (0.62f + min(0.34f, feature.variance * 6f)).coerceIn(0f, 1f)
                },
                suggestedFocusX = feature.focusX,
                suggestedFocusY = feature.focusY,
                suggestedZoom = feature.zoom,
            )
        }
    }

    private data class Features(
        val fingerprint: FloatArray,
        val variance: Float,
        val visibleFraction: Float,
        val focusX: Float,
        val focusY: Float,
        val zoom: Float,
    )

    private fun features(raster: CardRaster): Features {
        require(raster.width > 0 && raster.height > 0 && raster.argb.size >= raster.width * raster.height)
        val fingerprint = FloatArray(GRID * GRID * 3)
        val luma = FloatArray(GRID * GRID)
        val alphaGrid = FloatArray(GRID * GRID)
        var visible = 0
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val x = ((gx + 0.5f) * raster.width / GRID).toInt().coerceIn(0, raster.width - 1)
                val y = ((gy + 0.5f) * raster.height / GRID).toInt().coerceIn(0, raster.height - 1)
                val pixel = raster.argb[y * raster.width + x]
                val alpha = ((pixel ushr 24) and 0xff) / 255f
                val red = ((pixel ushr 16) and 0xff) / 255f
                val green = ((pixel ushr 8) and 0xff) / 255f
                val blue = (pixel and 0xff) / 255f
                val base = (gy * GRID + gx) * 3
                fingerprint[base] = red * alpha
                fingerprint[base + 1] = green * alpha
                fingerprint[base + 2] = blue * alpha
                luma[gy * GRID + gx] = (red * 0.2126f + green * 0.7152f + blue * 0.0722f) * alpha
                alphaGrid[gy * GRID + gx] = alpha
                if (alpha > 0.1f) visible++
            }
        }
        val mean = luma.average().toFloat()
        val variance = luma.map { value -> (value - mean) * (value - mean) }.average().toFloat()
        val borderIndices = buildList {
            for (cell in 0 until GRID) {
                add(cell)
                add((GRID - 1) * GRID + cell)
                add(cell * GRID)
                add(cell * GRID + GRID - 1)
            }
        }.distinct()
        val borderLuma = borderIndices.map(luma::get).average().toFloat()
        val alphaIsMeaningful = alphaGrid.any { it < 0.92f }
        var totalWeight = 0f
        var weightedX = 0f
        var weightedY = 0f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val index = gy * GRID + gx
                val center = luma[index]
                val left = luma[gy * GRID + max(0, gx - 1)]
                val right = luma[gy * GRID + min(GRID - 1, gx + 1)]
                val top = luma[max(0, gy - 1) * GRID + gx]
                val bottom = luma[min(GRID - 1, gy + 1) * GRID + gx]
                val edge = abs(right - left) + abs(bottom - top)
                val contrast = abs(center - mean)
                val foreground = if (alphaIsMeaningful) {
                    alphaGrid[index]
                } else {
                    abs(center - borderLuma).coerceIn(0f, 1f)
                }
                val weight = edge * 0.48f + contrast * 0.18f + foreground * 0.34f + 0.0001f
                totalWeight += weight
                weightedX += ((gx + 0.5f) / GRID) * weight
                weightedY += ((gy + 0.5f) / GRID) * weight
            }
        }
        val focusX = (weightedX / totalWeight.coerceAtLeast(0.0001f)).coerceIn(0.12f, 0.88f)
        val focusY = (weightedY / totalWeight.coerceAtLeast(0.0001f)).coerceIn(0.12f, 0.88f)
        val weightedCoverage = (totalWeight / (GRID * GRID)).coerceIn(0f, 1f)
        val distanceFromCenter = abs(focusX - 0.5f) + abs(focusY - 0.5f)
        val zoom = (1.02f + distanceFromCenter * 0.16f + (0.08f - weightedCoverage).coerceAtLeast(0f))
            .coerceIn(1f, 1.24f)
        return Features(
            fingerprint = fingerprint,
            variance = variance,
            visibleFraction = visible / (GRID * GRID).toFloat(),
            focusX = focusX,
            focusY = focusY,
            zoom = zoom,
        )
    }

    private fun fingerprintDistance(first: FloatArray, second: FloatArray): Float =
        first.indices.sumOf { index -> abs(first[index] - second[index]).toDouble() }.toFloat() /
            first.size.coerceAtLeast(1)
}
