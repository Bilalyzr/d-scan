package com.cleansweep.app.di

import android.content.Context
import androidx.room.Room
import com.cleansweep.app.data.local.db.CleanSweepDatabase
import com.cleansweep.app.data.local.db.dao.FileIndexDao
import com.cleansweep.app.data.local.db.dao.SuggestionItemDao
import com.cleansweep.app.data.local.db.dao.TrashManifestDao
import com.cleansweep.app.data.local.db.dao.WhitelistRuleDao
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * There is deliberately no `fallbackToDestructiveMigration`. Silently rebuilding the database would orphan
     * every file in Safe Trash and drop the user's whitelist. A schema change therefore has to ship an explicit
     * migration (and a migration test) instead.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CleanSweepDatabase =
        Room.databaseBuilder(context, CleanSweepDatabase::class.java, CleanSweepDatabase.NAME)
            .build()

    @Provides
    fun provideFileIndexDao(database: CleanSweepDatabase): FileIndexDao = database.fileIndexDao()

    @Provides
    fun provideSuggestionItemDao(database: CleanSweepDatabase): SuggestionItemDao = database.suggestionItemDao()

    @Provides
    fun provideWhitelistRuleDao(database: CleanSweepDatabase): WhitelistRuleDao = database.whitelistRuleDao()

    @Provides
    fun provideTrashManifestDao(database: CleanSweepDatabase): TrashManifestDao = database.trashManifestDao()

    /** Injected so that time-dependent logic (trash expiry, rule creation) can be tested with a fixed clock. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
