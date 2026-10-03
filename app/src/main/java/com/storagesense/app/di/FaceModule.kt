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
    fun provideFaceDetectionEngine(@ApplicationContext context: Context): FaceDetectionEngine {
        return FaceDetectionEngine(context)
    }

    @Provides
    @Singleton
    fun provideFaceEmbeddingEngine(): FaceEmbeddingEngine {
        val path = "/sdcard/StorageSense/models/mobilefacenet.onnx"
        return FaceEmbeddingEngine(path)
    }

    @Provides
    @Singleton
    fun provideFaceClusterer(): FaceClusterer {
        return FaceClusterer(threshold = 0.58f)
    }
}
