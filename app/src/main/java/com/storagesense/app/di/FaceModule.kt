package com.storagesense.app.di

import com.storagesense.app.ai.face.FaceClusterer
import com.storagesense.app.ai.face.FaceDetectionEngine
import com.storagesense.app.ai.face.FaceEmbeddingEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

@Module
@InstallIn(SingletonComponent::class)
object FaceModule {

    @Provides
    @Singleton
    fun provideFaceEmbeddingEngine(@ApplicationContext context: Context): FaceEmbeddingEngine {
        return FaceEmbeddingEngine(context)
    }

    @Provides
    @Singleton
    fun provideFaceDetectionEngine(
        @ApplicationContext context: Context,
        faceEmbeddingEngine: FaceEmbeddingEngine
    ): FaceDetectionEngine {
        return FaceDetectionEngine(context, faceEmbeddingEngine)
    }

    @Provides
    @Singleton
    fun provideFaceClusterer(): FaceClusterer {
        return FaceClusterer(threshold = 0.60f)
    }
}
