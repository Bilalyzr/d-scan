package com.cleansweep.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * I/O tests for the settings store. Defaults, clamping and recovery are covered by [UserPreferencesCodecTest].
 *
 * Each test writes at most once. DataStore replaces its file with `File.renameTo`, which on some Windows setups
 * (including the one this project was built on) cannot overwrite an existing file, so a second write in a JVM test
 * would fail there. On Android the rename replaces the file as usual.
 */
class SettingsRepositoryImplTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepositoryImpl

    @Before
    fun setUp() {
        // A real dispatcher: DataStore runs its own actor, which the virtual time of runTest would never advance.
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(temporaryFolder.root, "test.preferences_pb")
        }
        repository = SettingsRepositoryImpl(dataStore)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `an untouched store reports the PRD defaults`() = runTest {
        assertEquals(UserPreferences(), repository.current())
    }

    @Test
    fun `a change is persisted, emitted to observers and leaves the other settings alone`() = runTest {
        repository.setLargeFileThresholdBytes(200L * 1024 * 1024)

        val saved = repository.current()
        assertEquals(200L * 1024 * 1024, saved.largeFileThresholdBytes)
        assertEquals(UserPreferences().largeFileStaleDays, saved.largeFileStaleDays)
        assertEquals(200L * 1024 * 1024, repository.preferences.first().largeFileThresholdBytes)
    }

    @Test
    fun `a value out of range is clamped before it is stored`() = runTest {
        repository.setLargeFileStaleDays(0)

        assertEquals(1, repository.current().largeFileStaleDays)
    }

    @Test
    fun `an unknown stored theme follows the system`() = runTest {
        dataStore.edit { it[stringPreferencesKey("theme_mode")] = "A_THEME_REMOVED_IN_A_LATER_VERSION" }

        assertEquals(ThemeMode.SYSTEM, repository.current().themeMode)
    }
}
