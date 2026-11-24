package com.cleansweep.app.di

import com.cleansweep.app.data.repository.FileIndexRepositoryImpl
import com.cleansweep.app.data.repository.SettingsRepositoryImpl
import com.cleansweep.app.data.repository.SuggestionRepositoryImpl
import com.cleansweep.app.data.repository.TrashRepositoryImpl
import com.cleansweep.app.data.repository.WhitelistRepositoryImpl
import com.cleansweep.app.domain.repository.FileIndexRepository
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.repository.SuggestionRepository
import com.cleansweep.app.domain.repository.TrashRepository
import com.cleansweep.app.domain.repository.WhitelistRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFileIndexRepository(implementation: FileIndexRepositoryImpl): FileIndexRepository

    @Binds
    @Singleton
    abstract fun bindSuggestionRepository(implementation: SuggestionRepositoryImpl): SuggestionRepository

    @Binds
    @Singleton
    abstract fun bindWhitelistRepository(implementation: WhitelistRepositoryImpl): WhitelistRepository

    @Binds
    @Singleton
    abstract fun bindTrashRepository(implementation: TrashRepositoryImpl): TrashRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(implementation: SettingsRepositoryImpl): SettingsRepository
}
