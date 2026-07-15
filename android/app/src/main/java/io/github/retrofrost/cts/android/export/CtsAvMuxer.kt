package io.github.retrofrost.cts.android.export

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

internal object CtsAvMuxer {
    fun combine(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        durationUs: Long,
    ) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoFile.absolutePath)
        audioExtractor.setDataSource(audioFile.absolutePath)

        val videoSourceTrack = findTrack(videoExtractor, "video/")
        val audioSourceTrack = findTrack(audioExtractor, "audio/")
        require(videoSourceTrack >= 0) { "Encoded CTS video has no video track." }
        require(audioSourceTrack >= 0) { "Encoded soundtrack has no audio track." }

        videoExtractor.selectTrack(videoSourceTrack)
        audioExtractor.selectTrack(audioSourceTrack)

        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
        var started = false
        try {
            val videoOutputTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack))
            val audioOutputTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
            muxer.start()
            started = true

            copyTrack(
                extractor = videoExtractor,
                muxer = muxer,
                outputTrack = videoOutputTrack,
                stopAtUs = Long.MAX_VALUE,
            )
            copyTrack(
                extractor = audioExtractor,
                muxer = muxer,
                outputTrack = audioOutputTrack,
                stopAtUs = durationUs,
            )
        } finally {
            videoExtractor.release()
            audioExtractor.release()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        stopAtUs: Long,
    ) {
        val format = extractor.getTrackFormat(extractor.sampleTrackIndex)
        val requestedSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            0
        }
        val buffer = ByteBuffer.allocateDirect(max(1_048_576, requestedSize))
        val info = MediaCodec.BufferInfo()

        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0 || sampleTime > stopAtUs) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = sampleTime
            info.flags = extractor.sampleFlags
            buffer.position(0)
            buffer.limit(size)
            muxer.writeSampleData(outputTrack, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(prefix) == true) return index
        }
        return -1
    }
}
