package com.cleansweep.app.ui.bootstrap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cleansweep.app.domain.model.CategoryTotal
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.SuggestionTier
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class BootstrapUiState(
    val indexedFiles: Int = 0,
    val categoryTotals: Map<FileCategory, CategoryTotal> = emptyMap(),
    val safeToClean: TierTotal? = null,
    val reviewRecommended: TierTotal? = null,
    val whitelistRules: Int = 0,
    val trashEntries: Int = 0,
)

/**
 * Temporary. It exists only to prove that the Phase 1 graph works end to end
 * (ViewModel -> repositories -> Room) and is replaced by the real dashboard in Phase 4.
 */
@HiltViewModel
class BootstrapViewModel @Inject constructor(
    fileIndexRepository: FileIndexRepository,
    suggestionRepository: SuggestionRepository,
    whitelistRepository: WhitelistRepository,
    trashRepository: TrashRepository,
) : ViewModel() {

    val uiState: StateFlow<BootstrapUiState> = combine(
        fileIndexRepository.observeFileCount(),
        fileIndexRepository.observeCategoryTotals(),
        suggestionRepository.observeTierTotals(),
        whitelistRepository.observeRuleCount(),
        trashRepository.observeEntryCount(),
    ) { indexedFiles, categoryTotals, tierTotals, whitelistRules, trashEntries ->
        BootstrapUiState(
            indexedFiles = indexedFiles,
            categoryTotals = categoryTotals.associateBy { it.category },
            safeToClean = tierTotals.firstOrNull { it.tier == SuggestionTier.SAFE_TO_CLEAN },
            reviewRecommended = tierTotals.firstOrNull { it.tier == SuggestionTier.REVIEW_RECOMMENDED },
            whitelistRules = whitelistRules,
            trashEntries = trashEntries,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BootstrapUiState(),
    )
}
