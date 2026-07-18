package io.github.retrofrost.cts.android.export

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import io.github.retrofrost.cts.android.model.CtsSoundtrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min

internal object CtsAudioTranscoder {
    private const val TIMEOUT_US = 10_000L
    private const val AAC_BIT_RATE_STEREO = 192_000
    private const val AAC_BIT_RATE_MONO = 128_000
    private const val MAX_PCM_BYTES = 512L * 1024L * 1024L

    private data class DecodedPcm(
        val file: File,
        val sampleRate: Int,
        val channelCount: Int,
    )

    suspend fun transcodeToAac(
        context: Context,
        soundtrack: CtsSoundtrack,
        durationUs: Long,
        outputFile: File,
        onProgress: suspend (Float) -> Unit = {},
    ) = withContext(Dispatchers.Default) {
        val pcmFile = File.createTempFile("cts-audio-", ".pcm", context.cacheDir)
        try {
            val decoded = decodeToPcm(context, soundtrack.source, pcmFile)
            encodePcmToAac(
                decoded = decoded,
                outputFile = outputFile,
                durationUs = durationUs,
                volume = soundtrack.volume,
                loop = soundtrack.loop,
                onProgress = onProgress,
            )
        } finally {
            pcmFile.delete()
        }
    }

    private suspend fun decodeToPcm(
        context: Context,
        source: String,
        pcmFile: File,
    ): DecodedPcm {
        val extractor = MediaExtractor()
        setDataSource(extractor, context, source)
        val trackIndex = findAudioTrack(extractor)
        require(trackIndex >= 0) { "The selected file has no audio track." }
        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)
            ?: error("The selected audio format has no MIME type.")

        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(channelCount in 1..2) {
            "CTS currently supports mono or stereo soundtracks."
        }

        val decoder = MediaCodec.createDecoderByType(mime)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            FileOutputStream(pcmFile).use { output ->
                while (!outputDone) {
                    coroutineContext.ensureActive()

                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                                ?: error("Audio decoder returned no input buffer.")
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime.coerceAtLeast(0L),
                                    extractor.sampleFlags,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = decoder.outputFormat
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            require(channelCount in 1..2) {
                                "CTS currently supports mono or stereo soundtracks."
                            }
                            if (Build.VERSION.SDK_INT >= 24 &&
                                outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ) {
                                pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                ?: error("Audio decoder returned no output buffer.")
                            if (info.size > 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                val bytes = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_16BIT -> {
                                        ByteArray(info.size).also(outputBuffer::get)
                                    }

                                    AudioFormat.ENCODING_PCM_FLOAT -> {
                                        floatPcmTo16Bit(outputBuffer)
                                    }

                                    else -> error(
                                        "Unsupported decoded PCM format: $pcmEncoding",
                                    )
                                }
                                check(pcmFile.length() + bytes.size <= MAX_PCM_BYTES) {
                                    "The selected soundtrack is too large for this alpha exporter."
                                }
                                output.write(bytes)
                            }
                            outputDone =
                                (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            extractor.release()
        }

        require(pcmFile.length() > 0L) { "The selected soundtrack decoded to no audio." }
        return DecodedPcm(pcmFile, sampleRate, channelCount)
    }

    private suspend fun encodePcmToAac(
        decoded: DecodedPcm,
        outputFile: File,
        durationUs: Long,
        volume: Float,
        loop: Boolean,
        onProgress: suspend (Float) -> Unit,
    ) {
        val sampleRate = decoded.sampleRate
        val channels = decoded.channelCount
        val bytesPerFrame = channels * 2
        val targetSamples = (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1L)
        val encoderFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channels,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC,
            )
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                if (channels == 1) AAC_BIT_RATE_MONO else AAC_BIT_RATE_STEREO,
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var outputTrack = -1
        var inputDone = false
        var outputDone = false
        var samplesSubmitted = 0L
        var lastProgress = -1

        try {
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            RandomAccessFile(decoded.file, "r").use { pcm ->
                while (!outputDone) {
                    coroutineContext.ensureActive()

                    if (!inputDone) {
                        val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputIndex)
                                ?: error("AAC encoder returned no input buffer.")
                            inputBuffer.clear()

                            val remainingSamples = targetSamples - samplesSubmitted
                            if (remainingSamples <= 0L) {
                                encoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    durationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                val wantedBytes = min(
                                    inputBuffer.remaining().toLong(),
                                    remainingSamples * bytesPerFrame,
                                ).toInt() / bytesPerFrame * bytesPerFrame
                                val pcmBytes = readPcm(
                                    file = pcm,
                                    byteCount = wantedBytes,
                                    loop = loop,
                                )
                                if (pcmBytes.isEmpty()) {
                                    encoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        samplesSubmitted * 1_000_000L / sampleRate,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    inputDone = true
                                } else {
                                    applyVolume(pcmBytes, volume)
                                    inputBuffer.put(pcmBytes)
                                    val sampleCount = pcmBytes.size / bytesPerFrame
                                    val presentationTimeUs =
                                        samplesSubmitted * 1_000_000L / sampleRate
                                    encoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        pcmBytes.size,
                                        presentationTimeUs,
                                        0,
                                    )
                                    samplesSubmitted += sampleCount
                                    val percent =
                                        ((samplesSubmitted * 100L) / targetSamples).toInt()
                                    if (percent != lastProgress) {
                                        lastProgress = percent
                                        onProgress(
                                            samplesSubmitted.toFloat() /
                                                targetSamples.toFloat(),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    when (val outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed twice." }
                            outputTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }

                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        else -> if (outputIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                                ?: error("AAC encoder returned no output buffer.")
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                info.size = 0
                            }
                            if (info.size > 0) {
                                check(muxerStarted) { "AAC muxer has not started." }
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                muxer.writeSampleData(outputTrack, outputBuffer, info)
                            }
                            outputDone =
                                (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun readPcm(
        file: RandomAccessFile,
        byteCount: Int,
        loop: Boolean,
    ): ByteArray {
        if (byteCount <= 0) return ByteArray(0)
        val result = ByteArray(byteCount)
        var filled = 0
        while (filled < byteCount) {
            val read = file.read(result, filled, byteCount - filled)
            if (read > 0) {
                filled += read
                continue
            }
            if (!loop || file.length() <= 0L) break
            file.seek(0L)
        }
        return if (filled == result.size) result else result.copyOf(filled)
    }

    private fun applyVolume(bytes: ByteArray, volume: Float) {
        val gain = volume.coerceIn(0f, 1f)
        if (gain >= 0.999f) return
        var index = 0
        while (index + 1 < bytes.size) {
            val raw = (bytes[index].toInt() and 0xFF) or
                (bytes[index + 1].toInt() shl 8)
            val sample = raw.toShort().toInt()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[index] = (scaled and 0xFF).toByte()
            bytes[index + 1] = ((scaled shr 8) and 0xFF).toByte()
            index += 2
        }
    }

    private fun floatPcmTo16Bit(buffer: ByteBuffer): ByteArray {
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val output = ByteBuffer
            .allocate(floats.remaining() * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        while (floats.hasRemaining()) {
            val sample = floats.get().coerceIn(-1f, 1f)
            output.putShort((sample * Short.MAX_VALUE).toInt().toShort())
        }
        return output.array()
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun setDataSource(
        extractor: MediaExtractor,
        context: Context,
        source: String,
    ) {
        val uri = Uri.parse(source)
        when (uri.scheme?.lowercase()) {
            "content", "android.resource", "file" -> {
                extractor.setDataSource(context, uri, null)
            }
            "http", "https" -> extractor.setDataSource(source)
            null -> extractor.setDataSource(source)
            else -> extractor.setDataSource(context, uri, null)
        }
    }
}
