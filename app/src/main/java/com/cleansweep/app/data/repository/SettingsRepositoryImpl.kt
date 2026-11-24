package com.cleansweep.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.cleansweep.app.data.repository.UserPreferencesCodec.toPreferences
import com.cleansweep.app.data.repository.UserPreferencesCodec.toUserPreferences
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import com.cleansweep.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val preferences: Flow<UserPreferences> = dataStore.data
        .catch { error ->
            // A corrupt preferences file must never take the app down: fall back to the defaults.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { it.toUserPreferences() }

    override suspend fun current(): UserPreferences = preferences.first()

    override suspend fun setMinFileSizeBytes(bytes: Long) = update { it.copy(minFileSizeBytes = bytes) }

    override suspend fun setLargeFileThresholdBytes(bytes: Long) =
        update { it.copy(largeFileThresholdBytes = bytes) }

    override suspend fun setLargeFileStaleDays(days: Int) = update { it.copy(largeFileStaleDays = days) }

    override suspend fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    /**
     * One read-modify-write per change, so the other settings are never clobbered. The codec clamps the values on
     * the way in.
     */
    private suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.updateData { stored -> transform(stored.toUserPreferences()).toPreferences() }
    }
}
