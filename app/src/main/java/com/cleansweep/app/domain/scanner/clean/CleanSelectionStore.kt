package com.cleansweep.app.domain.scanner.clean

import com.cleansweep.app.domain.model.Suggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is marked for deletion right now, shared by every screen that can mark something: the Suggestions Hub, a
 * group of duplicates and the preview. It lives as long as the app, so marking survives going from one screen
 * to the next.
 */
class CleanSelectionStore {

    private val _selection = MutableStateFlow(CleanSelection.EMPTY)

    val selection: StateFlow<CleanSelection> = _selection.asStateFlow()

    fun set(
        suggestion: Suggestion,
        selected: Boolean,
    ) {
        _selection.update { it.set(suggestion, selected) }
    }

    fun setAll(
        suggestions: Iterable<Suggestion>,
        selected: Boolean,
    ) {
        _selection.update { it.setAll(suggestions, selected) }
    }

    fun forget(
        fileIds: Collection<Long>,
    ) {
        _selection.update { it.forget(fileIds) }
    }
}
