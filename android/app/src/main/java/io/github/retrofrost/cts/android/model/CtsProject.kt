package io.github.retrofrost.cts.android.model

import android.media.MediaFormat
import io.github.retrofrost.cts.android.shared.SHARED_SAMPLE_CARDS
import io.github.retrofrost.cts.android.shared.SharedContract
import java.util.UUID

/**
 * The Android renderer is intentionally hierarchical:
 *
 * Timeline -> parent card -> child image subcard.
 *
 * An image subcard never owns timeline coordinates. Its transform is normalized inside
 * the image frame of exactly one parent card, so it cannot reveal early or follow the
 * complete card strip by accident.
 */
enum class VisualModel(
    val id: String,
    val label: String,
    val visibleCards: Int,
) {
    Males(
        "what-males-learn-at-each-age",
        "What Males Learn at Each Age",
        SharedContract.VISIBLE_CARDS,
    ),
    Relationships(
        "types-of-relationships",
        "Types of Relationships",
        4,
    ),
    ;

    companion object {
        fun fromId(id: String?): VisualModel = when (id) {
            Relationships.id, "relationships", "relationship_reference", "relationships_exact_reference" -> Relationships
            else -> Males
        }
    }
}

enum class ModelMode(val id: String, val label: String) {
    ExactReference("exact_reference", "Exact Reference"),
    Custom("custom", "Custom"),
    ;

    companion object {
        fun fromId(id: String?): ModelMode = entries.firstOrNull { it.id == id } ?: ExactReference
    }
}

data class NormalizedRect(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
) {
    fun clamped(minimumSize: Float = 0.08f): NormalizedRect {
        val safeWidth = width.coerceIn(minimumSize, 1f)
        val safeHeight = height.coerceIn(minimumSize, 1f)
        return copy(
            x = x.coerceIn(0f, 1f - safeWidth),
            y = y.coerceIn(0f, 1f - safeHeight),
            width = safeWidth,
            height = safeHeight,
        )
    }

    companion object {
        val Full = NormalizedRect()
    }
}

data class ImageSubcard(
    val id: String = UUID.randomUUID().toString(),
    val parentCardId: String,
    val source: String? = null,
    val transform: NormalizedRect = NormalizedRect.Full,
)

data class CtsCard(
    val id: String = UUID.randomUUID().toString(),
    val badgePrimary: String = "",
    val badgeSecondary: String = "",
    val title: String = "",
    val description: String = "",
    val imageSubcard: ImageSubcard = ImageSubcard(parentCardId = id),
) {
    fun withOwnedImageSubcard(subcard: ImageSubcard = imageSubcard): CtsCard =
        copy(imageSubcard = subcard.copy(parentCardId = id, transform = subcard.transform.clamped()))
}

/** One soundtrack is enough for the fast CTS workflow and remains desktop compatible. */
data class SoundtrackSettings(
    val uri: String? = null,
    val displayName: String = "",
    val volume: Float = 1f,
    val loop: Boolean = true,
) {
    fun normalized(): SoundtrackSettings = copy(volume = volume.coerceIn(0f, 2f))
}

/** Device encoder selections and quality controls used by the background exporter. */
data class ExportSettings(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val videoBitrate: Int = 6_000_000,
    val videoMime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    val videoEncoderName: String? = null,
    val audioBitrate: Int = 192_000,
    val audioEncoderName: String? = null,
) {
    fun normalized(): ExportSettings {
        val safeWidth = width.coerceIn(640, 3840).let { it - it % 2 }
        val safeHeight = height.coerceIn(360, 2160).let { it - it % 2 }
        val safeMime = when (videoMime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            else -> MediaFormat.MIMETYPE_VIDEO_AVC
        }
        return copy(
            width = safeWidth,
            height = safeHeight,
            fps = fps.coerceIn(15, 60),
            videoBitrate = videoBitrate.coerceIn(1_000_000, 50_000_000),
            videoMime = safeMime,
            videoEncoderName = videoEncoderName?.takeIf { it.isNotBlank() },
            audioBitrate = audioBitrate.coerceIn(64_000, 320_000),
            audioEncoderName = audioEncoderName?.takeIf { it.isNotBlank() },
        )
    }
}

data class CtsProject(
    val version: Int = SharedContract.PROJECT_VERSION,
    val name: String = "Untitled comparison",
    val model: VisualModel = VisualModel.Males,
    val modelMode: ModelMode = ModelMode.ExactReference,
    val cards: List<CtsCard> = sampleCards(),
    /** Badge visibility; the historical JSON key is retained for desktop compatibility. */
    val showHexagons: Boolean = true,
    val showIntro: Boolean = true,
    val showDisclaimer: Boolean = true,
    val showOutro: Boolean = true,
    /** Null uses automatic timing; a value retimes only horizontal card scrolling. */
    val customDurationSeconds: Float? = null,
    val soundtrack: SoundtrackSettings = SoundtrackSettings(),
    val export: ExportSettings = ExportSettings(),
) {
    fun normalized(): CtsProject {
        val safeExport = export.normalized()
        val modelExport = if (modelMode == ModelMode.ExactReference) {
            safeExport.copy(width = 1920, height = 1080, fps = 60)
        } else {
            safeExport
        }
        return copy(
            version = SharedContract.PROJECT_VERSION,
            cards = cards.map { it.withOwnedImageSubcard() },
            customDurationSeconds = DurationRuntime.normalizeProjectValue(customDurationSeconds),
            soundtrack = soundtrack.normalized(),
            export = modelExport,
        )
    }

    fun updateCard(cardId: String, update: (CtsCard) -> CtsCard): CtsProject = copy(
        cards = cards.map { card ->
            if (card.id == cardId) update(card).withOwnedImageSubcard() else card
        },
    )

    fun removeCard(cardId: String): CtsProject = copy(cards = cards.filterNot { it.id == cardId })

    fun duplicateCard(cardId: String): CtsProject {
        val index = cards.indexOfFirst { it.id == cardId }
        if (index < 0) return this
        val source = cards[index]
        val duplicateId = UUID.randomUUID().toString()
        val duplicate = source.copy(
            id = duplicateId,
            imageSubcard = source.imageSubcard.copy(
                id = UUID.randomUUID().toString(),
                parentCardId = duplicateId,
            ),
        )
        return copy(cards = cards.toMutableList().apply { add(index + 1, duplicate) })
    }

    fun addBlankCard(): CtsProject {
        val card = CtsCard(title = "New card")
        return copy(cards = cards + card)
    }
}

fun sampleCards(): List<CtsCard> = SHARED_SAMPLE_CARDS.map { sample ->
    CtsCard(
        badgePrimary = sample.badgePrimary,
        badgeSecondary = sample.badgeSecondary,
        title = sample.title,
        description = sample.description,
    )
}
