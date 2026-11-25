package com.cleansweep.app.data.repository

import androidx.room.withTransaction
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY
import com.cleansweep.app.data.local.db.dao.SuggestionItemDao
import com.cleansweep.app.data.mapper.toDomain
import com.cleansweep.app.data.mapper.toEntity
import com.cleansweep.app.domain.model.Suggestion
import com.cleansweep.app.domain.model.SuggestionCategory
import com.cleansweep.app.domain.model.SuggestionCategoryTotal
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.model.TierTotal
import com.cleansweep.app.domain.repository.SuggestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuggestionRepositoryImpl @Inject constructor(
    private val database: CleanSweepDatabase,
    private val suggestionItemDao: SuggestionItemDao,
) : SuggestionRepository {

    override fun observeSuggestions(
        category: SuggestionCategory?,
        includeDismissed: Boolean,
    ): Flow<List<Suggestion>> =
        suggestionItemDao.observeWithFile(category, includeDismissed).map { rows -> rows.map { it.toDomain() } }

    override fun observeTierTotals(): Flow<List<TierTotal>> =
        suggestionItemDao.observeTierTotals().map { rows ->
            rows.map { TierTotal(it.tier, it.itemCount, it.totalBytes) }
        }

    override fun observeCategoryTotals(): Flow<List<SuggestionCategoryTotal>> =
        suggestionItemDao.observeCategoryTotals().map { rows ->
            rows.map { SuggestionCategoryTotal(it.category, it.itemCount, it.totalBytes) }
        }

    override fun observeActiveCount(): Flow<Int> = suggestionItemDao.observeActiveCount()

    override suspend fun replaceAll(suggestions: List<SuggestionDraft>) {
        database.withTransaction {
            val dismissedFileIds = suggestionItemDao.getDismissedFileIds().toSet()
            suggestionItemDao.deleteAll()
            if (suggestions.isNotEmpty()) {
                suggestionItemDao.upsertAll(
                    suggestions.map { it.toEntity(isDismissed = it.fileId in dismissedFileIds) },
                )
            }
        }
    }

    override suspend fun setDismissed(fileIds: Collection<Long>, dismissed: Boolean) {
        database.withTransaction {
            fileIds.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).forEach {
                suggestionItemDao.setDismissed(it, dismissed)
            }
        }
    }

    override suspend fun deleteForFiles(fileIds: Collection<Long>) {
        database.withTransaction {
            fileIds.distinct().chunked(MAX_BIND_ARGS_PER_QUERY).forEach {
                suggestionItemDao.deleteByFileIds(it)
            }
        }
    }

    override suspend fun getActiveFileIds(fileIds: Collection<Long>): Set<Long> =
        fileIds.distinct()
            .chunked(MAX_BIND_ARGS_PER_QUERY)
            .flatMap { suggestionItemDao.getActiveFileIds(it) }
            .toSet()

    override suspend fun clearSuggestions() {
        suggestionItemDao.deleteAll()
    }
}
