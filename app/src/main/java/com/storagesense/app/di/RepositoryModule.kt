package com.storagesense.app.di

import com.storagesense.app.action.ActionEngine
import com.storagesense.app.data.repository.FileRepositoryImpl
import com.storagesense.app.data.repository.SearchRepositoryImpl
import com.storagesense.app.domain.repository.ActionRepository
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.domain.repository.SearchRepository
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
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindActionRepository(impl: ActionEngine): ActionRepository
}
