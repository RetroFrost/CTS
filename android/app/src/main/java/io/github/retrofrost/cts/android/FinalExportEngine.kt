package io.github.retrofrost.cts.android

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

class FinalExportEngine(
    private val context: Context,
    private val project: StudioProject,
    private val shouldStop: () -> Boolean,
    private val onProgress: (Int, String, String) -> Unit,
) {
    fun export(destination: Uri) {
        require(project.cards.isNotEmpty()) { "Add at least one card before exporting." }
        val token = System.nanoTime()
        val video = File(context.cacheDir, "final-$token-video.mp4")
        val audio = File(context.cacheDir, "final-$token-audio.m4a")
        val joined = File(context.cacheDir, "final-$token.mp4")
        try {
            encodeVideo(video)
            val final = if (project.soundtrack.isNotBlank()) {
                encodeAudio(audio, Uri.parse(project.soundtrack)); mux(video, audio, joined); joined
            } else video
            checkStop()
            onProgress(98, "Saving", "Writing the finished MP4")
            context.contentResolver.openOutputStream(destination, "w")?.use { output ->
                final.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        checkStop(); val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read)
                    }
                }
            } ?: error("Could not open the selected output file.")
            onProgress(100, "Finished", "The MP4 is ready")
        } finally { video.delete(); audio.delete(); joined.delete() }
    }

    private data class VideoEncoder(val codec: MediaCodec, val mime: String, val color: Int)

    private fun chooseVideoEncoder(width: Int, height: Int, fps: Int): VideoEncoder {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
            .sortedByDescending { info -> Build.VERSION.SDK_INT >= 29 && info.isHardwareAccelerated }
        val preferred = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        for (info in candidates) {
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val color = preferred.firstOrNull { it in caps.colorFormats } ?: continue
            if (!runCatching { caps.videoCapabilities.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)) continue
            return VideoEncoder(MediaCodec.createByCodecName(info.name), mime, color)
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val caps = codec.codecInfo.getCapabilitiesForType(mime)
        val color = preferred.firstOrNull { it in caps.colorFormats } ?: error("No YUV420 input format is available on this device.")
        return VideoEncoder(codec, mime, color)
    }

    private fun encodeVideo(output: File) {
        val width = project.width.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val height = project.height.coerceAtLeast(2).let { if (it % 2 == 0) it else it - 1 }
        val fps = project.fps.coerceIn(1, 120)
        val selected = chooseVideoEncoder(width, height, fps)
        val codec = selected.codec
        val capabilities = codec.codecInfo.getCapabilitiesForType(selected.mime)
        val bitrate = (width.toLong() * height * fps * 0.11).toLong().coerceIn(4_000_000, 45_000_000).toInt()
        val format = MediaFormat.createVideoFormat(selected.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, selected.color)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (capabilities.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        onProgress(0, "Preparing encoder", "${codec.name} · H.264 · fast exact-render bridge")
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        val expectedYuvSize = width * height * 3 / 2
        val semiPlanar = selected.color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        var frame = 0
        var inputDone = false
        var outputDone = false
        var started = false
        var track = -1
        var lastProgressNanos = 0L
        val renderStartedNanos = System.nanoTime()
        try {
            val metadata = SharedRenderer.beginVideoExport(project)
            val totalFrames = metadata.frameCount.coerceAtLeast(1)
            while (!outputDone) {
                checkStop()
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = codec.getInputBuffer(inputIndex) ?: error("Encoder returned no input buffer.")
                        input.clear()
                        val pts = frame.toLong() * 1_000_000L / fps
                        if (frame < totalFrames) {
                            val yuv = SharedRenderer.renderYuv420(frame, width, height, semiPlanar)
                            require(yuv.size == expectedYuvSize) { "Renderer returned an invalid YUV420 frame." }
                            require(input.capacity() >= yuv.size) { "Encoder input buffer is too small." }
                            input.put(yuv)
                            codec.queueInputBuffer(inputIndex, 0, yuv.size, pts, 0)
                            frame++

                            val now = System.nanoTime()
                            if (
                                frame == 1 || frame == totalFrames ||
                                now - lastProgressNanos >= 750_000_000L
                            ) {
                                onProgress(
                                    (frame * 82 / totalFrames).coerceIn(0, 82),
                                    "Rendering + encoding",
                                    videoProgressDetail(frame, totalFrames, width, height, fps, renderStartedNanos, now),
                                )
                                lastProgressNanos = now
                            }
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, if (inputDone) 10_000 else 0)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!started)
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        outputIndex >= 0 -> {
                            val data = codec.getOutputBuffer(outputIndex) ?: error("Encoder returned no output buffer.")
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(started)
                                data.position(info.offset)
                                data.limit(info.offset + info.size)
                                muxer.writeSampleData(track, data, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (outputDone) break
                        }
                    }
                }
            }
        } finally {
            SharedRenderer.endVideoExport()
            runCatching { codec.stop() }
            codec.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun encodeAudio(output: File, uri: Uri) {
        onProgress(83, "Soundtrack", "Encoding AAC soundtrack")
        val extractor = MediaExtractor(); extractor.setDataSource(context, uri, null)
        val sourceTrack = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("The soundtrack has no audio track.")
        extractor.selectTrack(sourceTrack)
        val sourceFormat = extractor.getTrackFormat(sourceTrack)
        val sourceMime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("Unknown soundtrack codec.")
        val sampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); val channels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        runCatching { sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) }
        val decoder = MediaCodec.createDecoderByType(sourceMime).apply { configure(sourceFormat, null, null, 0); start() }
        val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC); setInteger(MediaFormat.KEY_BIT_RATE, 192_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val dInfo = MediaCodec.BufferInfo(); val eInfo = MediaCodec.BufferInfo()
        val durationSamples = (SharedRenderer.metadata(project).duration * sampleRate).toLong().coerceAtLeast(1)
        val bytesPerFrame = max(1, channels * 2)
        var samples = 0L; var decoderEos = false; var encoderEos = false; var started = false; var track = -1

        fun drainEncoder(wait: Boolean) {
            while (true) {
                val out = encoder.dequeueOutputBuffer(eInfo, if (wait) 10_000 else 0)
                when {
                    out == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start(); started = true }
                    out >= 0 -> {
                        val data = encoder.getOutputBuffer(out)!!
                        if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                        if (eInfo.size > 0) { data.position(eInfo.offset); data.limit(eInfo.offset + eInfo.size); muxer.writeSampleData(track, data, eInfo) }
                        val eos = eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(out, false); if (eos) { encoderEos = true; return }
                    }
                }
            }
        }

        fun queuePcm(bytes: ByteArray) {
            var offset = 0; applyVolume(bytes, project.soundtrackVolume)
            while (offset < bytes.size && samples < durationSamples) {
                checkStop(); drainEncoder(false)
                val idx = encoder.dequeueInputBuffer(10_000); if (idx < 0) continue
                val input = encoder.getInputBuffer(idx)!!; input.clear()
                val remaining = ((durationSamples - samples) * bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                var count = min(min(input.capacity(), bytes.size - offset), remaining); count -= count % bytesPerFrame
                if (count <= 0) { encoder.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / sampleRate, 0); continue }
                input.put(bytes, offset, count); encoder.queueInputBuffer(idx, 0, count, samples * 1_000_000L / sampleRate, 0)
                samples += count / bytesPerFrame; offset += count
            }
        }

        try {
            while (samples < durationSamples) {
                checkStop()
                if (!decoderEos) {
                    val idx = decoder.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val input = decoder.getInputBuffer(idx)!!; input.clear(); val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            if (project.soundtrackLoop) extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            else { decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); decoderEos = true }
                        } else { decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime.coerceAtLeast(0), extractor.sampleFlags); extractor.advance() }
                    }
                }
                val out = decoder.dequeueOutputBuffer(dInfo, 10_000)
                if (out >= 0) {
                    if (dInfo.size > 0) { val data = decoder.getOutputBuffer(out)!!; data.position(dInfo.offset); data.limit(dInfo.offset + dInfo.size); val pcm = ByteArray(dInfo.size); data.get(pcm); queuePcm(pcm) }
                    val eos = dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0; decoder.releaseOutputBuffer(out, false); if (eos && !project.soundtrackLoop) break
                }
                onProgress(83 + (samples * 10 / durationSamples).toInt().coerceIn(0, 10), "Soundtrack", "${samples * 100 / durationSamples}%")
            }
            var idx: Int
            do { drainEncoder(false); idx = encoder.dequeueInputBuffer(10_000) } while (idx < 0)
            encoder.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            while (!encoderEos) { checkStop(); drainEncoder(true) }
        } finally {
            extractor.release(); runCatching { decoder.stop() }; decoder.release(); runCatching { encoder.stop() }; encoder.release(); if (started) runCatching { muxer.stop() }; muxer.release()
        }
    }

    private fun mux(video: File, audio: File, output: File) {
        checkStop(); onProgress(94, "Finalizing", "Muxing video and soundtrack")
        val vx = MediaExtractor().apply { setDataSource(video.absolutePath) }; val ax = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val vi = (0 until vx.trackCount).first { vx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val ai = (0 until ax.trackCount).first { ax.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try { val vo = muxer.addTrack(vx.getTrackFormat(vi)); val ao = muxer.addTrack(ax.getTrackFormat(ai)); muxer.start(); copyTrack(vx, vi, muxer, vo); onProgress(96, "Finalizing", "Adding AAC audio"); copyTrack(ax, ai, muxer, ao); muxer.stop() }
        finally { vx.release(); ax.release(); muxer.release() }
    }

    private fun copyTrack(extractor: MediaExtractor, index: Int, muxer: MediaMuxer, output: Int) {
        extractor.selectTrack(index); val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024); val info = MediaCodec.BufferInfo()
        while (true) { checkStop(); buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break; info.set(0, size, extractor.sampleTime.coerceAtLeast(0), extractor.sampleFlags); muxer.writeSampleData(output, buffer, info); extractor.advance() }
    }

    private fun videoProgressDetail(
        frame: Int,
        totalFrames: Int,
        width: Int,
        height: Int,
        outputFps: Int,
        startedNanos: Long,
        nowNanos: Long,
    ): String {
        val elapsedMillis = max(1L, (nowNanos - startedNanos) / 1_000_000L)
        val fpsTenths = frame.toLong() * 10_000L / elapsedMillis
        val remainingFrames = (totalFrames - frame).coerceAtLeast(0).toLong()
        val etaSeconds = if (frame > 0) remainingFrames * elapsedMillis / frame / 1_000L else 0L
        val eta = when {
            etaSeconds >= 3_600L -> "${etaSeconds / 3_600L}h ${(etaSeconds % 3_600L) / 60L}m"
            etaSeconds >= 60L -> "${etaSeconds / 60L}m ${etaSeconds % 60L}s"
            else -> "${etaSeconds}s"
        }
        return "Frame $frame / $totalFrames · $width×$height @ $outputFps output · render ${fpsTenths / 10}.${fpsTenths % 10} fps · ETA $eta"
    }

    private fun applyVolume(bytes: ByteArray, volume: Float) {
        val gain = volume.coerceIn(0f, 1f); if (gain == 1f) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); var i=0
        while(i+1<bytes.size){ val sample=buffer.getShort(i).toInt(); buffer.putShort(i,(sample*gain).toInt().coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt()).toShort()); i+=2 }
    }
    private fun checkStop() { if (shouldStop()) throw CancellationException("Export canceled") }
}
