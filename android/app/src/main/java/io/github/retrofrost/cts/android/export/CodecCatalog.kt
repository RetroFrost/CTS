package io.github.retrofrost.cts.android.export

import android.media.MediaCodecList
import android.media.MediaFormat

data class EncoderChoice(
    val name: String,
    val label: String,
    val mime: String,
)

object CodecCatalog {
    fun videoEncoders(): List<EncoderChoice> = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes.asSequence()
                    .filter { it == MediaFormat.MIMETYPE_VIDEO_AVC || it == MediaFormat.MIMETYPE_VIDEO_HEVC }
                    .map { mime ->
                        EncoderChoice(
                            name = info.name,
                            label = "${if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"} · ${info.name}",
                            mime = mime,
                        )
                    }
            }
            .sortedWith(compareBy<EncoderChoice> { it.mime != MediaFormat.MIMETYPE_VIDEO_AVC }.thenBy { it.name })
            .toList()
    }.getOrDefault(emptyList())

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
