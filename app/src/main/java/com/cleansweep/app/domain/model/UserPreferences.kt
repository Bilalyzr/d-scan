package com.cleansweep.app.domain.model

/** User-selected theme for the app's own UI (Settings, PRD screen 8). Stored by name. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * User-configurable settings (PRD screens 4.3 and 8). Persisted in DataStore, not Room.
 *
 * @property minFileSizeBytes Files smaller than this are ignored by indexing and suggestions.
 * @property largeFileThresholdBytes Size above which an untouched file counts as "large forgotten" (default 50 MB).
 * @property largeFileStaleDays Days without modification after which a large file counts as forgotten (default 90).
 */
data class UserPreferences(
    val minFileSizeBytes: Long = 0L,
    val largeFileThresholdBytes: Long = 50L * 1024 * 1024,
    val largeFileStaleDays: Int = 90,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)
