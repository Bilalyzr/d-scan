package com.cleansweep.app.domain.scanner.junk

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.UserPreferences

/** One detector's verdict on one file: what it is, and the signals that say so. */
data class Finding(
    val file: IndexedFile,
    val category: SuggestionCategory,
    /** Starts with the signal that defines the category. Never empty. */
    val reasons: List<ReasonCode>,
)

/** What the detectors need to know about the moment of the scan. */
data class DetectionContext(
    /** Now, in epoch millis. Injected so that age rules can be tested at any date. */
    val now: Long,
    val settings: UserPreferences,
)
