package com.cleansweep.app.domain.scanner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * A gate the scan passes through between entries.
 *
 * A closed gate makes the walk and the hashing wait exactly where they are. Nothing is undone and nothing already
 * indexed is lost, so resuming continues from the entry after the last one.
 */
class PauseGate(
    initiallyOpen: Boolean = true,
) {
    private val state = MutableStateFlow(initiallyOpen)

    val isOpen: Boolean
        get() = state.value

    fun close() {
        state.value = false
    }

    fun open() {
        state.value = true
    }

    /** Returns at once while the gate is open and suspends until [open] otherwise. */
    suspend fun awaitOpen() {
        if (!state.value) {
            state.first { it }
        }
    }
}
