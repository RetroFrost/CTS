package io.github.retrofrost.cts.android.export

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MediaExportEngine(
    private val context: Context,
    private val project: CtsProject,
    private val shouldStop: () -> Boolean = { false },
    private val onProgress: (percent: Int, stage: String, detail: String) -> Unit = { _, _, _ -> },
) {
    fun export(destination: Uri) {
        require(project.cards.isNotEmpty()) { "Add at least one card before exporting." }
        val token = System.currentTimeMillis().toString()
        val videoFile = File(context.cacheDir, "cts-$token-video.mp4")
        val audioFile = File(context.cacheDir, "cts-$token-audio.m4a")
        val muxedFile = File(context.cacheDir, "cts-$token-final.mp4")
        try {
            encodeVideo(videoFile)
            val soundtrack = project.soundtrack.uri?.takeIf { it.isNotBlank() }
            val finalFile = if (soundtrack != null) {
                encodeAudio(audioFile, Uri.parse(soundtrack), TimelineEngine.duration(project))
                mux(videoFile, audioFile, muxedFile)
                muxedFile
            } else {
                videoFile
            }
            checkStopped()
            onProgress(99, "Saving video", "Writing the finished MP4")
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                finalFile.inputStream().use { input -> input.copyTo(output, 1024 * 1024) }
            } ?: error("The selected destination could not be opened for writing.")
            onProgress(100, "Finished", "The MP4 is ready")
        } finally {
            videoFile.delete()
            audioFile.delete()
            muxedFile.delete()
        }
    }

    private fun encodeVideo(output: File) {
        val settings = project.export.normalized()
        val codec = settings.videoEncoderName
            ?.let(MediaCodec::createByCodecName)
            ?: MediaCodec.createEncoderByType(settings.videoMime)
        val capabilities = codec.codecInfo.getCapabilitiesForType(settings.videoMime)
        val colorFormat = selectYuv420Format(capabilities.colorFormats)
            ?: error("${codec.name} does not expose a byte-buffer YUV420 input format.")
        val format = MediaFormat.createVideoFormat(settings.videoMime, settings.width, settings.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, settings.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val renderer = ReferenceFrameRenderer(context, project, settings.width, settings.height)
        val bitmap = Bitmap.createBitmap(settings.width, settings.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(settings.width * settings.height)
        val yuv = ByteArray(settings.width * settings.height * 3 / 2)
        val info = MediaCodec.BufferInfo()
        val duration = TimelineEngine.duration(project).coerceAtLeast(1f)
        val totalFrames = max(1, ceil(duration * settings.fps).toInt())
        var frameIndex = 0
        var inputDone = false
        var outputDone = false
        var muxerStarted = false
        var videoTrack = -1

        try {
            while (!outputDone) {
                checkStopped()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Video encoder returned no input buffer.")
                        input.clear()
                        val presentationTimeUs = frameIndex.toLong() * 1_000_000L / settings.fps
                        if (frameIndex < totalFrames) {
                            renderer.render(bitmap, frameIndex.toFloat() / settings.fps)
                            bitmap.getPixels(pixels, 0, settings.width, 0, 0, settings.width, settings.height)
                            argbToYuv420(pixels, yuv, settings.width, settings.height, colorFormat)
                            require(input.capacity() >= yuv.size) {
                                "The selected encoder input buffer is too small for ${settings.width}x${settings.height}."
                            }
                            input.put(yuv)
                            codec.queueInputBuffer(inputIndex, 0, yuv.size, presentationTimeUs, 0)
                            frameIndex++
                            val percent = (frameIndex * 72 / totalFrames).coerceIn(0, 72)
                            onProgress(
                                percent,
                                "Encoding video",
                                "Frame $frameIndex of $totalFrames · ${settings.width}×${settings.height} ${settings.fps} fps",
                            )
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Video encoder changed format twice." }
                            videoTrack = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outputIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outputIndex)
                                ?: error("Video encoder returned no output buffer.")
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(muxerStarted) { "Video samples arrived before the output format." }
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, encoded, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (outputDone) break
                        }
                    }
                }
            }
        } finally {
            renderer.close()
            bitmap.recycle()
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun encodeAudio(output: File, source: Uri, targetDurationSeconds: Float) {
        onProgress(72, "Encoding soundtrack", "Preparing the selected audio")
        val extractor = MediaExtractor()
        extractor.setDataSource(context, source, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("The selected file does not contain an audio track.")
        extractor.selectTrack(trackIndex)
        val sourceFormat = extractor.getTrackFormat(trackIndex)
        val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME)
            ?: error("The selected audio track has no codec type.")
        val sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        runCatching { sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) }
        val decoder = MediaCodec.createDecoderByType(sourceMime)
        decoder.configure(sourceFormat, null, null, 0)

        val audioSettings = project.export.normalized()
        val encoder = audioSettings.audioEncoderName
            ?.let(MediaCodec::createByCodecName)
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val encoderFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            sampleRate,
            channelCount,
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioSettings.audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        }
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        decoder.start()
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()
        val targetSamples = max(1L, (targetDurationSeconds * sampleRate).toLong())
        val bytesPerFrame = max(1, channelCount * 2)
        val sourceDurationUs = sourceFormat.longOrNull(MediaFormat.KEY_DURATION)?.coerceAtLeast(1L) ?: 1L
        var sourceLoopOffsetUs = 0L
        var lastSourceTimeUs = 0L
        var decoderInputDone = false
        var decoderOutputDone = false
        var encoderInputDone = false
        var encoderOutputDone = false
        var samplesQueued = 0L
        var muxerStarted = false
        var audioTrack = -1

        fun drainEncoder(wait: Boolean) {
            while (!encoderOutputDone) {
                val outputIndex = encoder.dequeueOutputBuffer(encoderInfo, if (wait) 10_000 else 0)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "Audio encoder changed format twice." }
                        audioTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outputIndex)
                            ?: error("Audio encoder returned no output buffer.")
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoderInfo.size = 0
                        if (encoderInfo.size > 0) {
                            check(muxerStarted) { "Audio samples arrived before the output format." }
                            encoded.position(encoderInfo.offset)
                            encoded.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(audioTrack, encoded, encoderInfo)
                        }
                        encoderOutputDone = encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        fun queuePcm(bytes: ByteArray) {
            var byteOffset = 0
            while (byteOffset < bytes.size && samplesQueued < targetSamples) {
                checkStopped()
                drainEncoder(false)
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex < 0) continue
                val input = encoder.getInputBuffer(inputIndex) ?: error("Audio encoder returned no input buffer.")
                input.clear()
                val remainingFrames = targetSamples - samplesQueued
                val maximumBytes = min(input.capacity().toLong(), remainingFrames * bytesPerFrame).toInt()
                var chunk = min(maximumBytes, bytes.size - byteOffset)
                chunk -= chunk % bytesPerFrame
                if (chunk <= 0) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, samplesQueued * 1_000_000L / sampleRate, 0)
                    continue
                }
                input.put(bytes, byteOffset, chunk)
                val pts = samplesQueued * 1_000_000L / sampleRate
                encoder.queueInputBuffer(inputIndex, 0, chunk, pts, 0)
                val frames = chunk / bytesPerFrame
                samplesQueued += frames
                byteOffset += chunk
                val audioProgress = (samplesQueued * 16 / targetSamples).toInt().coerceIn(0, 16)
                onProgress(
                    72 + audioProgress,
                    "Encoding soundtrack",
                    "${samplesQueued * 100 / targetSamples}% · AAC ${audioSettings.audioBitrate / 1000} kbps",
                )
            }
        }

        try {
            while (!encoderInputDone) {
                checkStopped()
                if (!decoderInputDone && samplesQueued < targetSamples) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = decoder.getInputBuffer(inputIndex)
                            ?: error("Audio decoder returned no input buffer.")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            if (project.soundtrack.loop && samplesQueued < targetSamples) {
                                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                sourceLoopOffsetUs += max(sourceDurationUs, lastSourceTimeUs + 1L)
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    sourceLoopOffsetUs + lastSourceTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                decoderInputDone = true
                            }
                        } else {
                            val sourceTime = extractor.sampleTime.coerceAtLeast(0L)
                            lastSourceTimeUs = sourceTime
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                size,
                                sourceLoopOffsetUs + sourceTime,
                                extractor.sampleFlags,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex >= 0 -> {
                        if (decoderInfo.size > 0 && samplesQueued < targetSamples) {
                            val decoded = decoder.getOutputBuffer(outputIndex)
                                ?: error("Audio decoder returned no output buffer.")
                            decoded.position(decoderInfo.offset)
                            decoded.limit(decoderInfo.offset + decoderInfo.size)
                            val pcm = ByteArray(decoderInfo.size)
                            decoded.get(pcm)
                            applyVolume16Bit(pcm, project.soundtrack.volume)
                            queuePcm(pcm)
                        }
                        decoderOutputDone = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }

                if (samplesQueued >= targetSamples || decoderOutputDone) {
                    var inputIndex: Int
                    do {
                        drainEncoder(false)
                        inputIndex = encoder.dequeueInputBuffer(10_000)
                    } while (inputIndex < 0)
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        samplesQueued * 1_000_000L / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    encoderInputDone = true
                }
            }

            while (!encoderOutputDone) {
                checkStopped()
                drainEncoder(true)
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

    private fun mux(video: File, audio: File, output: File) {
        checkStopped()
        onProgress(89, "Finishing MP4", "Combining video and soundtrack")
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val videoIndex = (0 until videoExtractor.trackCount).firstOrNull { index ->
            videoExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("The encoded video has no video track.")
        val audioIndex = (0 until audioExtractor.trackCount).firstOrNull { index ->
            audioExtractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("The encoded soundtrack has no audio track.")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val outputVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoIndex))
            val outputAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioIndex))
            muxer.start()
            copyTrack(videoExtractor, videoIndex, muxer, outputVideo)
            onProgress(95, "Finishing MP4", "Adding the AAC soundtrack")
            copyTrack(audioExtractor, audioIndex, muxer, outputAudio)
            muxer.stop()
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            muxer.release()
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
    ) {
        extractor.selectTrack(inputTrack)
        val format = extractor.getTrackFormat(inputTrack)
        val capacity = format.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(256 * 1024)
            ?: 1024 * 1024
        val buffer = ByteBuffer.allocateDirect(capacity)
        val info = MediaCodec.BufferInfo()
        while (true) {
            checkStopped()
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(inputTrack)
    }

    private fun checkStopped() {
        if (shouldStop()) throw CancellationException("CTS export was canceled.")
    }

    private fun selectYuv420Format(formats: IntArray): Int? {
        val available = formats.toSet()
        return listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        ).firstOrNull { it in available }
    }

    private fun argbToYuv420(
        pixels: IntArray,
        output: ByteArray,
        width: Int,
        height: Int,
        colorFormat: Int,
    ) {
        val frameSize = width * height
        for (index in pixels.indices) {
            val color = pixels[index]
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            output[index] = (((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16)
                .coerceIn(0, 255).toByte()
        }

        var uIndex = frameSize
        var vIndex = frameSize + frameSize / 4
        var uvIndex = frameSize
        val semiPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var row = 0
        while (row < height) {
            var column = 0
            while (column < width) {
                var u = 0
                var v = 0
                var count = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val y = min(height - 1, row + dy)
                        val x = min(width - 1, column + dx)
                        val color = pixels[y * width + x]
                        val red = color shr 16 and 0xff
                        val green = color shr 8 and 0xff
                        val blue = color and 0xff
                        u += (((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128)
                        v += (((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128)
                        count++
                    }
                }
                val averageU = (u / count).coerceIn(0, 255).toByte()
                val averageV = (v / count).coerceIn(0, 255).toByte()
                if (semiPlanar) {
                    output[uvIndex++] = averageU
                    output[uvIndex++] = averageV
                } else {
                    output[uIndex++] = averageU
                    output[vIndex++] = averageV
                }
                column += 2
            }
            row += 2
        }
    }

    private fun applyVolume16Bit(bytes: ByteArray, volume: Float) {
        val gain = volume.coerceIn(0f, 2f)
        if (gain == 1f) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var index = 0
        while (index + 1 < bytes.size) {
            val sample = buffer.getShort(index).toInt()
            buffer.putShort(index, (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            index += 2
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
}
