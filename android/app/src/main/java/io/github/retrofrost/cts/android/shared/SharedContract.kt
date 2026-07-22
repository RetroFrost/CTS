package io.github.retrofrost.cts.android.shared

/** Generated from shared/cts_contract.json. Do not edit by hand. */
object SharedContract {
    const val CONTRACT_VERSION = 1
    const val PROJECT_VERSION = 3
    const val MODEL_ID = "illustrated_cards"
    const val MODEL_LABEL = "Reference Timeline"
    const val VISIBLE_CARDS = 4

    const val REVEAL_SECONDS = 2f
    const val SCROLL_SECONDS = 3.3333333f
    const val END_HOLD_SECONDS = 2f
    const val FADE_SECONDS = 0.8f
    const val BODY_WIPE_SECONDS = 1.1f
    const val BADGE_DELAY_SECONDS = 0.55f
    const val BADGE_SETTLE_SECONDS = 2.6f
    const val INTRO_TAIL_HOLD_SECONDS = 0.8f

    const val MATERIAL_EASE_X1 = 0.4f
    const val MATERIAL_EASE_Y1 = 0f
    const val MATERIAL_EASE_X2 = 0.2f
    const val MATERIAL_EASE_Y2 = 1f
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
