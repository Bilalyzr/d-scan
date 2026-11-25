package com.cleansweep.app.di

import android.content.Context
import com.cleansweep.app.data.scanner.trash.AndroidMediaScanner
import com.cleansweep.app.data.scanner.trash.NioTrashStorage
import com.cleansweep.app.data.scanner.trash.WorkManagerPurgeScheduler
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.trash.MediaScanner
import com.cleansweep.app.domain.scanner.trash.PurgeScheduler
import com.cleansweep.app.domain.scanner.trash.TrashManager
import com.cleansweep.app.domain.scanner.trash.TrashStorage
import com.cleansweep.app.domain.scanner.whitelist.WhitelistManager
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.time.Clock
import javax.inject.Singleton

/** Safe Trash and the whitelist, with the platform pieces they need. */
@Module
@InstallIn(SingletonComponent::class)
object TrashModule {

    @Provides
    @Singleton
    fun provideTrashStorage(): TrashStorage = NioTrashStorage()

    @Provides
    @Singleton
    fun provideMediaScanner(
        @ApplicationContext context: Context,
    ): MediaScanner = AndroidMediaScanner(context)

    @Provides
    @Singleton
    fun providePurgeScheduler(
        @ApplicationContext context: Context,
    ): PurgeScheduler = WorkManagerPurgeScheduler(context)

    @Provides
    @Singleton
    fun provideTrashManager(
        manifest: TrashRepository,
        fileIndex: FileIndexRepository,
        suggestions: SuggestionRepository,
        whitelist: WhitelistRepository,
        storage: TrashStorage,
        mediaScanner: MediaScanner,
        purgeScheduler: PurgeScheduler,
        clock: Clock,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): TrashManager = TrashManager(
        trash = manifest,
        fileIndex = fileIndex,
        suggestions = suggestions,
        whitelist = whitelist,
        storage = storage,
        mediaScanner = mediaScanner,
        purgeScheduler = purgeScheduler,
        clock = clock,
        ioDispatcher = ioDispatcher,
    )

    @Provides
    @Singleton
    fun provideWhitelistManager(
        whitelist: WhitelistRepository,
        roots: StorageRootsProvider,
        metadata: FileMetadataReader,
    ): WhitelistManager = WhitelistManager(whitelist, roots, metadata)
}
