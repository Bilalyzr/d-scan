package com.cleansweep.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.cleansweep.app.data.local.db.entity.TrashManifestEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashManifestDao {

    @Query("SELECT * FROM trash_manifest ORDER BY deleted_at DESC")
    fun observeAll(): Flow<List<TrashManifestEntity>>

    @Query("SELECT COUNT(*) FROM trash_manifest")
    fun observeCount(): Flow<Int>

    /** Bytes sitting in Safe Trash; `COALESCE` keeps the result non-null when the trash is empty. */
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM trash_manifest")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM trash_manifest WHERE id = :id")
    suspend fun getById(id: Long): TrashManifestEntity?

    @Query("SELECT * FROM trash_manifest WHERE original_path = :originalPath LIMIT 1")
    suspend fun getByOriginalPath(originalPath: String): TrashManifestEntity?

    @Query("SELECT * FROM trash_manifest")
    suspend fun getAll(): List<TrashManifestEntity>

    /** Callers keep each chunk of [ids] within [com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY]. */
    @Query("SELECT * FROM trash_manifest WHERE id IN (:ids)")
    suspend fun getByIds(ids: Collection<Long>): List<TrashManifestEntity>

    @Query("SELECT COUNT(*) FROM trash_manifest")
    suspend fun count(): Int

    /** Items whose retention window has ended and that the purge worker may delete permanently. */
    @Query("SELECT * FROM trash_manifest WHERE expires_at <= :now")
    suspend fun getExpired(now: Long): List<TrashManifestEntity>

    /** Returns the new row id. */
    @Insert
    suspend fun insert(entry: TrashManifestEntity): Long

    /** Callers keep each chunk of [ids] within [com.cleansweep.app.data.local.db.MAX_BIND_ARGS_PER_QUERY]. */
    @Query("DELETE FROM trash_manifest WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>): Int

    /** Clears the manifest without looking at storage. The trash manager never calls this: it forgets each entry only once its file is gone. */
    @Query("DELETE FROM trash_manifest")
    suspend fun deleteAll(): Int
}
