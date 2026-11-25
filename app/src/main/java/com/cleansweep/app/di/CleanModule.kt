package com.cleansweep.app.di

import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.clean.CleanSelectionStore
import com.cleansweep.app.domain.scanner.duplicates.DuplicateGroupEditor
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import javax.inject.Singleton

/** What the screens need to act on what the engine found: what is marked for deletion, and changing the Original. */
@Module
@InstallIn(SingletonComponent::class)
object CleanModule {

    @Provides
    @Singleton
    fun provideCleanSelectionStore(): CleanSelectionStore = CleanSelectionStore()

    @Provides
    @Singleton
    fun provideDuplicateGroupEditor(
        fileIndex: FileIndexRepository,
        suggestions: SuggestionRepository,
        whitelist: WhitelistRepository,
        installedApks: InstalledApkOracle,
        guard: SuggestionGuard,
        settings: SettingsRepository,
        clock: Clock,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): DuplicateGroupEditor = DuplicateGroupEditor(
        fileIndex = fileIndex,
        suggestions = suggestions,
        whitelist = whitelist,
        installedApks = installedApks,
        guard = guard,
        settings = settings,
        clock = clock,
        ioDispatcher = ioDispatcher,
    )
}
