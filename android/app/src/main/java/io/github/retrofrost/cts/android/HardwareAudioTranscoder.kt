package io.github.retrofrost.cts.android

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

object HardwareAudioTranscoder {
    fun transcode(
        context: Context,
        source: Uri,
        output: File,
        durationUs: Long,
        volume: Float,
        loop: Boolean,
        shouldCancel: () -> Boolean,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, source, null)
        val inputTrack = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("The selected soundtrack has no audio track.")
        extractor.selectTrack(inputTrack)
        val inputFormat = extractor.getTrackFormat(inputTrack)
        val inputMime = requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))
        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(sourceChannels in 1..2) {
            "The selected soundtrack has $sourceChannels channels. Cubical Compare currently supports mono or stereo audio."
        }
        val channels = sourceChannels
        runCatching { inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, 2) }

        val decoder = MediaCodec.createDecoderByType(inputMime)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 262_144)
        }
        decoder.configure(inputFormat, null, null, 0)
        encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        decoder.start()
        encoder.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        var decoderInputEnded = false
        var decoderOutputEnded = false
        var encoderEnded = false
        var encoderEosQueued = false
        var muxerStarted = false
        var outputTrack = -1
        var writtenFrames = 0L
        val targetFrames = (durationUs.coerceAtLeast(1) * sampleRate / 1_000_000L).coerceAtLeast(1)
        val frameBytes = channels * 2
        val safeVolume = volume.coerceIn(0f, 1f)

        fun cancelCheck() {
            if (shouldCancel()) throw CancellationException("Export cancelled")
        }

        fun drainEncoder(wait: Boolean) {
            while (true) {
                val index = encoder.dequeueOutputBuffer(encoderInfo, if (wait) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "AAC encoder format changed twice." }
                        outputTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = requireNotNull(encoder.getOutputBuffer(index))
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoderInfo.size = 0
                        if (encoderInfo.size > 0) {
                            check(muxerStarted) { "AAC data arrived before its output format." }
                            buffer.position(encoderInfo.offset)
                            buffer.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(outputTrack, buffer, encoderInfo)
                        }
                        encoderEnded = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(index, false)
                        if (encoderEnded) return
                    }
                }
            }
        }

        fun queuePcm(data: ByteArray) {
            var offset = 0
            while (offset < data.size && writtenFrames < targetFrames) {
                cancelCheck()
                var inputIndex = encoder.dequeueInputBuffer(10_000)
                while (inputIndex < 0) {
                    drainEncoder(false)
                    inputIndex = encoder.dequeueInputBuffer(10_000)
                }
                val input = requireNotNull(encoder.getInputBuffer(inputIndex)).apply { clear() }
                val remainingBytes = ((targetFrames - writtenFrames) * frameBytes)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val chunk = minOf(input.capacity(), data.size - offset, remainingBytes)
                    .let { it - it % frameBytes }
                if (chunk <= 0) break
                input.put(data, offset, chunk)
                val frames = chunk / frameBytes
                val pts = writtenFrames * 1_000_000L / sampleRate
                encoder.queueInputBuffer(inputIndex, 0, chunk, pts, 0)
                writtenFrames += frames
                offset += chunk
                drainEncoder(false)
            }
        }

        try {
            while (!encoderEnded) {
                cancelCheck()

                if (!decoderInputEnded && writtenFrames < targetFrames) {
                    val index = decoder.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(index)).apply { clear() }
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            if (loop) {
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                val restartedSize = extractor.readSampleData(input, 0)
                                if (restartedSize < 0) {
                                    decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    decoderInputEnded = true
                                } else {
                                    decoder.queueInputBuffer(index, 0, restartedSize, 0, 0)
                                    extractor.advance()
                                }
                            } else {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderInputEnded = true
                            }
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderOutputEnded && writtenFrames < targetFrames) {
                    val index = decoder.dequeueOutputBuffer(decoderInfo, 10_000)
                    if (index >= 0) {
                        val pcm = requireNotNull(decoder.getOutputBuffer(index))
                        val bytes = ByteArray(decoderInfo.size)
                        pcm.position(decoderInfo.offset)
                        pcm.limit(decoderInfo.offset + decoderInfo.size)
                        pcm.get(bytes)
                        if (safeVolume < 0.999f) scalePcm16(bytes, safeVolume)
                        queuePcm(bytes)
                        decoderOutputEnded = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false)
                    }
                }

                if ((decoderOutputEnded || writtenFrames >= targetFrames) && !encoderEosQueued) {
                    if (writtenFrames < targetFrames) {
                        val silenceFrames = minOf(targetFrames - writtenFrames, 4096).toInt()
                        queuePcm(ByteArray(silenceFrames * frameBytes))
                    } else {
                        var index = encoder.dequeueInputBuffer(10_000)
                        while (index < 0) {
                            drainEncoder(false)
                            index = encoder.dequeueInputBuffer(10_000)
                        }
                        val pts = writtenFrames * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encoderEosQueued = true
                    }
                }
                drainEncoder(encoderEosQueued)
            }
        } finally {
            extractor.release()
            runCatching { decoder.stop() }
            decoder.release()
            runCatching { encoder.stop() }
            encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun scalePcm16(bytes: ByteArray, volume: Float) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 2) {
            val position = buffer.position()
            val sample = buffer.short.toInt()
            val scaled = (sample * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer.putShort(position, scaled.toShort())
        }
    }
}
