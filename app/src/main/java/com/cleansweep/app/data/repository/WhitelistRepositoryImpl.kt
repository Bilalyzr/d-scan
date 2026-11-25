package com.cleansweep.app.data.repository

import androidx.room.withTransaction
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.dao.WhitelistRuleDao
import com.cleansweep.app.data.local.db.entity.WhitelistRuleEntity
import com.cleansweep.app.data.mapper.toDomain
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.model.WhitelistRule
import com.cleansweep.app.domain.model.WhitelistRuleType
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhitelistRepositoryImpl @Inject constructor(
    private val database: CleanSweepDatabase,
    private val whitelistRuleDao: WhitelistRuleDao,
    private val fileIndexDao: FileIndexDao,
    private val clock: Clock,
) : WhitelistRepository {

    override fun observeRules(): Flow<List<WhitelistRule>> =
        whitelistRuleDao.observeAll().map { rules -> rules.map { it.toDomain() } }

    override fun observeRuleCount(): Flow<Int> = whitelistRuleDao.observeCount()

    override suspend fun addRule(path: String, type: WhitelistRuleType): WhitelistRule {
        val canonicalPath = StoragePaths.canonicalize(path)
        return database.withTransaction {
            whitelistRuleDao.getByPath(canonicalPath)?.let { return@withTransaction it.toDomain() }

            val rule = WhitelistRuleEntity(
                pathPattern = canonicalPath,
                ruleType = type,
                createdAt = clock.millis(),
            )
            val id = whitelistRuleDao.insert(rule)

            // The rule takes effect immediately. Anything already indexed under the path leaves the index now,
            // and its suggestions go with it through the foreign-key cascade.
            if (type == WhitelistRuleType.FOLDER) {
                fileIndexDao.deleteByPathAndDescendants(canonicalPath, StoragePaths.descendantPrefix(canonicalPath))
            } else {
                fileIndexDao.deleteByPath(canonicalPath)
            }
            rule.copy(id = id).toDomain()
        }
    }

    override suspend fun getRules(): List<WhitelistRule> =
        whitelistRuleDao.getAll().sortedByDescending { it.createdAt }.map { it.toDomain() }

    override suspend fun removeRule(id: Long) {
        whitelistRuleDao.deleteById(id)
    }

    override suspend fun matcher(): WhitelistMatcher =
        WhitelistMatcher.from(whitelistRuleDao.getAll().map { it.toDomain() })

    override suspend fun isWhitelisted(path: String): Boolean =
        matcher().matches(StoragePaths.canonicalize(path))
}
