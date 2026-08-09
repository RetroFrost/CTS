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

data class CardStripInspection(
    val imageWidth: Int,
    val imageHeight: Int,
    val layout: CardStripLayout,
)

data class CardStripImportResult(
    val sources: List<String>,
    val axis: StripAxis,
)

object CardStripImporter {
    fun inspect(
        context: Context,
        source: Uri,
        cardCount: Int,
        model: VisualModel,
        separatorPx: Int = CardStripGeometry.DEFAULT_SEPARATOR_PX,
        axisOverride: StripAxis? = null,
    ): CardStripInspection {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("The selected card strip could not be opened.")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "The selected image has no readable dimensions."
        }
        val layout = CardStripGeometry.split(
            imageWidth = bounds.outWidth,
            imageHeight = bounds.outHeight,
            cardCount = cardCount,
            targetAspect = targetArtworkAspect(model),
            separatorPx = separatorPx,
            axisOverride = axisOverride,
        )
        return CardStripInspection(bounds.outWidth, bounds.outHeight, layout)
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
    ): List<Bitmap> {
        require(maximumEdgePx > 0) { "Preview size must be positive." }
        val decoded = mutableListOf<Bitmap>()
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { stream ->
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                    ?: error("The selected image format cannot be previewed.")
                try {
                    layout.regions.mapTo(decoded) { region ->
                        var sampleSize = 1
                        while (max(region.width, region.height) / sampleSize > maximumEdgePx * 2) {
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
