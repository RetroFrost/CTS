package dev.infinitycomparison.cc

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class EncoderPreference(val wireName: String, val displayName: String) {
    AUTO("auto", "Auto"),
    H264("h264", "H.264"),
    H265("h265", "H.265");

    companion object {
        fun fromWireName(value: String?): EncoderPreference =
            entries.firstOrNull { it.wireName == value } ?: AUTO
    }
}

data class StudioCard(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val title: String = "Card 1",
    val value: String = "1",
    val badgeHeader: String = "",
    val description: String = "",
    val image: String = "",
    val imageX: Double = 0.0,
    val imageY: Double = 0.0,
    val imageScale: Double = 1.0,
    val imageRotation: Double = 0.0,
    val imageCropLeft: Double = 0.0,
    val imageCropTop: Double = 0.0,
    val imageCropRight: Double = 0.0,
    val imageCropBottom: Double = 0.0,
    val imageLayer: String = "behind",
)

data class StudioProject(
    val name: String = "Untitled",
    val cards: List<StudioCard> = listOf(StudioCard()),
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val showBadges: Boolean = true,
    val settledScrollingBadges: Boolean = false,
    val creditsEnabled: Boolean = true,
    val soundtrack: String = "",
    val soundtrackVolume: Float = 0.75f,
    val soundtrackLoop: Boolean = true,
    val autoLength: Boolean = true,
    val customLengthSeconds: Double = 90.0,
    val encoderPreference: EncoderPreference = EncoderPreference.AUTO,
) {
    fun toJson(): String {
        val settings = JSONObject()
            .put("model_id", "what-males-learn-at-each-age")
            .put("model_revision", 1)
            .put("width", width)
            .put("height", height)
            .put("fps", fps)
            .put("auto_length", autoLength)
            .put("custom_length_seconds", customLengthSeconds)
            .put("show_badges", showBadges)
            .put("settled_scrolling_badges", settledScrollingBadges)
            .put("credits_enabled", creditsEnabled)
            .put("soundtrack", soundtrack)
            .put("soundtrack_volume", soundtrackVolume)
            .put("soundtrack_loop", soundtrackLoop)
            .put("encoder_preference", encoderPreference.wireName)
            .put("encoder_preset", "faster")
            .put("encoder_crf", 18)

        val cardArray = JSONArray()
        cards.forEach { card ->
            cardArray.put(
                JSONObject()
                    .put("id", card.id)
                    .put("title", card.title)
                    .put("value", card.value)
                    .put("badge_header", card.badgeHeader)
                    .put("description", card.description)
                    .put("image", card.image)
                    .put("image_x", card.imageX)
                    .put("image_y", card.imageY)
                    .put("image_scale", card.imageScale)
                    .put("image_rotation", card.imageRotation)
                    .put("image_crop_left", card.imageCropLeft)
                    .put("image_crop_top", card.imageCropTop)
                    .put("image_crop_right", card.imageCropRight)
                    .put("image_crop_bottom", card.imageCropBottom)
                    .put("image_layer", card.imageLayer),
            )
        }

        return JSONObject()
            .put("version", 3)
            .put("name", name)
            .put("cards", cardArray)
            .put("settings", settings)
            .put(
                "model_lock",
                JSONObject()
                    .put("id", "what-males-learn-at-each-age")
                    .put("revision", 1)
                    .put("renderer_profile", "what-males-learn-at-each-age"),
            )
            .toString()
    }

    fun copyUiSettingsFrom(previous: StudioProject): StudioProject = copy(
        encoderPreference = previous.encoderPreference,
        settledScrollingBadges = previous.settledScrollingBadges,
    )

    companion object {
        fun fromJson(text: String): StudioProject {
            val root = JSONObject(text)
            val settings = root.optJSONObject("settings") ?: JSONObject()
            val jsonCards = root.optJSONArray("cards") ?: JSONArray()
            val cards = buildList {
                repeat(jsonCards.length()) { index ->
                    val card = jsonCards.getJSONObject(index)
                    add(
                        StudioCard(
                            id = card.optString("id").ifBlank {
                                UUID.randomUUID().toString().replace("-", "")
                            },
                            title = card.optString("title"),
                            value = card.optString("value"),
                            badgeHeader = card.optString("badge_header", card.optString("badgeHeader")),
                            description = card.optString("description"),
                            image = card.optString("image"),
                            imageX = card.optDouble("image_x", 0.0),
                            imageY = card.optDouble("image_y", 0.0),
                            imageScale = card.optDouble("image_scale", 1.0),
                            imageRotation = card.optDouble("image_rotation", 0.0),
                            imageCropLeft = card.optDouble("image_crop_left", 0.0),
                            imageCropTop = card.optDouble("image_crop_top", 0.0),
                            imageCropRight = card.optDouble("image_crop_right", 0.0),
                            imageCropBottom = card.optDouble("image_crop_bottom", 0.0),
                            imageLayer = card.optString("image_layer", "behind"),
                        ),
                    )
                }
            }.ifEmpty { listOf(StudioCard()) }

            return StudioProject(
                name = root.optString("name", "Untitled"),
                cards = cards,
                width = settings.optInt("width", 1920),
                height = settings.optInt("height", 1080),
                fps = settings.optInt("fps", 60),
                showBadges = settings.optBoolean("show_badges", true),
                settledScrollingBadges = settings.optBoolean("settled_scrolling_badges", false),
                creditsEnabled = settings.optBoolean("credits_enabled", true),
                soundtrack = settings.optString("soundtrack", ""),
                soundtrackVolume = settings.optDouble("soundtrack_volume", 0.75).toFloat(),
                soundtrackLoop = settings.optBoolean("soundtrack_loop", true),
                autoLength = settings.optBoolean("auto_length", true),
                customLengthSeconds = settings.optDouble("custom_length_seconds", 90.0),
                encoderPreference = EncoderPreference.fromWireName(
                    settings.optString("encoder_preference", "auto"),
                ),
            )
        }
    }
}

object DurationFormat {
    fun format(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun parse(text: String): Double? {
        val parts = text.trim().split(':')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.takeIf { it > 0.0 }
            2 -> {
                val minutes = parts[0].toIntOrNull() ?: return null
                val seconds = parts[1].toIntOrNull() ?: return null
                if (minutes < 0 || seconds !in 0..59) null else (minutes * 60 + seconds).toDouble()
            }
            else -> null
        }
    }
}
