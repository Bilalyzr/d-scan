package com.cleansweep.app.domain.repository

import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/** User settings that parameterise the scan, the detectors and the UI. All methods are main-safe. */
interface SettingsRepository {

    /** Emits the current settings and again after every change. Never fails on a corrupt or missing file. */
    val preferences: Flow<UserPreferences>

    /** The current settings, for engines that run once per scan rather than observing changes. */
    suspend fun current(): UserPreferences

    suspend fun setMinFileSizeBytes(bytes: Long)

    suspend fun setLargeFileThresholdBytes(bytes: Long)

    suspend fun setLargeFileStaleDays(days: Int)

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setBackgroundIndexing(enabled: Boolean)
}
