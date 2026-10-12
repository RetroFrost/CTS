package io.github.retrofrost.cts.android.export

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

data class EncoderChoice(
    val name: String,
    val label: String,
    val mime: String,
    val hardwareAccelerated: Boolean = false,
    val vendor: Boolean = false,
)

object CodecCatalog {
    private data class VideoCandidate(
        val choice: EncoderChoice,
        val supportsTarget: Boolean,
        val performanceGuaranteed: Boolean,
        val maximumFrameRate: Double,
    )

    fun videoEncoders(): List<EncoderChoice> = videoCandidates().map { it.choice }

    /**
     * Select the quickest power-efficient encoder that explicitly supports this export.
     * Hardware codecs avoid the CPU-heavy software fallback. Performance points and the
     * advertised maximum frame rate then distinguish between multiple hardware codecs.
     */
    fun bestAutomaticVideoEncoder(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
    ): EncoderChoice? = videoCandidates(width, height, fps)
        .asSequence()
        .filter { it.choice.mime == mime && it.supportsTarget }
        .sortedWith(
            compareByDescending<VideoCandidate> { it.choice.hardwareAccelerated }
                .thenByDescending { it.performanceGuaranteed }
                .thenByDescending { it.choice.vendor }
                .thenByDescending { it.maximumFrameRate }
                .thenBy { it.choice.name },
        )
        .firstOrNull()
        ?.choice

    private fun videoCandidates(
        width: Int? = null,
        height: Int? = null,
        fps: Int? = null,
    ): List<VideoCandidate> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filterNot { Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it.isAlias }
            .flatMap { info ->
                info.supportedTypes.asSequence()
                    .filter { it == MediaFormat.MIMETYPE_VIDEO_AVC || it == MediaFormat.MIMETYPE_VIDEO_HEVC }
                    .mapNotNull { mime -> videoCandidate(info, mime, width, height, fps) }
            }
            .distinctBy { it.choice.name to it.choice.mime }
            .sortedWith(
                compareBy<VideoCandidate> { it.choice.mime != MediaFormat.MIMETYPE_VIDEO_AVC }
                    .thenByDescending { it.choice.hardwareAccelerated }
                    .thenBy { it.choice.name },
            )
            .toList()
    }.getOrDefault(emptyList())

    private fun videoCandidate(
        info: MediaCodecInfo,
        mime: String,
        width: Int?,
        height: Int?,
        fps: Int?,
    ): VideoCandidate? = runCatching {
        val capabilities = info.getCapabilitiesForType(mime)
        val video = capabilities.videoCapabilities ?: return@runCatching null
        val byteBufferYuv = capabilities.colorFormats.any { format ->
            format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        }
        if (!byteBufferYuv) return@runCatching null
        val hardware = isHardwareAccelerated(info)
        val vendor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isVendor
        val hasTarget = width != null && height != null && fps != null
        val supportsTarget = !hasTarget || video.areSizeAndRateSupported(width!!, height!!, fps!!.toDouble())
        val maximumFrameRate = if (hasTarget && video.isSizeSupported(width!!, height!!)) {
            runCatching { video.getSupportedFrameRatesFor(width!!, height!!).upper }.getOrDefault(0.0)
        } else {
            0.0
        }
        val performanceGuaranteed = if (
            hasTarget && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            val target = MediaCodecInfo.VideoCapabilities.PerformancePoint(width!!, height!!, fps!!)
            video.supportedPerformancePoints?.any { point -> point.covers(target) } == true
        } else {
            false
        }
        val codec = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"
        val engine = if (hardware) "hardware" else "software"
        VideoCandidate(
            choice = EncoderChoice(
                name = info.name,
                label = "$codec · $engine · ${info.name}",
                mime = mime,
                hardwareAccelerated = hardware,
                vendor = vendor,
            ),
            supportsTarget = supportsTarget,
            performanceGuaranteed = performanceGuaranteed,
            maximumFrameRate = maximumFrameRate,
        )
    }.getOrNull()

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        val name = info.name.lowercase()
        return listOf("omx.google.", "c2.android.", ".sw.", "software", "ffmpeg")
            .none { marker -> marker in name }
    }

    fun audioEncoders(): List<EncoderChoice> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it == MediaFormat.MIMETYPE_AUDIO_AAC } }
            .map { info ->
                EncoderChoice(
                    name = info.name,
                    label = "AAC · ${info.name}",
                    mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                )
            }
            .sortedBy { it.name }
            .toList()
    }.getOrDefault(emptyList())
}
