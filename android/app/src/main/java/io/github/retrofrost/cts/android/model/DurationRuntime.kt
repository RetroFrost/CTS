package io.github.retrofrost.cts.android.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Restores the CTS Easy video-length behavior without splitting preview and export timing.
 *
 * The screen may choose automatic timing or a custom target duration. TimelineEngine reads
 * this state immediately so the preview changes at once. CtsProject.normalized() then folds
 * the choice into the project before saving or queuing a background export, which makes the
 * selected length survive process death and remain compatible with desktop CTS projects.
 */
enum class DurationChoice {
    Project,
    Automatic,
    Custom,
}

object DurationRuntime {
    private const val MINIMUM_SECONDS = 1f
    private const val MAXIMUM_SECONDS = 24f * 60f * 60f

    var choice by mutableStateOf(DurationChoice.Project)
        private set

    var customSeconds by mutableFloatStateOf(60f)
        private set

    var projectCustomSeconds by mutableStateOf<Float?>(null)
        private set

    fun useAutomatic() {
        choice = DurationChoice.Automatic
    }

    fun useCustom(seconds: Float) {
        customSeconds = sanitize(seconds) ?: MINIMUM_SECONDS
        choice = DurationChoice.Custom
    }

    fun useProjectSetting() {
        choice = DurationChoice.Project
    }

    /** Resolve timing for live preview without mutating the project. */
    fun resolve(projectValue: Float?): Float? = when (choice) {
        DurationChoice.Project -> sanitize(projectValue)
        DurationChoice.Automatic -> null
        DurationChoice.Custom -> sanitize(customSeconds)
    }

    /**
     * Resolve and persist timing while a project is normalized for editing, saving, or export.
     */
    fun normalizeProjectValue(projectValue: Float?): Float? {
        val safeProjectValue = sanitize(projectValue)
        if (choice == DurationChoice.Project) {
            projectCustomSeconds = safeProjectValue
            if (safeProjectValue != null) customSeconds = safeProjectValue
        }
        return when (choice) {
            DurationChoice.Project -> safeProjectValue
            DurationChoice.Automatic -> null
            DurationChoice.Custom -> sanitize(customSeconds)
        }
    }

    fun effectiveCustomSeconds(): Float? = when (choice) {
        DurationChoice.Project -> projectCustomSeconds
        DurationChoice.Automatic -> null
        DurationChoice.Custom -> sanitize(customSeconds)
    }

    internal fun resetForTests() {
        projectCustomSeconds = null
        customSeconds = 60f
        choice = DurationChoice.Project
    }

    private fun sanitize(value: Float?): Float? = value
        ?.takeIf { it.isFinite() }
        ?.coerceIn(MINIMUM_SECONDS, MAXIMUM_SECONDS)
}
