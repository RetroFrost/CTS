package io.github.retrofrost.cts.android.export

import android.app.ActivityManager
import android.content.Context
import android.media.MediaFormat
import io.github.retrofrost.cts.android.model.ExportSettings

/** Suggests initial settings only; chosen export settings and project timing remain editable. */
internal object DeviceExportPolicy {
    private const val THREE_GIB = 3L * 1024L * 1024L * 1024L
    private data class Target(val width: Int, val height: Int, val fps: Int, val bitrate: Int)

    fun recommend(
        lowRamDevice: Boolean,
        totalRamBytes: Long,
        supportsHardwareTarget: (width: Int, height: Int, fps: Int) -> Boolean,
    ): ExportSettings {
        val limited = lowRamDevice || (totalRamBytes in 1 until THREE_GIB)
        val candidates = if (limited) {
            listOf(Target(1280, 720, 30, 4_000_000), Target(640, 360, 30, 1_500_000))
        } else {
            listOf(
                Target(1920, 1080, 60, 6_000_000),
                Target(1920, 1080, 30, 6_000_000),
                Target(1280, 720, 30, 4_000_000),
                Target(640, 360, 30, 1_500_000),
            )
        }
        // On a software-only device, use the smallest initial output rather than OOM on 1080p.
        val selected = candidates.firstOrNull {
            supportsHardwareTarget(it.width, it.height, it.fps)
        } ?: candidates.last()
        return ExportSettings(
            width = selected.width,
            height = selected.height,
            fps = selected.fps,
            videoBitrate = selected.bitrate,
            videoMime = MediaFormat.MIMETYPE_VIDEO_AVC,
        )
    }

    fun defaultFor(context: Context): ExportSettings {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val ram = runCatching {
            if (manager != null) manager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem
        }.getOrDefault(0L)
        return recommend(manager?.isLowRamDevice == true, ram) { width, height, fps ->
            CodecCatalog.bestAutomaticVideoEncoder(
                mime = MediaFormat.MIMETYPE_VIDEO_AVC, width = width, height = height, fps = fps,
            )?.hardwareAccelerated == true
        }
    }
}
