package dev.infinitycomparison.cc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.os.Build
import java.io.File
import java.io.FileOutputStream

class ThumbnailExporter(private val context: Context) {
    private val painter = ScenePainter()

    fun exportCuriosityThumbnail(project: CompareProject, format: ThumbnailFormat = ThumbnailFormat.JPEG): File {
        val outDir = File(context.getExternalFilesDir(null), "thumbnails").apply { mkdirs() }
        val ext = if (format == ThumbnailFormat.PNG) "png" else "jpg"
        val outFile = File(outDir, "cubical-thumbnail-ctr-${System.currentTimeMillis()}.$ext")
        if (outFile.exists()) outFile.delete()

        val bitmap = createCompatibleSrgbBitmap(1280, 720)
        try {
            val canvas = Canvas(bitmap)
            painter.drawThumbnail(canvas, project)
            FileOutputStream(outFile).use { stream ->
                when (format) {
                    ThumbnailFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, 94, stream)
                    ThumbnailFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            }
            return outFile
        } finally {
            bitmap.recycle()
        }
    }

    private fun createCompatibleSrgbBitmap(width: Int, height: Int): Bitmap {
        return if (Build.VERSION.SDK_INT >= 26) {
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888,
                false,
                ColorSpace.get(ColorSpace.Named.SRGB)
            )
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }
}

enum class ThumbnailFormat {
    JPEG,
    PNG
}
