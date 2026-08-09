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

data class CardStripImportResult(
    val sources: List<String>,
    val axis: StripAxis,
)

object CardStripImporter {
    fun importStrip(
        context: Context,
        source: Uri,
        cardCount: Int,
        model: VisualModel,
        separatorPx: Int = CardStripGeometry.DEFAULT_SEPARATOR_PX,
    ): CardStripImportResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("The selected card strip could not be opened.")
        val layout = CardStripGeometry.split(
            imageWidth = bounds.outWidth,
            imageHeight = bounds.outHeight,
            cardCount = cardCount,
            targetAspect = targetArtworkAspect(model),
            separatorPx = separatorPx,
        )

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
            CardStripImportResult(files.map { it.absolutePath }, layout.axis)
        }.onFailure {
            outputDirectory.deleteRecursively()
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
