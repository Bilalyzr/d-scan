package com.cleansweep.app.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences

/** Allowed range for the large-file threshold: 10 MB to 10 GB. */
private const val MIN_LARGE_FILE_THRESHOLD_BYTES: Long = 10L * 1024 * 1024
private const val MAX_LARGE_FILE_THRESHOLD_BYTES: Long = 10L * 1024 * 1024 * 1024

/** Allowed range for the "untouched for" window: 1 day to 10 years. */
private const val MIN_LARGE_FILE_STALE_DAYS: Int = 1
private const val MAX_LARGE_FILE_STALE_DAYS: Int = 10 * 365

private object PreferenceKeys {
    val MIN_FILE_SIZE_BYTES = longPreferencesKey("min_file_size_bytes")
    val LARGE_FILE_THRESHOLD_BYTES = longPreferencesKey("large_file_threshold_bytes")
    val LARGE_FILE_STALE_DAYS = intPreferencesKey("large_file_stale_days")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val BACKGROUND_INDEXING = booleanPreferencesKey("background_indexing")
}

/**
 * Translates between the stored preferences and [UserPreferences].
 *
 * Pure and I/O free, so the defaults, the clamping and the recovery from unknown values are tested directly.
 * Whatever comes out of here is a valid [UserPreferences]: missing keys fall back to the PRD defaults, values
 * outside the allowed ranges are clamped in both directions, and an unknown theme name follows the system.
 */
internal object UserPreferencesCodec {

    fun Preferences.toUserPreferences(): UserPreferences {
        val defaults = UserPreferences()
        return UserPreferences(
            minFileSizeBytes = this[PreferenceKeys.MIN_FILE_SIZE_BYTES] ?: defaults.minFileSizeBytes,
            largeFileThresholdBytes = this[PreferenceKeys.LARGE_FILE_THRESHOLD_BYTES]
                ?: defaults.largeFileThresholdBytes,
            largeFileStaleDays = this[PreferenceKeys.LARGE_FILE_STALE_DAYS] ?: defaults.largeFileStaleDays,
            themeMode = this[PreferenceKeys.THEME_MODE]
                ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
                ?: defaults.themeMode,
            backgroundIndexing = this[PreferenceKeys.BACKGROUND_INDEXING] ?: defaults.backgroundIndexing,
        ).normalized()
    }

    /** Writes a complete, clamped snapshot, which makes it safe to call with values from any source. */
    fun UserPreferences.toPreferences(): Preferences {
        val normalized = normalized()
        return mutablePreferencesOf().apply {
            this[PreferenceKeys.MIN_FILE_SIZE_BYTES] = normalized.minFileSizeBytes
            this[PreferenceKeys.LARGE_FILE_THRESHOLD_BYTES] = normalized.largeFileThresholdBytes
            this[PreferenceKeys.LARGE_FILE_STALE_DAYS] = normalized.largeFileStaleDays
            this[PreferenceKeys.THEME_MODE] = normalized.themeMode.name
            this[PreferenceKeys.BACKGROUND_INDEXING] = normalized.backgroundIndexing
        }
    }

    /** Keeps the numbers inside the ranges the detectors rely on. */
    private fun UserPreferences.normalized(): UserPreferences = copy(
        minFileSizeBytes = minFileSizeBytes.coerceAtLeast(0L),
        largeFileThresholdBytes = largeFileThresholdBytes.coerceIn(
            MIN_LARGE_FILE_THRESHOLD_BYTES,
            MAX_LARGE_FILE_THRESHOLD_BYTES,
        ),
        largeFileStaleDays = largeFileStaleDays.coerceIn(MIN_LARGE_FILE_STALE_DAYS, MAX_LARGE_FILE_STALE_DAYS),
    )
}
