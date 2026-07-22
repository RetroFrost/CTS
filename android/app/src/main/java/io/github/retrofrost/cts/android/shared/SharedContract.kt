package io.github.retrofrost.cts.android.shared

/** Generated from shared/cts_contract.json. Do not edit by hand. */
object SharedContract {
    const val CONTRACT_VERSION = 1
    const val PROJECT_VERSION = 3
    const val MODEL_ID = "illustrated_cards"
    const val MODEL_LABEL = "Reference Timeline"
    const val VISIBLE_CARDS = 4

    val LEGACY_MODEL_IDS = setOf("illustrated_cards", "reference_detail", "classic_compact")
    val FIELDS = listOf("badge_primary", "badge_secondary", "title", "description", "image")

    const val REVEAL_SECONDS = 2.0f
    const val SCROLL_SECONDS = 3.3333333333333335f
    const val END_HOLD_SECONDS = 2.0f
    const val FADE_SECONDS = 0.8f
    const val BODY_WIPE_SECONDS = 1.1f
    const val BADGE_DELAY_SECONDS = 0.55f
    const val BADGE_SETTLE_SECONDS = 0.7f
    const val INTRO_TAIL_HOLD_SECONDS = 0.8f

    const val MATERIAL_EASE_X1 = 0.4f
    const val MATERIAL_EASE_Y1 = 0.0f
    const val MATERIAL_EASE_X2 = 0.2f
    const val MATERIAL_EASE_Y2 = 1.0f

    const val IMAGE_X = 0.008f
    const val IMAGE_Y = 0.0f
    const val IMAGE_WIDTH = 0.984f
    const val IMAGE_HEIGHT = 0.807f
    const val TITLE_X = 0.008f
    const val TITLE_Y = 0.807f
    const val TITLE_WIDTH = 0.984f
    const val TITLE_HEIGHT = 0.088f
    const val DESCRIPTION_X = 0.008f
    const val DESCRIPTION_Y = 0.895f
    const val DESCRIPTION_WIDTH = 0.984f
    const val DESCRIPTION_HEIGHT = 0.101f
    const val BADGE_X = 0.245f
    const val BADGE_Y = 0.063f
    const val BADGE_WIDTH = 0.51f
    const val BADGE_HEIGHT = 0.263f

    const val COLOR_BACKGROUND = "#000000"
    const val COLOR_IMAGE_TOP = "#138DDB"
    const val COLOR_IMAGE_BOTTOM = "#0B74BE"
    const val COLOR_TITLE_BACKGROUND = "#F0F0F0"
    const val COLOR_TITLE_TEXT = "#101010"
    const val COLOR_DESCRIPTION_BACKGROUND = "#625F56"
    const val COLOR_DESCRIPTION_TEXT = "#FFFFFF"
    const val COLOR_DIVIDER = "#11100C"
    const val COLOR_BADGE_TOP = "#EB0909"
    const val COLOR_BADGE_MIDDLE = "#E00000"
    const val COLOR_BADGE_BOTTOM = "#D50000"
    const val COLOR_BADGE_BORDER = "#FF4545"
}

data class SharedSampleCard(
    val badgePrimary: String,
    val badgeSecondary: String,
    val title: String,
    val description: String,
)

val SHARED_SAMPLE_CARDS = listOf(
    SharedSampleCard(
        badgePrimary = "10",
        badgeSecondary = "SECONDS OLD",
        title = "Breathing",
        description = "A baby's first breath requires blood flow through the heart.",
    ),
    SharedSampleCard(
        badgePrimary = "1",
        badgeSecondary = "HOUR OLD",
        title = "Suckling",
        description = "Newborns instinctively try to feed within just hours.",
    ),
    SharedSampleCard(
        badgePrimary = "3",
        badgeSecondary = "DAYS OLD",
        title = "Recognizing Mom's Smell",
        description = "Within days a baby can recognize a familiar scent.",
    ),
    SharedSampleCard(
        badgePrimary = "6.5",
        badgeSecondary = "MONTHS OLD",
        title = "Recognizing Their Own Name",
        description = "A baby turns toward their name months before speaking.",
    ),
    SharedSampleCard(
        badgePrimary = "8",
        badgeSecondary = "MONTHS OLD",
        title = "Object Permanence",
        description = "Objects still exist even when they are out of sight.",
    ),
)
