package com.cleansweep.app.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.cleansweep.app.data.repository.UserPreferencesCodec.toPreferences
import com.cleansweep.app.data.repository.UserPreferencesCodec.toUserPreferences
import com.cleansweep.app.domain.model.ThemeMode
import com.cleansweep.app.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesCodecTest {

    private fun stored(fill: MutablePreferences.() -> Unit): Preferences = mutablePreferencesOf().apply(fill)

    private val minFileSize = longPreferencesKey("min_file_size_bytes")
    private val largeThreshold = longPreferencesKey("large_file_threshold_bytes")
    private val staleDays = intPreferencesKey("large_file_stale_days")
    private val themeMode = stringPreferencesKey("theme_mode")

    @Test
    fun `an empty store yields the PRD defaults`() {
        val defaults = mutablePreferencesOf().toUserPreferences()

        assertEquals(UserPreferences(), defaults)
        assertEquals(50L * 1024 * 1024, defaults.largeFileThresholdBytes)
        assertEquals(90, defaults.largeFileStaleDays)
    }

    @Test
    fun `values round-trip through the store`() {
        val expected = UserPreferences(
            minFileSizeBytes = 1_024L,
            largeFileThresholdBytes = 200L * 1024 * 1024,
            largeFileStaleDays = 30,
            themeMode = ThemeMode.DARK,
        )

        assertEquals(expected, expected.toPreferences().toUserPreferences())
    }

    @Test
    fun `a key that was never written falls back to its default without disturbing the others`() {
        val partial = stored { this[minFileSize] = 4_096L }.toUserPreferences()

        assertEquals(4_096L, partial.minFileSizeBytes)
        assertEquals(UserPreferences().largeFileThresholdBytes, partial.largeFileThresholdBytes)
        assertEquals(UserPreferences().largeFileStaleDays, partial.largeFileStaleDays)
        assertEquals(UserPreferences().themeMode, partial.themeMode)
    }

    @Test
    fun `values out of range are clamped when written`() {
        val written = UserPreferences(
            minFileSizeBytes = -5L,
            largeFileThresholdBytes = 1L,
            largeFileStaleDays = 0,
        ).toPreferences()

        assertEquals(0L, written[minFileSize])
        assertEquals(10L * 1024 * 1024, written[largeThreshold])
        assertEquals(1, written[staleDays])
    }

    @Test
    fun `values far above the allowed range are clamped when written`() {
        val written = UserPreferences(
            largeFileThresholdBytes = Long.MAX_VALUE,
            largeFileStaleDays = Int.MAX_VALUE,
        ).toPreferences()

        assertEquals(10L * 1024 * 1024 * 1024, written[largeThreshold])
        assertEquals(10 * 365, written[staleDays])
    }

    @Test
    fun `a value that was somehow stored out of range is clamped on the way back in`() {
        val tampered = stored {
            this[largeThreshold] = -7L
            this[staleDays] = -1
        }.toUserPreferences()

        assertEquals(10L * 1024 * 1024, tampered.largeFileThresholdBytes)
        assertEquals(1, tampered.largeFileStaleDays)
    }

    @Test
    fun `an unknown stored theme follows the system instead of crashing`() {
        val fromOlderVersion = stored { this[themeMode] = "A_THEME_REMOVED_IN_A_LATER_VERSION" }.toUserPreferences()

        assertEquals(ThemeMode.SYSTEM, fromOlderVersion.themeMode)
    }
}
