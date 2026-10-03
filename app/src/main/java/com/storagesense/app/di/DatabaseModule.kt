package com.storagesense.app.di

import android.content.Context
import com.storagesense.app.data.local.room.ActionLogDao
import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.ImageIndexDao
import com.storagesense.app.data.local.room.StorageSenseDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StorageSenseDatabase {
        return StorageSenseDatabase.getInstance(context)
    }

    @Provides
    fun provideFileMetadataDao(db: StorageSenseDatabase): FileMetadataDao {
        return db.fileMetadataDao()
    }

    @Provides
    fun provideDocumentChunkDao(db: StorageSenseDatabase): DocumentChunkDao {
        return db.documentChunkDao()
    }

    @Provides
    fun provideImageIndexDao(db: StorageSenseDatabase): ImageIndexDao {
        return db.imageIndexDao()
    }

    @Provides
    fun provideActionLogDao(db: StorageSenseDatabase): ActionLogDao {
        return db.actionLogDao()
    }

    @Provides
    fun provideFaceClusterDao(db: StorageSenseDatabase): com.storagesense.app.data.local.room.FaceClusterDao {
        return db.faceClusterDao()
    }
}
