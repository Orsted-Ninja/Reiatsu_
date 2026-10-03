package com.storagesense.app.di

import com.storagesense.app.ai.face.FaceClusterer
import com.storagesense.app.ai.face.FaceDetectionEngine
import com.storagesense.app.ai.face.FaceEmbeddingEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FaceModule {

    @Provides
    @Singleton
    fun provideFaceDetectionEngine(): FaceDetectionEngine {
        return FaceDetectionEngine()
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
        return FaceClusterer(threshold = 0.6f)
    }
}
