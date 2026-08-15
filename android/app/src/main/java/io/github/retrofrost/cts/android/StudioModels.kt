package io.github.retrofrost.cts.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class StudioCard(
    val id: String = UUID.randomUUID().toString().replace("-", ""),
    val title: String = "",
    val value: String = "",
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
    val cards: List<StudioCard> = listOf(StudioCard(title = "Card 1", value = "1")),
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 60,
    val showBadges: Boolean = true,
    val creditsEnabled: Boolean = true,
    val soundtrack: String = "",
    val soundtrackVolume: Float = 0.75f,
    val soundtrackLoop: Boolean = true,
) {
    fun toJson(): String {
        val settings = JSONObject()
            .put("model_id", "what-males-learn-at-each-age")
            .put("model_revision", 1)
            .put("width", width).put("height", height).put("fps", fps)
            .put("auto_length", true).put("show_badges", showBadges).put("credits_enabled", creditsEnabled)
            .put("soundtrack", soundtrack).put("soundtrack_volume", soundtrackVolume.toDouble()).put("soundtrack_loop", soundtrackLoop)
            .put("encoder_preset", "faster").put("encoder_crf", 18)
        val array = JSONArray()
        cards.forEach { card ->
            array.put(JSONObject().put("id", card.id).put("title", card.title).put("value", card.value)
                .put("description", card.description).put("image", card.image)
                .put("image_x", card.imageX).put("image_y", card.imageY).put("image_scale", card.imageScale).put("image_rotation", card.imageRotation)
                .put("image_crop_left", card.imageCropLeft).put("image_crop_top", card.imageCropTop)
                .put("image_crop_right", card.imageCropRight).put("image_crop_bottom", card.imageCropBottom).put("image_layer", card.imageLayer))
        }
        return JSONObject().put("version", 3).put("name", name).put("cards", array).put("settings", settings)
            .put("model_lock", JSONObject().put("id", "what-males-learn-at-each-age").put("revision", 1).put("renderer_profile", "what-males-learn-at-each-age"))
            .toString()
    }

    companion object {
        fun fromJson(text: String): StudioProject {
            val root = JSONObject(text)
            val settings = root.optJSONObject("settings") ?: JSONObject()
            val array = root.optJSONArray("cards") ?: JSONArray()
            val cards = buildList {
                for (i in 0 until array.length()) {
                    val c = array.getJSONObject(i)
                    add(StudioCard(
                        id = c.optString("id").ifBlank { UUID.randomUUID().toString().replace("-", "") }, title = c.optString("title"), value = c.optString("value"),
                        description = c.optString("description"), image = c.optString("image"), imageX = c.optDouble("image_x", 0.0), imageY = c.optDouble("image_y", 0.0),
                        imageScale = c.optDouble("image_scale", 1.0), imageRotation = c.optDouble("image_rotation", 0.0),
                        imageCropLeft = c.optDouble("image_crop_left", 0.0), imageCropTop = c.optDouble("image_crop_top", 0.0),
                        imageCropRight = c.optDouble("image_crop_right", 0.0), imageCropBottom = c.optDouble("image_crop_bottom", 0.0), imageLayer = c.optString("image_layer", "behind")))
                }
            }
            return StudioProject(root.optString("name", "Untitled"), cards.ifEmpty { listOf(StudioCard(title = "Card 1", value = "1")) },
                settings.optInt("width", 1920), settings.optInt("height", 1080), settings.optInt("fps", 60),
                settings.optBoolean("show_badges", true), settings.optBoolean("credits_enabled", true), settings.optString("soundtrack", ""),
                settings.optDouble("soundtrack_volume", 0.75).toFloat(), settings.optBoolean("soundtrack_loop", true))
        }
    }
}

data class RenderMetadata(val frameCount: Int, val duration: Double, val fps: Int)
