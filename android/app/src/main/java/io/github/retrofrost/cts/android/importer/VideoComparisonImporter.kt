package io.github.retrofrost.cts.android.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.retrofrost.cts.android.layout.CardContentLayout
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.VisualModel
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine

data class ReconstructedCard(
    val id: String = UUID.randomUUID().toString(),
    val badgePrimary: String = "",
    val badgeSecondary: String = "",
    val title: String = "",
    val description: String = "",
    val artworkPath: String,
    val sourceTimeSeconds: Float,
    val confidence: Float,
    val warnings: List<String> = emptyList(),
)

data class VideoReconstructionResult(
    val sourceName: String,
    val durationSeconds: Float,
    val detectedModel: VisualModel,
    val cards: List<ReconstructedCard>,
    val warnings: List<String> = emptyList(),
)

enum class VideoReconstructionPhase(val label: String) {
    Reading("Reading video"),
    FindingCards("Finding cards"),
    ReadingText("Reading text"),
    SavingArtwork("Saving artwork"),
}

data class VideoReconstructionProgress(
    val phase: VideoReconstructionPhase,
    val completed: Int,
    val total: Int,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else completed.toFloat().div(total).coerceIn(0f, 1f)
}

/**
 * Reconstructs editable CTS cards from a rendered CTS-style comparison video.
 *
 * The source contains flattened pixels, so the recovered artwork is the cleanest
 * available artwork crop rather than the original background/subject source layers.
 */
object VideoComparisonImporter {
    private const val PREVIEW_WIDTH = 960
    private const val PREVIEW_HEIGHT = 540
    private const val MAX_SAMPLE_FRAMES = 96
    private const val MIN_SAMPLE_INTERVAL_SECONDS = 0.35f
    private const val MAX_CARDS = 500
    private const val MIN_EDGE_RATIO = 1.16f
    suspend fun reconstruct(
        context: Context,
        source: Uri,
        sourceName: String,
        onProgress: (VideoReconstructionProgress) -> Unit = {},
    ): VideoReconstructionResult {
        onProgress(VideoReconstructionProgress(VideoReconstructionPhase.Reading, 0, 1))
        val retriever = MediaMetadataRetriever()
        val outputDirectory = File(context.filesDir, "video-imports/${UUID.randomUUID()}")
        try {
            retriever.setDataSource(context, source)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: error("The selected video has no readable duration.")
            require(durationMs >= 750L) { "The selected video is too short to contain comparison cards." }
            val sourceWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val sourceHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            require(sourceWidth > 0 && sourceHeight > 0) { "The selected file does not contain readable video." }
            val landscapeRatio = max(sourceWidth, sourceHeight).toFloat() / minOf(sourceWidth, sourceHeight)
            require(landscapeRatio >= 1.45f) {
                "Video reconstruction currently supports landscape CTS comparisons."
            }

            val durationSeconds = durationMs / 1_000f
            val sampleTimes = sampleTimes(durationSeconds)
            val observations = mutableListOf<PanelObservation>()
            val modelVotes = mutableListOf<ModelVote>()
            sampleTimes.forEachIndexed { index, seconds ->
                onProgress(
                    VideoReconstructionProgress(
                        VideoReconstructionPhase.FindingCards,
                        index,
                        sampleTimes.size,
                    ),
                )
                val frame = scaledFrame(retriever, seconds, PREVIEW_WIDTH, PREVIEW_HEIGHT)
                    ?: return@forEachIndexed
                try {
                    val seam = VideoPanelAnalysis.findRepeatingSeam(frame)
                    if (seam.edgeRatio < MIN_EDGE_RATIO) return@forEachIndexed
                    val cardWidth = frame.width / 4
                    val normalizedOffset = VideoPanelAnalysis.normalizeSeamOffset(seam.offsetPx, cardWidth)
                    var panelIndex = 0
                    var left = normalizedOffset
                    while (left + cardWidth <= frame.width && observations.size < MAX_CARDS * 40) {
                        if (left >= 0) {
                            val panel = Bitmap.createBitmap(frame, left, 0, cardWidth, frame.height)
                            try {
                                val quality = VideoPanelAnalysis.contentQuality(panel)
                                if (quality >= 0.34f) {
                                    observations += PanelObservation(
                                        timeSeconds = seconds,
                                        leftFraction = left.toFloat() / frame.width,
                                        panelIndex = panelIndex,
                                        fingerprint = VideoPanelAnalysis.perceptualHash(panel),
                                        badgeScore = VideoPanelAnalysis.redBadgeScore(panel),
                                        quality = quality,
                                        edgeRatio = seam.edgeRatio,
                                    )
                                    modelVotes += VideoPanelAnalysis.modelVote(panel)
                                }
                            } finally {
                                panel.recycle()
                            }
                        }
                        panelIndex++
                        left += cardWidth
                    }
                } finally {
                    frame.recycle()
                }
            }
            onProgress(
                VideoReconstructionProgress(
                    VideoReconstructionPhase.FindingCards,
                    sampleTimes.size,
                    sampleTimes.size,
                ),
            )
            require(observations.isNotEmpty()) {
                "CTS could not find the repeating card layout in this video. Try an exported CTS-style comparison."
            }

            val detectedModel = VideoPanelAnalysis.chooseModel(modelVotes)
            val clusters = VideoPanelAnalysis.cluster(observations)
                .filter { cluster -> cluster.observations.size >= minimumObservations(durationSeconds, sampleTimes.size) }
                .sortedBy { it.orderKey }
                .take(MAX_CARDS)
            require(clusters.isNotEmpty()) {
                "CTS found moving panels, but none stayed visible long enough to reconstruct safely."
            }

            check(outputDirectory.mkdirs()) { "Could not create storage for reconstructed artwork." }
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val reconstructed = try {
                clusters.mapIndexedNotNull { index, cluster ->
                    onProgress(
                        VideoReconstructionProgress(
                            VideoReconstructionPhase.ReadingText,
                            index,
                            clusters.size,
                        ),
                    )
                    reconstructCard(
                        retriever = retriever,
                        cluster = cluster,
                        model = detectedModel,
                        recognizer = recognizer,
                        outputDirectory = outputDirectory,
                        outputIndex = index,
                    )
                }
            } finally {
                recognizer.close()
            }
            require(reconstructed.isNotEmpty()) { "No editable comparison cards could be recovered." }
            onProgress(
                VideoReconstructionProgress(
                    VideoReconstructionPhase.SavingArtwork,
                    reconstructed.size,
                    reconstructed.size,
                ),
            )

            val warnings = buildList {
                add("Artwork is recovered from rendered video pixels; original subject/background layers cannot be separated.")
                if (clusters.size != reconstructed.size) {
                    add("${clusters.size - reconstructed.size} uncertain panel(s) were skipped.")
                }
                val lowConfidence = reconstructed.count { it.confidence < 0.58f }
                if (lowConfidence > 0) add("Review the text on $lowConfidence low-confidence card(s).")
            }
            return VideoReconstructionResult(
                sourceName = sourceName,
                durationSeconds = durationSeconds,
                detectedModel = detectedModel,
                cards = reconstructed,
                warnings = warnings,
            )
        } catch (error: Throwable) {
            if (outputDirectory.exists()) outputDirectory.deleteRecursively()
            throw error
        } finally {
            retriever.release()
        }
    }

    private suspend fun reconstructCard(
        retriever: MediaMetadataRetriever,
        cluster: PanelCluster,
        model: VisualModel,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        outputDirectory: File,
        outputIndex: Int,
    ): ReconstructedCard? {
        val cleanObservation = cluster.observations.minWithOrNull(
            compareBy<PanelObservation> { it.badgeScore }.thenByDescending { it.quality },
        ) ?: return null
        val textObservation = cluster.observations.maxWithOrNull(
            compareBy<PanelObservation> { it.badgeScore }.thenBy { it.quality },
        ) ?: cleanObservation
        val cleanPanel = fullResolutionPanel(retriever, cleanObservation) ?: return null
        val textPanel = if (textObservation == cleanObservation) {
            cleanPanel
        } else {
            fullResolutionPanel(retriever, textObservation) ?: cleanPanel
        }
        try {
            val recognizedText = recognizer.processBitmap(textPanel)
            val fields = VideoPanelAnalysis.readFields(
                text = recognizedText,
                panelWidth = textPanel.width,
                panelHeight = textPanel.height,
                model = model,
            )
            val layoutCard = CtsCard(title = fields.title, description = fields.description)
            val artworkFrame = CardContentLayout.frames(model, layoutCard).image
            val artworkRect = Rect(
                (cleanPanel.width * artworkFrame.x).roundToInt().coerceIn(0, cleanPanel.width - 1),
                (cleanPanel.height * artworkFrame.y).roundToInt().coerceIn(0, cleanPanel.height - 1),
                (cleanPanel.width * (artworkFrame.x + artworkFrame.width)).roundToInt().coerceIn(1, cleanPanel.width),
                (cleanPanel.height * (artworkFrame.y + artworkFrame.height)).roundToInt().coerceIn(1, cleanPanel.height),
            )
            if (artworkRect.width() < 32 || artworkRect.height() < 32) return null
            val artwork = Bitmap.createBitmap(
                cleanPanel,
                artworkRect.left,
                artworkRect.top,
                artworkRect.width(),
                artworkRect.height(),
            )
            val artworkFile = File(outputDirectory, "artwork-${(outputIndex + 1).toString().padStart(3, '0')}.png")
            try {
                FileOutputStream(artworkFile).use { stream ->
                    check(artwork.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        "Could not save reconstructed artwork ${outputIndex + 1}."
                    }
                }
            } finally {
                artwork.recycle()
            }
            val confidence = (
                fields.confidence * 0.65f +
                    cleanObservation.quality * 0.20f +
                    ((cleanObservation.edgeRatio - 1f) / 0.7f).coerceIn(0f, 1f) * 0.15f
                ).coerceIn(0f, 1f)
            val warnings = buildList {
                if (fields.title.isBlank()) add("Title was not detected")
                if (fields.description.isBlank()) add("Description was not detected")
                if (fields.badgePrimary.isBlank()) add("Badge value was not detected")
                if (cleanObservation.badgeScore > 0.035f) add("Artwork may contain part of the rendered badge")
            }
            return ReconstructedCard(
                badgePrimary = fields.badgePrimary,
                badgeSecondary = fields.badgeSecondary,
                title = fields.title,
                description = fields.description,
                artworkPath = artworkFile.absolutePath,
                sourceTimeSeconds = cleanObservation.timeSeconds,
                confidence = confidence,
                warnings = warnings,
            )
        } finally {
            if (textPanel !== cleanPanel && !textPanel.isRecycled) textPanel.recycle()
            if (!cleanPanel.isRecycled) cleanPanel.recycle()
        }
    }

    private fun fullResolutionPanel(
        retriever: MediaMetadataRetriever,
        observation: PanelObservation,
    ): Bitmap? {
        val frame = retriever.getFrameAtTime(
            (observation.timeSeconds * 1_000_000f).toLong(),
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return null
        try {
            val cardWidth = frame.width / 4
            val left = (observation.leftFraction * frame.width).roundToInt()
                .coerceIn(0, max(0, frame.width - cardWidth))
            return Bitmap.createBitmap(frame, left, 0, cardWidth, frame.height)
        } finally {
            frame.recycle()
        }
    }

    private fun scaledFrame(
        retriever: MediaMetadataRetriever,
        seconds: Float,
        width: Int,
        height: Int,
    ): Bitmap? {
        val timeUs = (seconds * 1_000_000f).toLong()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, width, height)
        } else {
            val source = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            try {
                Bitmap.createScaledBitmap(source, width, height, true)
            } finally {
                source.recycle()
            }
        }
    }

    private fun sampleTimes(durationSeconds: Float): List<Float> {
        val usableStart = minOf(0.35f, durationSeconds * 0.03f)
        val usableEnd = max(usableStart, durationSeconds - minOf(0.35f, durationSeconds * 0.03f))
        val interval = max(MIN_SAMPLE_INTERVAL_SECONDS, (usableEnd - usableStart) / (MAX_SAMPLE_FRAMES - 1))
        return buildList {
            var time = usableStart
            while (time <= usableEnd + 0.001f && size < MAX_SAMPLE_FRAMES) {
                add(time)
                time += interval
            }
            if (isEmpty()) add(durationSeconds / 2f)
        }
    }

    private fun minimumObservations(durationSeconds: Float, sampleCount: Int): Int {
        val interval = durationSeconds / sampleCount.coerceAtLeast(1)
        return if (interval <= 1.2f) 2 else 1
    }

    private suspend fun com.google.mlkit.vision.text.TextRecognizer.processBitmap(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { continuation ->
            process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
                .addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
        }
}

internal data class PanelObservation(
    val timeSeconds: Float,
    val leftFraction: Float,
    val panelIndex: Int,
    val fingerprint: Long,
    val badgeScore: Float,
    val quality: Float,
    val edgeRatio: Float,
)

internal data class PanelCluster(val observations: MutableList<PanelObservation>) {
    val orderKey: Float
        get() {
            val first = observations.minWith(compareBy<PanelObservation> { it.timeSeconds }.thenBy { it.leftFraction })
            return first.timeSeconds * 8f + first.leftFraction * 4f
        }
}

internal data class SeamResult(val offsetPx: Int, val edgeRatio: Float)

internal data class ModelVote(val males: Float, val relationships: Float)

internal data class RecognizedCardFields(
    val badgePrimary: String,
    val badgeSecondary: String,
    val title: String,
    val description: String,
    val confidence: Float,
)

internal object VideoPanelAnalysis {
    private const val MAX_HASH_DISTANCE = 8

    fun findRepeatingSeam(bitmap: Bitmap): SeamResult {
        val cardWidth = bitmap.width / 4
        if (cardWidth < 8) return SeamResult(0, 0f)
        val scores = FloatArray(cardWidth)
        for (offset in 0 until cardWidth) {
            var difference = 0L
            var samples = 0
            var boundary = if (offset == 0) cardWidth else offset
            while (boundary < bitmap.width) {
                for (y in bitmap.height / 3 until bitmap.height step 7) {
                    val left = bitmap.getPixel(boundary - 1, y)
                    val right = bitmap.getPixel(boundary, y)
                    difference += colorDistance(left, right)
                    samples++
                }
                boundary += cardWidth
            }
            scores[offset] = if (samples == 0) 0f else difference.toFloat() / samples
        }
        val bestOffset = scores.indices.maxByOrNull { scores[it] } ?: 0
        val sorted = scores.sorted()
        val median = sorted[sorted.size / 2].coerceAtLeast(1f)
        return SeamResult(bestOffset, scores[bestOffset] / median)
    }

    fun normalizeSeamOffset(offsetPx: Int, cardWidth: Int): Int {
        if (cardWidth <= 0) return 0
        val wrapped = ((offsetPx % cardWidth) + cardWidth) % cardWidth
        val snap = (cardWidth * 0.035f).roundToInt()
        return when {
            wrapped <= snap || wrapped >= cardWidth - snap -> 0
            else -> wrapped
        }
    }

    fun contentQuality(bitmap: Bitmap): Float {
        var count = 0
        var visible = 0
        var sum = 0.0
        var squareSum = 0.0
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 6) {
                val luma = luma(bitmap.getPixel(x, y)).toDouble()
                if (luma > 34.0) visible++
                sum += luma
                squareSum += luma * luma
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sum / count
        val deviation = sqrt(max(0.0, squareSum / count - mean * mean))
        val visibleFraction = visible.toFloat() / count
        return (visibleFraction * 0.58f + (deviation / 72.0).toFloat().coerceIn(0f, 1f) * 0.42f)
            .coerceIn(0f, 1f)
    }

    fun perceptualHash(bitmap: Bitmap): Long {
        val top = (bitmap.height * 0.36f).roundToInt()
        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
        val tiny = Bitmap.createScaledBitmap(crop, 9, 8, true)
        crop.recycle()
        try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    if (luma(tiny.getPixel(x, y)) >= luma(tiny.getPixel(x + 1, y))) {
                        hash = hash or (1L shl bit)
                    }
                    bit++
                }
            }
            return hash
        } finally {
            tiny.recycle()
        }
    }

    fun redBadgeScore(bitmap: Bitmap): Float {
        var red = 0
        var count = 0
        val maxY = (bitmap.height * 0.42f).roundToInt()
        for (y in 0 until maxY step 5) {
            for (x in 0 until bitmap.width step 4) {
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (r > 145 && r > g * 1.55f && r > b * 1.45f) red++
                count++
            }
        }
        return if (count == 0) 0f else red.toFloat() / count
    }

    fun modelVote(bitmap: Bitmap): ModelVote {
        val relationshipsStart = (bitmap.height * 788f / 1080f).roundToInt()
        val malesStart = (bitmap.height * 872f / 1080f).roundToInt()
        val relationshipsRule = (bitmap.height * 906f / 1080f).roundToInt()
        val relationshipEdge = rowDifference(bitmap, relationshipsStart)
        val malesEdge = rowDifference(bitmap, malesStart)
        val orange = orangeFraction(bitmap, relationshipsRule)
        return ModelVote(
            males = malesEdge + (1f - orange) * 8f,
            relationships = relationshipEdge + orange * 65f,
        )
    }

    fun chooseModel(votes: List<ModelVote>): VisualModel {
        if (votes.isEmpty()) return VisualModel.Males
        val strongest = votes.sortedByDescending { max(it.males, it.relationships) }
            .take(max(3, votes.size / 3))
        val males = strongest.sumOf { it.males.toDouble() }
        val relationships = strongest.sumOf { it.relationships.toDouble() }
        return if (relationships > males * 1.05) VisualModel.Relationships else VisualModel.Males
    }

    fun cluster(observations: List<PanelObservation>): List<PanelCluster> {
        val clusters = mutableListOf<PanelCluster>()
        observations.sortedWith(compareBy<PanelObservation> { it.timeSeconds }.thenBy { it.leftFraction })
            .forEach { observation ->
                val matching = clusters.minByOrNull { cluster ->
                    cluster.observations.minOf { hammingDistance(it.fingerprint, observation.fingerprint) }
                }
                val distance = matching?.observations
                    ?.minOf { hammingDistance(it.fingerprint, observation.fingerprint) }
                    ?: Int.MAX_VALUE
                if (matching != null && distance <= MAX_HASH_DISTANCE) {
                    matching.observations += observation
                } else {
                    clusters += PanelCluster(mutableListOf(observation))
                }
            }
        return clusters
    }

    fun readFields(
        text: Text,
        panelWidth: Int,
        panelHeight: Int,
        model: VisualModel,
    ): RecognizedCardFields {
        val lines = text.textBlocks.flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text.cleanOcr(), box, line.elements.mapNotNull { it.confidence }.averageOrNull())
            }
            .filter { it.value.isNotBlank() }
            .sortedWith(compareBy<OcrLine> { it.box.top }.thenBy { it.box.left })
        val badgeLines = lines.filter {
            it.box.centerY() < panelHeight * 0.40f &&
                it.box.centerX() in (panelWidth * 0.10f).roundToInt()..(panelWidth * 0.90f).roundToInt()
        }
        val contentLines = lines - badgeLines.toSet()
        val descriptionStart = panelHeight * if (model == VisualModel.Relationships) 0.835f else 0.885f
        val titleStart = panelHeight * if (model == VisualModel.Relationships) 0.69f else 0.78f
        val description = contentLines.filter { it.box.centerY() >= descriptionStart }
            .joinToString(" ") { it.value }
            .cleanOcr()
        val title = contentLines.filter {
            it.box.centerY() >= titleStart && it.box.centerY() < descriptionStart
        }.joinToString(" ") { it.value }.cleanOcr()

        val badgePrimary: String
        val badgeSecondary: String
        if (model == VisualModel.Relationships) {
            badgePrimary = badgeLines
                .filterNot { it.value.equals("1 in", true) || it.value.equals("People", true) }
                .maxByOrNull { it.box.height() }
                ?.value
                ?.replace(Regex("[^0-9./-]"), "")
                .orEmpty()
            badgeSecondary = ""
        } else {
            val primary = badgeLines.maxByOrNull { it.box.height() }
            badgePrimary = primary?.value.orEmpty()
            badgeSecondary = badgeLines.filterNot { it === primary }
                .joinToString(" ") { it.value }
                .cleanOcr()
        }
        val relevant = lines.filter { line ->
            line in badgeLines || line.box.centerY() >= titleStart
        }
        val confidence = relevant.mapNotNull { it.confidence }.averageOrNull()
            ?: when {
                title.isNotBlank() && badgePrimary.isNotBlank() -> 0.72f
                title.isNotBlank() || description.isNotBlank() -> 0.55f
                else -> 0.25f
            }
        return RecognizedCardFields(
            badgePrimary = badgePrimary,
            badgeSecondary = badgeSecondary,
            title = title,
            description = description,
            confidence = confidence.coerceIn(0f, 1f),
        )
    }

    fun hammingDistance(first: Long, second: Long): Int = java.lang.Long.bitCount(first xor second)

    private fun rowDifference(bitmap: Bitmap, y: Int): Float {
        val safeY = y.coerceIn(1, bitmap.height - 1)
        var total = 0L
        var count = 0
        for (x in 0 until bitmap.width step 3) {
            total += colorDistance(bitmap.getPixel(x, safeY - 1), bitmap.getPixel(x, safeY))
            count++
        }
        return if (count == 0) 0f else total.toFloat() / count
    }

    private fun orangeFraction(bitmap: Bitmap, y: Int): Float {
        var orange = 0
        var count = 0
        val start = (y - bitmap.height * 0.008f).roundToInt().coerceAtLeast(0)
        val end = (y + bitmap.height * 0.008f).roundToInt().coerceAtMost(bitmap.height - 1)
        for (sampleY in start..end step 2) {
            for (x in 0 until bitmap.width step 3) {
                val color = bitmap.getPixel(x, sampleY)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                if (r > 150 && g in 55..170 && b < 80) orange++
                count++
            }
        }
        return if (count == 0) 0f else orange.toFloat() / count
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs(Color.red(first) - Color.red(second)) +
            abs(Color.green(first) - Color.green(second)) +
            abs(Color.blue(first) - Color.blue(second))

    private fun luma(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1_000

    private fun String.cleanOcr(): String = trim()
        .replace(Regex("\\s+"), " ")
        .replace(" | ", " ")

    private fun List<Float>.averageOrNull(): Float? = if (isEmpty()) null else average().toFloat()

    private data class OcrLine(
        val value: String,
        val box: Rect,
        val confidence: Float?,
    )
}
