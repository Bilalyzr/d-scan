package com.cleansweep.app.data.mapper

import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.local.db.projection.SuggestionWithFile
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionDraft

private const val REASON_SEPARATOR = ","

fun SuggestionItemEntity.toDomain(file: IndexedFile): Suggestion = Suggestion(
    fileId = fileId,
    category = category,
    confidenceScore = confidenceScore,
    primaryReason = reasonCode,
    reasons = reasonCodes.split(REASON_SEPARATOR).map(ReasonCode::valueOf),
    isDismissed = isDismissed,
    file = file,
)

fun SuggestionWithFile.toDomain(): Suggestion = suggestion.toDomain(file.toDomain())

fun SuggestionDraft.toEntity(isDismissed: Boolean): SuggestionItemEntity = SuggestionItemEntity(
    fileId = fileId,
    category = category,
    confidenceScore = confidenceScore,
    reasonCode = primaryReason,
    reasonCodes = reasons.joinToString(REASON_SEPARATOR),
    isDismissed = isDismissed,
)
