package com.cleansweep.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.dao.SuggestionItemDao
import com.cleansweep.app.data.local.db.dao.TrashManifestDao
import com.cleansweep.app.data.local.db.dao.WhitelistRuleDao
import com.cleansweep.app.data.local.db.entity.FileIndexEntity
import com.cleansweep.app.data.local.db.entity.SuggestionItemEntity
import com.cleansweep.app.data.local.db.entity.TrashManifestEntity
import com.cleansweep.app.data.local.db.entity.WhitelistRuleEntity

/**
 * The app's only persistent store of scanned data. Exactly the four tables from PRD section 6.
 *
 * Schema versions are exported to `app/schemas` by the Room Gradle plugin and must be committed. A schema change
 * means bumping the `version` literal below, a committed migration and a migration test; destructive migration is
 * never allowed (see DatabaseModule).
 */
@Database(
    entities = [
        FileIndexEntity::class,
        SuggestionItemEntity::class,
        WhitelistRuleEntity::class,
        TrashManifestEntity::class,
    ],
    // Bump only together with a migration: the exported schema file in app/schemas is named after this number.
    version = 1,
    exportSchema = true,
)
abstract class CleanSweepDatabase : RoomDatabase() {

    abstract fun fileIndexDao(): FileIndexDao

    abstract fun suggestionItemDao(): SuggestionItemDao

    abstract fun whitelistRuleDao(): WhitelistRuleDao

    abstract fun trashManifestDao(): TrashManifestDao

    companion object {
        const val NAME: String = "cleansweep.db"
    }
}
