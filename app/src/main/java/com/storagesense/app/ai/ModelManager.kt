package com.storagesense.app.ai

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import com.storagesense.app.ai.clip.MobileCLIPModel
import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.ai.llm.OnDeviceLlmEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class ModelState {
    IDLE,
    EMBEDDING_LOADED,
    LLM_LOADED
}

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val textEmbeddingModel: TextEmbeddingModel,
    private val mobileClipModel: MobileCLIPModel,
    private val onDeviceLlmEngine: OnDeviceLlmEngine
) : ComponentCallbacks2 {

    private val mutex = Mutex()
    private var currentState = ModelState.IDLE
    private var idleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    companion object {
        const val IDLE_UNLOAD_DELAY_MS = 60_000L // 60 seconds
    }

    init {
        context.registerComponentCallbacks(this)
    }

    fun getLlmStatus(): String {
        return if (onDeviceLlmEngine.isModelAvailable()) {
            "Active: ${onDeviceLlmEngine.getDetectedModelName()}"
        } else {
            "Ready (Listening to /sdcard/StorageSense/models)"
        }
    }

    suspend fun acquireEmbeddingModels() = mutex.withLock {
        idleJob?.cancel()
        if (currentState == ModelState.LLM_LOADED) {
            unloadLlmInternal()
        }

        if (currentState != ModelState.EMBEDDING_LOADED) {
            textEmbeddingModel.loadModel()
            mobileClipModel.loadModel()
            currentState = ModelState.EMBEDDING_LOADED
        }
    }

    suspend fun releaseEmbeddingModelsWithTimeout() = mutex.withLock {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_DELAY_MS)
            mutex.withLock {
                if (currentState == ModelState.EMBEDDING_LOADED) {
                    textEmbeddingModel.unload()
                    mobileClipModel.unload()
                    currentState = ModelState.IDLE
                }
            }
        }
    }

    suspend fun acquireLlm() = mutex.withLock {
        idleJob?.cancel()
        if (currentState == ModelState.EMBEDDING_LOADED) {
            textEmbeddingModel.unload()
            mobileClipModel.unload()
        }
        onDeviceLlmEngine.initialize()
        currentState = ModelState.LLM_LOADED
    }

    suspend fun releaseLlm() = mutex.withLock {
        unloadLlmInternal()
        currentState = ModelState.IDLE
    }

    private fun unloadLlmInternal() {
        onDeviceLlmEngine.unload()
    }

    fun isLowMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val memoryInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || (memoryInfo.availMem < (500L * 1024L * 1024L)) // < 500 MB
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            scope.launch {
                mutex.withLock {
                    textEmbeddingModel.unload()
                    mobileClipModel.unload()
                    unloadLlmInternal()
                    currentState = ModelState.IDLE
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {}
    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}
