package com.cleansweep.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory

/**
 * `suggestion_items`: the output of the scoring engine, one row per suggested file.
 *
 * The PRD schema plus one addition: `reason_codes` keeps every signal that contributed to the score, so a score
 * is always explainable without recomputing it. `reason_code` is the primary (highest-weight) reason.
 * `reason_codes` is a comma-separated list of [ReasonCode] names whose first entry equals `reason_code`.
 *
 * The primary key doubles as the foreign key, which guarantees one suggestion per file. Deleting a `file_index`
 * row cascades to its suggestion.
 */
@Entity(
    tableName = "suggestion_items",
    primaryKeys = ["file_id"],
    foreignKeys = [
        ForeignKey(
            entity = FileIndexEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["category"]),
        Index(value = ["is_dismissed", "confidence_score"]),
    ],
)
data class SuggestionItemEntity(
    @ColumnInfo(name = "file_id")
    val fileId: Long,
    val category: SuggestionCategory,
    @ColumnInfo(name = "confidence_score")
    val confidenceScore: Int,
    @ColumnInfo(name = "reason_code")
    val reasonCode: ReasonCode,
    @ColumnInfo(name = "reason_codes")
    val reasonCodes: String,
    @ColumnInfo(name = "is_dismissed")
    val isDismissed: Boolean,
)
