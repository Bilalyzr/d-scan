package com.cleansweep.app.data.scanner

import com.cleansweep.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the daily background scan in step with the setting, from the moment the app starts.
 *
 * The setting is the only place the schedule is decided: the app applies it at every start, and again whenever
 * the user changes it. If the settings cannot be read, the background scan is switched off, which is the safe
 * side of a setting that costs battery.
 */
class BackgroundIndexing(
    private val settings: SettingsRepository,
    private val scheduler: BackgroundIndexingScheduler,
) {

    @Volatile
    private var started: Boolean = false

    /** Starts following the setting in [scope], which should live as long as the app. Calling it twice changes nothing. */
    fun start(
        scope: CoroutineScope,
    ) {
        if (started) {
            return
        }
        started = true
        scope.launch {
            settings.preferences
                .map { it.backgroundIndexing }
                .distinctUntilChanged()
                .catch { emit(false) }
                .collect { enabled -> scheduler.setEnabled(enabled) }
        }
    }
}
