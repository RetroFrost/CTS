package io.github.retrofrost.cts.android.persistence

import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.model.CtsSoundtrack
import io.github.retrofrost.cts.android.model.ImageSubcard
import io.github.retrofrost.cts.android.model.NormalizedRect
import io.github.retrofrost.cts.android.model.VisualModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Reads the desktop CTS spreadsheet project format and writes a desktop-compatible
 * superset. Android-only identity and parent/child metadata live under `android`, while
 * the standard spreadsheet/settings keys remain available to the Python application.
 */
object ProjectJson {
    private val headers = listOf("Value", "Label", "Title", "Description", "Image")

    fun encode(project: CtsProject): String {
        val normalized = project.normalized()
        val rows = JSONArray()
        normalized.cards.forEach { card ->
            rows.put(
                JSONArray()
                    .put(card.badgePrimary)
                    .put(card.badgeSecondary)
                    .put(card.title)
                    .put(card.description)
                    .put(card.imageSubcard.source.orEmpty()),
            )
        }

        val transforms = JSONObject()
        normalized.cards.forEachIndexed { index, card ->
            val rect = card.imageSubcard.transform.clamped()
            transforms.put(
                "$index:image",
                JSONArray().put(rect.x).put(rect.y).put(rect.width).put(rect.height),
            )
        }

        val audioTracks = JSONArray().apply {
            normalized.soundtrack?.let { soundtrack ->
                put(
                    JSONObject()
                        .put("source", soundtrack.source)
                        .put("path", soundtrack.source)
                        .put("name", soundtrack.displayName)
                        .put("start_time", 0.0)
                        .put("volume", soundtrack.volume)
                        .put("loop", soundtrack.loop),
                )
            }
        }

        val android = JSONObject()
            .put("project_name", normalized.name)
            .put("card_ids", JSONArray(normalized.cards.map { it.id }))
            .put("image_subcard_ids", JSONArray(normalized.cards.map { it.imageSubcard.id }))
            .apply {
                normalized.soundtrack?.let { soundtrack ->
                    put(
                        "soundtrack",
                        JSONObject()
                            .put("source", soundtrack.source)
                            .put("display_name", soundtrack.displayName)
                            .put("volume", soundtrack.volume)
                            .put("loop", soundtrack.loop),
                    )
                }
            }

        val settings = JSONObject()
            .put("width", 1920)
            .put("height", 1080)
            .put("fps", 30)
            .put("custom_duration", normalized.customDurationSeconds ?: JSONObject.NULL)
            .put("model_id", normalized.model.id)
            .put("visible_cards", 0)
            .put("field_mapping", JSONObject().apply {
                put("badge_primary", "Value")
                put("badge_secondary", "Label")
                put("title", "Title")
                put("description", "Description")
                put("image", "Image")
            })
            .put("soundtrack_master_volume", normalized.soundtrack?.volume ?: 1.0)
            .put("hexagons_bounce", true)
            .put("show_hexagons", normalized.showHexagons)

        return JSONObject()
            .put("version", 3)
            .put(
                "spreadsheet",
                JSONObject()
                    .put("headers", JSONArray(headers))
                    .put("rows", rows),
            )
            .put("settings", settings)
            .put("audio_tracks", audioTracks)
            .put("transform_space", "per_card_image_frame_v3")
            .put("transform_overrides", transforms)
            .put("android", android)
            .toString(2)
    }

    fun decode(text: String): CtsProject {
        val root = JSONObject(text)
        return when {
            root.has("spreadsheet") -> decodeSpreadsheetProject(root)
            root.has("cards") -> decodeCardProject(root)
            else -> throw IllegalArgumentException(
                "This file does not contain CTS cards or a CTS spreadsheet.",
            )
        }.normalized()
    }

    private fun decodeSpreadsheetProject(root: JSONObject): CtsProject {
        val spreadsheet = root.optJSONObject("spreadsheet") ?: JSONObject()
        val headerArray = spreadsheet.optJSONArray("headers") ?: JSONArray()
        val rowArray = spreadsheet.optJSONArray("rows") ?: JSONArray()
        val headerList = List(headerArray.length()) { index -> headerArray.optString(index) }

        val settings = root.optJSONObject("settings") ?: JSONObject()
        val mapping = settings.optJSONObject("field_mapping") ?: JSONObject()

        fun columnFor(role: String, aliases: Set<String>): Int {
            val mapped = mapping.optString(role)
            if (mapped.isNotBlank()) {
                val exact = headerList.indexOfFirst { it.equals(mapped, ignoreCase = true) }
                if (exact >= 0) return exact
            }
            return headerList.indexOfFirst { header -> header.trim().lowercase() in aliases }
        }

        val badgePrimaryColumn = columnFor(
            "badge_primary",
            setOf("value", "date", "uploaded", "badge value", "year"),
        )
        val badgeSecondaryColumn = columnFor(
            "badge_secondary",
            setOf("label", "unit", "badge label"),
        )
        val titleColumn = columnFor("title", setOf("title", "name", "heading", "item"))
        val descriptionColumn = columnFor(
            "description",
            setOf("description", "details", "summary", "caption"),
        )
        val imageColumn = columnFor(
            "image",
            setOf("image", "artwork", "picture", "photo", "image path", "image url"),
        )

        val android = root.optJSONObject("android")
        val ids = android?.optJSONArray("card_ids")
        val subcardIds = android?.optJSONArray("image_subcard_ids")
        val transforms = root.optJSONObject("transform_overrides") ?: JSONObject()

        fun cell(row: JSONArray, column: Int): String =
            if (column in 0 until row.length()) row.optString(column) else ""

        val cards = buildList {
            for (index in 0 until rowArray.length()) {
                val row = rowArray.optJSONArray(index) ?: continue
                val values = List(row.length()) { cellIndex -> row.optString(cellIndex) }
                if (values.all { it.isBlank() }) continue

                val cardId = ids?.optString(index)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val subcardId = subcardIds?.optString(index)?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val transform = transforms.optJSONArray("$index:image")?.toRect()
                    ?: NormalizedRect.Full

                add(
                    CtsCard(
                        id = cardId,
                        badgePrimary = cell(row, badgePrimaryColumn),
                        badgeSecondary = cell(row, badgeSecondaryColumn),
                        title = cell(row, titleColumn),
                        description = cell(row, descriptionColumn),
                        imageSubcard = ImageSubcard(
                            id = subcardId,
                            parentCardId = cardId,
                            source = cell(row, imageColumn).takeIf { it.isNotBlank() },
                            transform = transform.clamped(),
                        ),
                    ),
                )
            }
        }

        val customDuration = if (settings.isNull("custom_duration")) {
            null
        } else {
            settings.optDouble("custom_duration")
                .takeIf { !it.isNaN() }
                ?.toFloat()
        }

        return CtsProject(
            name = android?.optString("project_name")?.takeIf { it.isNotBlank() }
                ?: "Imported comparison",
            model = VisualModel.fromId(settings.optString("model_id")),
            cards = cards,
            showHexagons = settings.optBoolean("show_hexagons", true),
            customDurationSeconds = customDuration,
            soundtrack = decodeSoundtrack(root, android, settings),
        )
    }

    private fun decodeCardProject(root: JSONObject): CtsProject {
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val cardArray = root.optJSONArray("cards") ?: JSONArray()
        val cards = buildList {
            for (index in 0 until cardArray.length()) {
                val entry = cardArray.optJSONObject(index) ?: continue
                val cardId = entry.optString("id").ifBlank { UUID.randomUUID().toString() }
                val imageObject = entry.optJSONObject("image_subcard")
                val transformObject = imageObject?.optJSONObject("transform")
                val transform = if (transformObject != null) {
                    NormalizedRect(
                        x = transformObject.optDouble("x", 0.0).toFloat(),
                        y = transformObject.optDouble("y", 0.0).toFloat(),
                        width = transformObject.optDouble("width", 1.0).toFloat(),
                        height = transformObject.optDouble("height", 1.0).toFloat(),
                    ).clamped()
                } else {
                    NormalizedRect.Full
                }

                val imageSource = imageObject
                    ?.optString("source")
                    ?.takeIf { it.isNotBlank() }
                    ?: entry.optString("image").takeIf { it.isNotBlank() }

                add(
                    CtsCard(
                        id = cardId,
                        badgePrimary = entry.optString(
                            "badge_primary",
                            entry.optString("uploaded"),
                        ),
                        badgeSecondary = entry.optString(
                            "badge_secondary",
                            entry.optString("badge_label"),
                        ),
                        title = entry.optString("title"),
                        description = entry.optString("description"),
                        imageSubcard = ImageSubcard(
                            id = imageObject
                                ?.optString("id")
                                ?.takeIf { it.isNotBlank() }
                                ?: UUID.randomUUID().toString(),
                            parentCardId = cardId,
                            source = imageSource,
                            transform = transform,
                        ),
                    ),
                )
            }
        }

        val android = root.optJSONObject("android")
        return CtsProject(
            name = root.optString("name", "Imported comparison"),
            model = VisualModel.fromId(
                settings.optString("model_id", root.optString("model")),
            ),
            cards = cards,
            showHexagons = settings.optBoolean(
                "show_hexagons",
                root.optBoolean("show_hexagons", true),
            ),
            customDurationSeconds = settings
                .optDouble("custom_duration", Double.NaN)
                .takeIf { !it.isNaN() }
                ?.toFloat(),
            soundtrack = decodeSoundtrack(root, android, settings),
        )
    }

    private fun decodeSoundtrack(
        root: JSONObject,
        android: JSONObject?,
        settings: JSONObject,
    ): CtsSoundtrack? {
        val androidTrack = android?.optJSONObject("soundtrack")
        if (androidTrack != null) {
            val source = androidTrack.optString("source").takeIf { it.isNotBlank() }
                ?: return null
            return CtsSoundtrack(
                source = source,
                displayName = androidTrack.optString("display_name", "Soundtrack"),
                volume = androidTrack.optDouble("volume", 1.0).toFloat(),
                loop = androidTrack.optBoolean("loop", true),
            ).normalized()
        }

        val firstTrack = root.optJSONArray("audio_tracks")?.optJSONObject(0) ?: return null
        val source = firstTrack.optString("source").takeIf { it.isNotBlank() }
            ?: firstTrack.optString("path").takeIf { it.isNotBlank() }
            ?: firstTrack.optString("file").takeIf { it.isNotBlank() }
            ?: return null
        return CtsSoundtrack(
            source = source,
            displayName = firstTrack.optString("name", "Soundtrack"),
            volume = firstTrack.optDouble(
                "volume",
                settings.optDouble("soundtrack_master_volume", 1.0),
            ).toFloat(),
            loop = firstTrack.optBoolean("loop", true),
        ).normalized()
    }

    private fun JSONArray.toRect(): NormalizedRect? {
        if (length() != 4) return null
        return NormalizedRect(
            x = optDouble(0, 0.0).toFloat(),
            y = optDouble(1, 0.0).toFloat(),
            width = optDouble(2, 1.0).toFloat(),
            height = optDouble(3, 1.0).toFloat(),
        )
    }
}
