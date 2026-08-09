package io.github.retrofrost.cts.android.importer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class CardStripCandidate(
    val axis: StripAxis,
    val dividerFractions: List<Float>,
    val dividerConfidences: List<Float>,
    val separatorFraction: Float,
    val confidence: Float,
)

data class CardStripRecognition(
    val horizontal: CardStripCandidate,
    val vertical: CardStripCandidate,
    val selectedAxis: StripAxis,
) {
    fun candidate(axis: StripAxis): CardStripCandidate =
        if (axis == StripAxis.Horizontal) horizontal else vertical
}

/** Pixel-based boundary detection; input may be a downsampled version of the source sheet. */
object CardStripRecognizer {
    fun recognize(
        argb: IntArray,
        width: Int,
        height: Int,
        cardCount: Int,
        targetAspect: Float,
    ): CardStripRecognition {
        require(width > 0 && height > 0 && argb.size >= width * height)
        require(cardCount > 0)
        val horizontal = candidate(argb, width, height, cardCount, targetAspect, StripAxis.Horizontal)
        val vertical = candidate(argb, width, height, cardCount, targetAspect, StripAxis.Vertical)
        return CardStripRecognition(
            horizontal = horizontal,
            vertical = vertical,
            selectedAxis = if (horizontal.confidence >= vertical.confidence) {
                StripAxis.Horizontal
            } else {
                StripAxis.Vertical
            },
        )
    }

    private fun candidate(
        argb: IntArray,
        width: Int,
        height: Int,
        cardCount: Int,
        targetAspect: Float,
        axis: StripAxis,
    ): CardStripCandidate {
        val primary = if (axis == StripAxis.Horizontal) width else height
        if (cardCount > 1 && primary < cardCount * 2) {
            val panelAspect = if (axis == StripAxis.Horizontal) {
                width / cardCount.toFloat() / height
            } else {
                width / (height / cardCount.toFloat()).coerceAtLeast(0.0001f)
            }
            val aspectScore = exp(
                -2.2f * abs(ln(panelAspect.coerceAtLeast(0.0001f) / targetAspect.coerceAtLeast(0.0001f))),
            )
            return CardStripCandidate(
                axis = axis,
                dividerFractions = (1 until cardCount).map { it / cardCount.toFloat() },
                dividerConfidences = List(cardCount - 1) { 0f },
                separatorFraction = 0f,
                confidence = aspectScore * 0.25f,
            )
        }
        val lines = lineStats(argb, width, height, axis)
        val spacing = primary.toFloat() / cardCount
        val dividers = mutableListOf<Float>()
        val confidences = mutableListOf<Float>()
        val separatorWidths = mutableListOf<Int>()
        var previous = 0

        for (index in 1 until cardCount) {
            val expected = index * spacing
            val radius = max(3, (spacing * 0.32f).toInt())
            val lowerByExpected = max(1, expected.toInt() - radius)
            val upperByExpected = min(primary - 2, expected.toInt() + radius)
            val minimumSpacing = max(2, (spacing * 0.28f).toInt())
            val lower = max(lowerByExpected, previous + minimumSpacing)
            val upper = min(upperByExpected, primary - (cardCount - index) * minimumSpacing)
            val best = if (lower <= upper) {
                (lower..upper).maxByOrNull { position -> boundaryScore(lines, position) }
                    ?: expected.toInt().coerceIn(1, primary - 2)
            } else {
                expected.toInt().coerceIn(previous + 1, primary - 2)
            }
            val score = boundaryScore(lines, best).coerceIn(0f, 1f)
            val run = separatorRun(lines, best, max(2, (spacing * 0.12f).toInt()))
            val reachedSearchEdge = best - run.first >= max(2, (spacing * 0.12f).toInt()) ||
                run.last - best >= max(2, (spacing * 0.12f).toInt())
            val separatorWidth = if (reachedSearchEdge) 0 else run.last - run.first + 1
            val center = if (separatorWidth == 0) best.toFloat() else (run.first + run.last + 1) / 2f
            dividers += (center / primary).coerceIn(0.001f, 0.999f)
            confidences += score
            separatorWidths += separatorWidth
            previous = best
        }

        val typicalSeparator = separatorWidths.sorted().let { widths ->
            if (widths.isEmpty()) 0f else widths[(widths.size - 1) / 2].toFloat() / primary
        }
        val boundaries = listOf(0f) + dividers + listOf(1f)
        val aspectDistance = boundaries.zipWithNext().map { (start, end) ->
            val primaryFraction = (end - start - typicalSeparator).coerceAtLeast(1f / primary)
            val panelAspect = if (axis == StripAxis.Horizontal) {
                primaryFraction * width / height.toFloat()
            } else {
                width / (primaryFraction * height)
            }
            abs(ln(panelAspect.coerceAtLeast(0.0001f) / targetAspect.coerceAtLeast(0.0001f)))
        }.average().toFloat()
        val aspectScore = exp(-2.2f * aspectDistance).coerceIn(0f, 1f)
        val boundaryConfidence = if (confidences.isEmpty()) aspectScore else confidences.average().toFloat()
        val widths = boundaries.zipWithNext().map { (start, end) -> end - start }
        val meanWidth = widths.average().toFloat().coerceAtLeast(0.0001f)
        val evenness = (1f - widths.map { abs(it - meanWidth) / meanWidth }.average().toFloat())
            .coerceIn(0f, 1f)
        val confidence = (boundaryConfidence * 0.58f + aspectScore * 0.36f + evenness * 0.06f)
            .coerceIn(0f, 1f)

        return CardStripCandidate(
            axis = axis,
            dividerFractions = dividers,
            dividerConfidences = confidences,
            separatorFraction = typicalSeparator,
            confidence = confidence,
        )
    }

    private data class LineStats(
        val red: Float,
        val green: Float,
        val blue: Float,
        val variance: Float,
    )

    private fun lineStats(
        argb: IntArray,
        width: Int,
        height: Int,
        axis: StripAxis,
    ): List<LineStats> {
        val primary = if (axis == StripAxis.Horizontal) width else height
        val secondary = if (axis == StripAxis.Horizontal) height else width
        val secondaryStep = max(1, secondary / 256)
        return List(primary) { position ->
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumSquares = 0f
            var count = 0
            var cross = 0
            while (cross < secondary) {
                val x = if (axis == StripAxis.Horizontal) position else cross
                val y = if (axis == StripAxis.Horizontal) cross else position
                val pixel = argb[y * width + x]
                val r = ((pixel ushr 16) and 0xff) / 255f
                val g = ((pixel ushr 8) and 0xff) / 255f
                val b = (pixel and 0xff) / 255f
                sumR += r
                sumG += g
                sumB += b
                sumSquares += r * r + g * g + b * b
                count++
                cross += secondaryStep
            }
            val safeCount = count.coerceAtLeast(1)
            val meanR = sumR / safeCount
            val meanG = sumG / safeCount
            val meanB = sumB / safeCount
            val variance = (sumSquares / safeCount - meanR * meanR - meanG * meanG - meanB * meanB)
                .coerceAtLeast(0f) / 3f
            LineStats(meanR, meanG, meanB, variance)
        }
    }

    private fun boundaryScore(lines: List<LineStats>, position: Int): Float {
        if (position <= 0 || position >= lines.lastIndex) return 0f
        val center = lines[position]
        val left = lines[max(0, position - 2)]
        val right = lines[min(lines.lastIndex, position + 2)]
        val edge = colorDistance(left, right)
        val contrast = (colorDistance(center, left) + colorDistance(center, right)) / 2f
        val uniformity = (1f - sqrt(center.variance).coerceIn(0f, 1f))
        return (sqrt(edge) * 0.45f + sqrt(contrast) * 0.35f + uniformity * 0.20f)
            .coerceIn(0f, 1f)
    }

    private fun separatorRun(lines: List<LineStats>, center: Int, maximumRadius: Int): IntRange {
        val reference = lines[center]
        var start = center
        var end = center
        while (start > 0 && center - start < maximumRadius && sameSeparator(lines[start - 1], reference)) start--
        while (end < lines.lastIndex && end - center < maximumRadius && sameSeparator(lines[end + 1], reference)) end++
        return start..end
    }

    private fun sameSeparator(candidate: LineStats, reference: LineStats): Boolean =
        candidate.variance <= 0.018f &&
            reference.variance <= 0.025f &&
            colorDistance(candidate, reference) <= 0.055f

    private fun colorDistance(first: LineStats, second: LineStats): Float {
        val dr = first.red - second.red
        val dg = first.green - second.green
        val db = first.blue - second.blue
        return sqrt((dr * dr + dg * dg + db * db) / 3f).coerceIn(0f, 1f)
    }
}
