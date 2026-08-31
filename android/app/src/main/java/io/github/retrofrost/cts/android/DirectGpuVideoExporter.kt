package io.github.retrofrost.cts.android

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/**
 * Frame-bitmap-free export path.
 *
 * The renderer records directly into the MediaCodec input Surface through a
 * hardware-accelerated Canvas. There is no full-frame Bitmap -> GL texture
 * upload between the renderer and the video encoder.
 */
class DirectGpuVideoExporter(
    private val context: android.content.Context,
    sourceProject: StudioProject,
    private val rendererSpec: RendererSpec,
    private val shouldCancel: () -> Boolean,
    private val onProgress: (Int, String, String) -> Unit,
) {
    private val project = RendererBridge.resolveOutputProject(sourceProject, rendererSpec)
    private val directRenderer = DirectCanvasFrameRenderer()

    fun export(destination: Uri) {
        require(project.cards.isNotEmpty()) { "Add at least one card before exporting." }

        // Keep custom-intro support intact until the zero-copy decoder hand-off is
        // available on every vendor codec. Normal renderer exports never take this path.
        if (project.introMode == IntroMode.CUSTOM && project.introVideo.isNotBlank()) {
            onProgress(0, "Preparing", "Custom intro compatibility path")
            HardwareVideoExporter(
                context = context,
                sourceProject = project,
                rendererSpec = rendererSpec,
                shouldCancel = shouldCancel,
                onProgress = onProgress,
            ).export(destination)
            return
        }

        val marker = System.nanoTime()
        val video = File(context.cacheDir, "cc-$marker-video.mp4")
        val audio = File(context.cacheDir, "cc-$marker-audio.m4a")
        val final = File(context.cacheDir, "cc-$marker-final.mp4")
        try {
            val metadata = RendererBridge.metadata(project, rendererSpec)
            val selected = encodeVideo(video, metadata)
            val completed = if (project.soundtrack.isNotBlank() && project.soundtrackVolume > 0f) {
                checkCancelled()
                onProgress(84, "Soundtrack", "Hardware AAC encoding")
                HardwareAudioTranscoder.transcode(
                    context = context,
                    source = Uri.parse(project.soundtrack),
                    output = audio,
                    durationUs = (metadata.duration * 1_000_000.0).toLong(),
                    volume = project.soundtrackVolume,
                    loop = project.soundtrackLoop,
                    shouldCancel = shouldCancel,
                )
                onProgress(94, "Muxing", "${selected.label} + AAC")
                mux(video, audio, final)
                final
            } else {
                video
            }
            checkCancelled()
            onProgress(98, "Saving", "Writing the finished MP4")
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                requireNotNull(output) { "Could not open the selected output file." }
                FileInputStream(completed).use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            onProgress(100, "Finished", "MP4 saved • direct GPU • ${selected.label}")
        } finally {
            video.delete()
            audio.delete()
            final.delete()
        }
    }

    private fun encodeVideo(output: File, metadata: RenderMetadata): SelectedVideoCodec {
        val width = project.width.coerceAtLeast(2).let { it - it % 2 }
        val height = project.height.coerceAtLeast(2).let { it - it % 2 }
        val fps = project.fps.coerceIn(1, 120)
        val selected = HardwareCodecSelector.select(project.encoderPreference, width, height, fps)
        val pixelsPerSecond = width.toLong() * height.toLong() * fps.toLong()
        val bitrate = if (selected.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            (pixelsPerSecond * 0.075).toLong().coerceIn(3_000_000L, 32_000_000L).toInt()
        } else {
            (pixelsPerSecond * 0.11).toLong().coerceIn(4_000_000L, 45_000_000L).toInt()
        }
        val format = MediaFormat.createVideoFormat(selected.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (RelationshipsPrecisionFrameRenderer.enabled(rendererSpec) && Build.VERSION.SDK_INT >= 24) {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            selected.bitrateMode?.let { setInteger(MediaFormat.KEY_BITRATE_MODE, it) }
        }

        val codec = MediaCodec.createByCodecName(selected.name)
        var inputSurface: Surface? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var writtenFrames = 0L
            val totalFrames = metadata.frameCount.coerceAtLeast(1)
            val started = System.nanoTime()
            onProgress(0, "Preparing GPU", "Direct hardware Canvas • ${selected.label}")

            repeat(totalFrames) { frame ->
                checkCancelled()
                val surface = requireNotNull(inputSurface)
                val canvas = try {
                    surface.lockHardwareCanvas()
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "This device's ${selected.label} input surface cannot accept direct GPU Canvas frames.",
                        error,
                    )
                }
                try {
                    directRenderer.drawTimeline(
                        canvas = canvas,
                        project = project,
                        spec = rendererSpec,
                        frame = frame,
                        outputWidth = width,
                        outputHeight = height,
                    )
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }

                val drain = drainCodec(
                    codec = codec,
                    muxer = muxer,
                    info = info,
                    wait = false,
                    currentTrack = track,
                    alreadyStarted = muxerStarted,
                    writtenFrames = writtenFrames,
                    fps = fps,
                )
                track = drain.track
                muxerStarted = drain.started
                writtenFrames = drain.writtenFrames

                if (frame == 0 || frame + 1 == totalFrames || frame % fps == 0) {
                    val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
                    val renderedFps = (frame + 1) * 1_000_000_000.0 / elapsed
                    val remaining = ((totalFrames - frame - 1) / renderedFps.coerceAtLeast(0.01)).toInt()
                    onProgress(
                        ((frame + 1) * 82 / totalFrames).coerceIn(0, 82),
                        "Direct GPU rendering + encoding",
                        "${frame + 1} / $totalFrames • ${"%.1f".format(renderedFps)} fps • ${remaining}s left",
                    )
                }
            }

            codec.signalEndOfInputStream()
            var ended = false
            while (!ended) {
                checkCancelled()
                val drain = drainCodec(
                    codec = codec,
                    muxer = muxer,
                    info = info,
                    wait = true,
                    currentTrack = track,
                    alreadyStarted = muxerStarted,
                    writtenFrames = writtenFrames,
                    fps = fps,
                )
                track = drain.track
                muxerStarted = drain.started
                writtenFrames = drain.writtenFrames
                ended = drain.ended
            }
            return selected
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            inputSurface?.release()
        }
    }

    private data class DrainResult(
        val track: Int,
        val started: Boolean,
        val ended: Boolean,
        val writtenFrames: Long,
    )

    private fun drainCodec(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        wait: Boolean,
        currentTrack: Int,
        alreadyStarted: Boolean,
        writtenFrames: Long,
        fps: Int,
    ): DrainResult {
        var track = currentTrack
        var started = alreadyStarted
        var ended = false
        var frameCounter = writtenFrames
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return DrainResult(track, started, ended, frameCounter)
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started) { "Video encoder format changed twice." }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                index >= 0 -> {
                    val buffer = requireNotNull(codec.getOutputBuffer(index))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        check(started) { "Video encoder produced data before its format." }
                        // Canvas-posted encoder surfaces do not expose eglPresentationTimeANDROID.
                        // Re-stamp access units in render order so the MP4 keeps the exact project
                        // cadence while frames can still be produced faster than real time.
                        info.presentationTimeUs = frameCounter * 1_000_000L / fps.coerceAtLeast(1)
                        frameCounter += 1
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (ended) return DrainResult(track, started, true, frameCounter)
                }
            }
        }
    }

    private fun mux(video: File, audio: File, output: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoIndex = (0 until videoExtractor.trackCount).first {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioIndex = (0 until audioExtractor.trackCount).first {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            videoExtractor.selectTrack(videoIndex)
            audioExtractor.selectTrack(audioIndex)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoIndex))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioIndex))
            muxer.start()
            copyTrack(videoExtractor, muxer, outVideo)
            copyTrack(audioExtractor, muxer, outAudio)
            muxer.stop()
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            muxer.release()
        }
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocateDirect(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            checkCancelled()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            info.set(0, size, extractor.sampleTime.coerceAtLeast(0), flags)
            muxer.writeSampleData(track, buffer, info)
            extractor.advance()
        }
    }

    private fun checkCancelled() {
        if (shouldCancel()) throw CancellationException("Export cancelled")
    }
}

/** Draws the existing native engines into a caller-owned hardware Canvas. */
private class DirectCanvasFrameRenderer {
    private val bridgeClass = RendererBridge::class.java
    private val nativeRenderer = bridgeField("nativeRenderer")
    private val ribbonRenderer = bridgeField("ribbonRenderer")
    private val relationshipsRenderer = bridgeField("relationshipsRenderer")
    private val relationshipsPrecisionRenderer = bridgeField("relationshipsPrecisionRenderer")

    private val fourArgDrawMethods = mutableMapOf<Class<*>, Method>()
    private var precisionDrawMethod: Method? = null
    private var precisionConfigMethod: Method? = null

    fun drawTimeline(
        canvas: Canvas,
        project: StudioProject,
        spec: RendererSpec,
        frame: Int,
        outputWidth: Int,
        outputHeight: Int,
    ) = synchronized(RendererBridge) {
        val previous = RendererRuntime.active
        try {
            RendererRuntime.active = spec
            val safeFrame = frame.coerceAtLeast(0)
            val engineFrame = when (project.introMode) {
                IntroMode.RENDERER -> safeFrame
                IntroMode.DISABLED -> safeFrame + RendererBridge.rendererIntroFrames(spec)
                IntroMode.CUSTOM -> error("Custom intro frames are handled before direct renderer frames.")
            }

            canvas.save()
            canvas.scale(
                outputWidth.coerceAtLeast(2) / 1920f,
                outputHeight.coerceAtLeast(2) / 1080f,
            )
            when {
                RelationshipsTimeline.isRelationships(spec) && RelationshipsPrecisionFrameRenderer.enabled(spec) ->
                    drawPrecision(canvas, project, engineFrame, spec)
                RelationshipsTimeline.isRelationships(spec) ->
                    drawFourArg(relationshipsRenderer, canvas, project, engineFrame, spec)
                RibbonTimeline.isRibbon(spec) ->
                    drawFourArg(ribbonRenderer, canvas, project, engineFrame, spec)
                else ->
                    drawFourArg(nativeRenderer, canvas, project, engineFrame, spec)
            }
            canvas.restore()
        } finally {
            RendererRuntime.active = previous
        }
    }

    private fun drawFourArg(
        renderer: Any,
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
    ) {
        val method = fourArgDrawMethods.getOrPut(renderer.javaClass) {
            renderer.javaClass.declaredMethods.firstOrNull {
                it.name == "drawReference" && it.parameterTypes.size == 4
            }?.apply { isAccessible = true }
                ?: error("${renderer.javaClass.simpleName} has no direct Canvas entry point.")
        }
        method.invoke(renderer, canvas, project, frame, spec)
    }

    private fun drawPrecision(
        canvas: Canvas,
        project: StudioProject,
        frame: Int,
        spec: RendererSpec,
    ) {
        val renderer = relationshipsPrecisionRenderer
        val configMethod = precisionConfigMethod ?: renderer.javaClass.declaredMethods.firstOrNull {
            it.name == "exactConfig" && it.parameterTypes.size == 1
        }?.apply { isAccessible = true }?.also { precisionConfigMethod = it }
            ?: error("Relationships precision renderer has no exact-config entry point.")
        val config = configMethod.invoke(renderer, spec)
        val drawMethod = precisionDrawMethod ?: renderer.javaClass.declaredMethods.firstOrNull {
            it.name == "drawReference" && it.parameterTypes.size == 6
        }?.apply { isAccessible = true }?.also { precisionDrawMethod = it }
            ?: error("Relationships precision renderer has no direct Canvas entry point.")
        drawMethod.invoke(renderer, canvas, project, frame, spec, config, RenderPassLedger())
    }

    private fun bridgeField(name: String): Any {
        val field = bridgeClass.getDeclaredField(name).apply { isAccessible = true }
        return requireNotNull(field.get(RendererBridge)) { "Renderer bridge field '$name' is unavailable." }
    }
}
