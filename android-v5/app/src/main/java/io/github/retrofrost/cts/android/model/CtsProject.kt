package io.github.retrofrost.cts.android.model

import com.retrofrost.cubicalcompare.v5.StudioModel

/** Compatibility model for the original Renderer3 player, mapped from the V5 .ccv5 project. */
data class ImageSubcard(val source: String? = null)
data class CtsCard(
    val badgePrimary: String = "",
    val badgeSecondary: String = "",
    val badgeHeader: String = "",
    val title: String = "",
    val description: String = "",
    val imageSubcard: ImageSubcard = ImageSubcard()
)
data class CtsProject(val cards: List<CtsCard>, val showHexagons: Boolean = true)

fun fromV5(project: StudioModel.Project): CtsProject =
    CtsProject(project.Cards.map { c ->
        val source = c.ArtworkBase64?.takeIf { it.isNotBlank() }?.let { "data:image/png;base64,$it" }
        CtsCard(
            badgePrimary = c.Badge ?: "",
            badgeSecondary = c.BadgeUnit ?: "",
            badgeHeader = c.BadgeHeader ?: "",
            title = c.Title ?: "",
            description = c.Description ?: "",
            imageSubcard = ImageSubcard(source = source)
        )
    })
