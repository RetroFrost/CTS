package io.github.retrofrost.cts.android.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.model.VisualModel
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CardStripInspection(
    val imageWidth: Int,
    val imageHeight: Int,
    val layout: CardStripLayout,
    val recognition: CardStripRecognition,
    val detectedSeparatorPx: Int,
)

data class CardStripImportResult(
    val sources: List<String>,
    val axis: StripAxis,
)

data class DetectedCardPreview(
    val bitmap: Bitmap,
    val analysis: CardImageAnalysis,
)

object CardStripImporter {
    fun decodeSheetPreview(
        context: Context,
        source: Uri,
        maximumEdgePx: Int = 1_600,
    ): Bitmap {
        require(maximumEdgePx > 0) { "Preview size must be positive." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val sourceOpened = context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        } ?: false
        require(sourceOpened) { "The selected card strip could not be opened." }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "The selected image has no readable dimensions."
        }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > maximumEdgePx) sampleSize *= 2
        return context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: error("The selected card strip could not be previewed.")
    }

    fun inspect(
        context: Context,
        source: Uri,
        cardCount: Int,
        model: VisualModel,
        separatorPx: Int? = null,
        axisOverride: StripAxis? = null,
    ): CardStripInspection {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val sourceOpened = context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
            true
        } ?: false
        require(sourceOpened) { "The selected card strip could not be opened." }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "The selected image has no readable dimensions."
        }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > 2_048) sampleSize *= 2
        val sampled = context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: error("The selected card strip could not be analyzed.")
        val recognition = try {
            val pixels = IntArray(sampled.width * sampled.height)
            sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
            CardStripRecognizer.recognize(
                argb = pixels,
                width = sampled.width,
                height = sampled.height,
                cardCount = cardCount,
                targetAspect = targetArtworkAspect(model),
            )
        } finally {
            sampled.recycle()
        }
        val chosenAxis = axisOverride ?: recognition.selectedAxis
        val candidate = recognition.candidate(chosenAxis)
        val primaryLength = if (chosenAxis == StripAxis.Horizontal) bounds.outWidth else bounds.outHeight
        val maximumSeparator = minOf(
            max(12, primaryLength / cardCount.coerceAtLeast(1) / 10),
            CardStripGeometry.maximumSafeSeparator(primaryLength, candidate.dividerFractions),
        )
        val detectedSeparator = (candidate.separatorFraction * primaryLength).roundToInt()
            .coerceIn(0, maximumSeparator)
        val effectiveSeparator = separatorPx ?: detectedSeparator
        val layout = runCatching {
            CardStripGeometry.fromDividers(
                imageWidth = bounds.outWidth,
                imageHeight = bounds.outHeight,
                axis = chosenAxis,
                dividerFractions = candidate.dividerFractions,
                separatorPx = effectiveSeparator,
            )
        }.getOrElse {
            CardStripGeometry.split(
                imageWidth = bounds.outWidth,
                imageHeight = bounds.outHeight,
                cardCount = cardCount,
                targetAspect = targetArtworkAspect(model),
                separatorPx = effectiveSeparator,
                axisOverride = chosenAxis,
            )
        }
        return CardStripInspection(
            imageWidth = bounds.outWidth,
            imageHeight = bounds.outHeight,
            layout = layout,
            recognition = recognition,
            detectedSeparatorPx = detectedSeparator,
        )
    }

    fun importStrip(
        context: Context,
        source: Uri,
        layout: CardStripLayout,
        reverseOrder: Boolean = false,
    ): CardStripImportResult {

        val outputDirectory = File(
            context.filesDir,
            "card-strips/${UUID.randomUUID()}",
        ).apply { check(mkdirs()) { "Could not create storage for the imported cards." } }

        return runCatching {
            val files = context.contentResolver.openInputStream(source)?.use { stream ->
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                    ?: error("The selected image format cannot be divided into cards.")
                try {
                    layout.regions.mapIndexed { index, region ->
                        val bitmap = decoder.decodeRegion(
                            Rect(region.left, region.top, region.right, region.bottom),
                            BitmapFactory.Options(),
                        )
                            ?: error("Card ${index + 1} could not be decoded.")
                        writeCard(outputDirectory, index, bitmap)
                    }
                } finally {
                    decoder.recycle()
                }
            } ?: error("The selected card strip could not be reopened.")
            val sources = files.map { it.absolutePath }
            CardStripImportResult(if (reverseOrder) sources.reversed() else sources, layout.axis)
        }.onFailure {
            outputDirectory.deleteRecursively()
        }.getOrThrow()
    }

    fun decodePreviews(
        context: Context,
        source: Uri,
        layout: CardStripLayout,
        maximumEdgePx: Int = 420,
    ): List<DetectedCardPreview> {
        require(maximumEdgePx > 0) { "Preview size must be positive." }
        val decoded = mutableListOf<Bitmap>()
        val adaptiveMaximumEdge = minOf(
            maximumEdgePx,
            max(96, (maximumEdgePx * sqrt(24f / layout.regions.size.coerceAtLeast(1))).roundToInt()),
        )
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { stream ->
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                    ?: error("The selected image format cannot be previewed.")
                try {
                    layout.regions.mapTo(decoded) { region ->
                        var sampleSize = 1
                        while (max(region.width, region.height) / sampleSize > adaptiveMaximumEdge) {
                            sampleSize *= 2
                        }
                        decoder.decodeRegion(
                            Rect(region.left, region.top, region.right, region.bottom),
                            BitmapFactory.Options().apply { inSampleSize = sampleSize },
                        ) ?: error("One of the detected cards could not be previewed.")
                    }
                } finally {
                    decoder.recycle()
                }
            } ?: error("The selected card strip could not be reopened.")
            val analyses = CardImageRecognizer.analyze(
                decoded.map { bitmap ->
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    CardRaster(bitmap.width, bitmap.height, pixels)
                },
            )
            decoded.zip(analyses) { bitmap, analysis -> DetectedCardPreview(bitmap, analysis) }
        }.onFailure {
            decoded.forEach { bitmap -> bitmap.recycle() }
        }.getOrThrow()
    }

    private fun writeCard(directory: File, index: Int, bitmap: Bitmap): File {
        val output = File(directory, "card-${(index + 1).toString().padStart(3, '0')}.png")
        try {
            output.outputStream().buffered().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Card ${index + 1} could not be saved."
                }
            }
        } finally {
            bitmap.recycle()
        }
        return output
    }

    private fun targetArtworkAspect(model: VisualModel): Float =
        CardContentLayout.artworkAspect(model)
}
