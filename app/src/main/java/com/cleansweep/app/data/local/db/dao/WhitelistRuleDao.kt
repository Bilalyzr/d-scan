package com.cleansweep.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cleansweep.app.data.local.db.entity.WhitelistRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistRuleDao {

    @Query("SELECT * FROM whitelist_rules ORDER BY created_at DESC")
    fun observeAll(): Flow<List<WhitelistRuleEntity>>

    @Query("SELECT COUNT(*) FROM whitelist_rules")
    fun observeCount(): Flow<Int>

    /** Every rule at once; the scanner folds them into a WhitelistMatcher for the duration of a scan. */
    @Query("SELECT * FROM whitelist_rules")
    suspend fun getAll(): List<WhitelistRuleEntity>

    @Query("SELECT * FROM whitelist_rules WHERE path_pattern = :path")
    suspend fun getByPath(path: String): WhitelistRuleEntity?

    /** Returns the new row id. */
    @Insert
    suspend fun insert(rule: WhitelistRuleEntity): Long

    /** Returns the number of rules removed (0 or 1). */
    @Query("DELETE FROM whitelist_rules WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
