package com.cleansweep.app.di

import android.content.Context
import com.cleansweep.app.data.scanner.AndroidDeviceStateReader
import com.cleansweep.app.data.scanner.AndroidHardLinkReader
import com.cleansweep.app.data.scanner.AndroidInstalledApkOracle
import com.cleansweep.app.data.scanner.AndroidMimeTypeResolver
import com.cleansweep.app.data.scanner.AndroidStorageAccess
import com.cleansweep.app.data.scanner.AndroidStorageRootsProvider
import com.cleansweep.app.data.scanner.NioFileMetadataReader
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import com.cleansweep.app.domain.scanner.HardLinkReader
import com.cleansweep.app.domain.scanner.ScanController
import com.cleansweep.app.domain.scanner.ScanRunner
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.SuggestionGuard
import com.cleansweep.app.domain.scanner.duplicates.DuplicatePipeline
import com.cleansweep.app.domain.scanner.hashing.DeviceStateReader
import com.cleansweep.app.domain.scanner.hashing.FileHasher
import com.cleansweep.app.domain.scanner.hashing.StreamingFileHasher
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import com.cleansweep.app.domain.scanner.walker.FileMetadataReader
import com.cleansweep.app.domain.scanner.walker.FileWalker
import com.cleansweep.app.domain.scanner.walker.MimeTypeResolver
import com.cleansweep.app.domain.scanner.walker.ScanExclusions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/** The dispatcher for blocking disk work: reading folders and files. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

/** The dispatcher for the CPU side of hashing. Never used for blocking I/O. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class HashingDispatcher

/** A scope that lives as long as the app, for work that must outlive a screen: a running scan. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @HashingDispatcher
    fun provideHashingDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @HashingDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}

/**
 * The scanning engine and its Android adapters.
 *
 * The engine itself (`domain.scanner`) is plain Kotlin without a line of Android or of Hilt. Everything here
 * only supplies it with the platform: folders, package names, battery state, dispatchers.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScannerModule {

    @Provides
    @Singleton
    fun provideFileMetadataReader(): FileMetadataReader = NioFileMetadataReader()

    @Provides
    @Singleton
    fun provideMimeTypeResolver(): MimeTypeResolver = AndroidMimeTypeResolver()

    @Provides
    @Singleton
    fun provideFileWalker(
        metadata: FileMetadataReader,
        mimeTypes: MimeTypeResolver,
    ): FileWalker = FileWalker(metadata, mimeTypes, ScanExclusions())

    @Provides
    @Singleton
    fun provideFileHasher(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @HashingDispatcher hashingDispatcher: CoroutineDispatcher,
    ): FileHasher = StreamingFileHasher(ioDispatcher, hashingDispatcher)

    @Provides
    @Singleton
    fun provideStorageRootsProvider(
        @ApplicationContext context: Context,
    ): StorageRootsProvider = AndroidStorageRootsProvider(context)

    @Provides
    @Singleton
    fun provideStorageAccess(
        @ApplicationContext context: Context,
    ): StorageAccess = AndroidStorageAccess(context)

    @Provides
    @Singleton
    fun provideDeviceStateReader(
        @ApplicationContext context: Context,
    ): DeviceStateReader = AndroidDeviceStateReader(context)

    @Provides
    @Singleton
    fun provideInstalledApkOracle(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): InstalledApkOracle = AndroidInstalledApkOracle(context, ioDispatcher)

    @Provides
    @Singleton
    fun provideHardLinkReader(): HardLinkReader = AndroidHardLinkReader()

    @Provides
    @Singleton
    fun provideSuggestionGuard(
        hardLinks: HardLinkReader,
    ): SuggestionGuard = SuggestionGuard(hardLinks)

    @Provides
    @Singleton
    fun provideDuplicatePipeline(
        hasher: FileHasher,
        fileIndex: FileIndexRepository,
    ): DuplicatePipeline = DuplicatePipeline(hasher, fileIndex)

    @Provides
    @Singleton
    fun provideScanRunner(
        rootsProvider: StorageRootsProvider,
        walker: FileWalker,
        fileIndex: FileIndexRepository,
        whitelist: WhitelistRepository,
        suggestions: SuggestionRepository,
        pipeline: DuplicatePipeline,
        guard: SuggestionGuard,
        installedApks: InstalledApkOracle,
        settings: SettingsRepository,
        deviceState: DeviceStateReader,
        clock: Clock,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ): ScanRunner = ScanRunner(
        rootsProvider = rootsProvider,
        walker = walker,
        fileIndex = fileIndex,
        whitelist = whitelist,
        suggestionRepository = suggestions,
        pipeline = pipeline,
        guard = guard,
        installedApks = installedApks,
        settings = settings,
        deviceState = deviceState,
        clock = clock,
        ioDispatcher = ioDispatcher,
    )

    @Provides
    @Singleton
    fun provideScanController(
        runner: ScanRunner,
        storageAccess: StorageAccess,
        @ApplicationScope scope: CoroutineScope,
    ): ScanController = ScanController(runner, storageAccess, scope)
}
