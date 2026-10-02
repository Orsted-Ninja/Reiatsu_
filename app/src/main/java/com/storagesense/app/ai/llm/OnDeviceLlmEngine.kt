package com.storagesense.app.ai.llm

import android.content.Context
import android.os.Environment
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnDeviceLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var llmInference: LlmInference? = null
    private var activeModelPath: String? = null
    private val mutex = Mutex()
    private var isInitializing = false

    companion object {
        const val MAX_TOKENS = 256
        const val TOP_K = 40
        const val TEMPERATURE = 0.2f
        const val RANDOM_SEED = 42

        val CANDIDATE_FILENAMES = listOf(
            "gemma-4-e2b-it.litertlm",
            "gemma4e2b.litertlm",
            "gemma4-e2b-q4.litertlm",
            "gemma-4-text-only.litertlm",
            "gemma4-e4b-q4.litertlm",
            "gemma4e4b.litertlm",
            "gemma-2b-it-gpu-int4.bin",
            "gemma-2b-it-cpu-int4.bin",
            "gemma-2b-it.bin",
            "gemma-2b.bin",
            "gemma.bin",
            "gemma4e2b.bin",
            "gemma2.bin",
            "gemma-2b-it.task"
        )
    }

    fun findAllModelCandidates(): List<File> {
        val candidateDirs = mutableListOf<File>()
        try {
            val external = Environment.getExternalStorageDirectory()
            if (external != null && external.exists()) {
                candidateDirs.add(File(external, "StorageSense/models"))
                candidateDirs.add(File(external, "Download/models"))
                candidateDirs.add(File(external, "Download"))
                candidateDirs.add(File(external, "Documents/models"))
                candidateDirs.add(File(external, "StorageSense"))
            }
        } catch (_: Exception) {}

        context.getExternalFilesDir("models")?.let { candidateDirs.add(it) }
        context.getExternalFilesDir(null)?.let { candidateDirs.add(it) }
        candidateDirs.add(File(context.filesDir, "models"))
        candidateDirs.add(File("/data/local/tmp"))

        val found = mutableListOf<File>()
        // 1. Prioritized candidate filenames (Gemma 4 E2B first as Primary, then INT4 as Secondary)
        for (filename in CANDIDATE_FILENAMES) {
            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                val f = File(dir, filename)
                if (f.exists() && f.length() > 10_000_000L && !found.any { it.absolutePath == f.absolutePath }) {
                    found.add(f)
                }
            }
        }

        // 2. Dynamic discovery for any other models
        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val dynamicMatches = dir.listFiles { _, name ->
                val lower = name.lowercase()
                (lower.endsWith(".litertlm") || lower.endsWith(".bin") || lower.endsWith(".task")) &&
                        (lower.contains("gemma") || lower.contains("llm") || lower.contains("model"))
            }
            dynamicMatches?.filter { it.length() > 50_000_000L }?.forEach { f ->
                if (!found.any { it.absolutePath == f.absolutePath }) {
                    found.add(f)
                }
            }
        }

        return found
    }

    /**
     * Searches standard on-device directories for the highest priority Gemma model binary.
     * Follows plan_project.md path: /sdcard/StorageSense/models/
     */
    fun findOnDeviceModelFile(): File? {
        return findAllModelCandidates().firstOrNull()
    }

    fun isModelAvailable(): Boolean {
        return activeModelPath != null || findOnDeviceModelFile() != null
    }

    fun getDetectedModelName(): String? {
        val path = activeModelPath ?: findOnDeviceModelFile()?.absolutePath ?: return null
        return File(path).name
    }

    fun getDetectedModelPath(): String? {
        return activeModelPath ?: findOnDeviceModelFile()?.absolutePath
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (llmInference != null) return@withContext true
            if (isInitializing) return@withContext false
            isInitializing = true

            try {
                val candidates = findAllModelCandidates()
                if (candidates.isEmpty()) {
                    android.util.Log.w("OnDeviceLlmEngine", "No Gemma model files found on device.")
                    isInitializing = false
                    return@withContext false
                }

                // Try candidates in order (Primary: Gemma 4 E2B -> Secondary: INT4 GPU)
                for (modelFile in candidates) {
                    android.util.Log.i("OnDeviceLlmEngine", "Attempting MediaPipe LLM load: ${modelFile.name} (${modelFile.length()} bytes)")
                    
                    try {
                        llmInference = try {
                            val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                                .setModelPath(modelFile.absolutePath)
                                .setMaxTokens(MAX_TOKENS)
                                .setPreferredBackend(LlmInference.Backend.GPU)
                                .build()
                            android.util.Log.i("OnDeviceLlmEngine", "Attempting GPU backend for ${modelFile.name}...")
                            LlmInference.createFromOptions(context, gpuOptions)
                        } catch (gpuEx: Throwable) {
                            android.util.Log.w("OnDeviceLlmEngine", "GPU backend failed for ${modelFile.name} (${gpuEx.message}), trying CPU...")
                            val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                                .setModelPath(modelFile.absolutePath)
                                .setMaxTokens(MAX_TOKENS)
                                .setPreferredBackend(LlmInference.Backend.CPU)
                                .build()
                            LlmInference.createFromOptions(context, cpuOptions)
                        }

                        activeModelPath = modelFile.absolutePath
                        isInitializing = false
                        android.util.Log.i("OnDeviceLlmEngine", "MediaPipe LLM initialized successfully with ${modelFile.name}!")
                        return@withContext true
                    } catch (e: Throwable) {
                        android.util.Log.w("OnDeviceLlmEngine", "Model ${modelFile.name} failed: ${e.message}. Trying next candidate...")
                    }
                }

                isInitializing = false
                false
            } catch (e: Throwable) {
                android.util.Log.e("OnDeviceLlmEngine", "Failed to initialize LLM: ${e.message}", e)
                isInitializing = false
                false
            }
        }
    }

    /**
     * Generates on-device LLM response and streams tokens smoothly to UI.
     * Prevents double-allocation of 2.6 GB model weights.
     */
    fun streamGenerate(prompt: String): Flow<String> = callbackFlow {
        android.util.Log.i("OnDeviceLlmEngine", "streamGenerate called with prompt length: ${prompt.length}")
        val ready = if (llmInference == null) initialize() else true
        val inference = llmInference

        if (!ready || inference == null) {
            android.util.Log.w("OnDeviceLlmEngine", "LLM inference engine not ready (ready=$ready, inference=$inference)")
            close()
            return@callbackFlow
        }

        try {
            android.util.Log.i("OnDeviceLlmEngine", "Calling inference.generateResponseAsync...")
            val future = inference.generateResponseAsync(prompt) { partialResponse, done ->
                if (!partialResponse.isNullOrEmpty()) {
                    android.util.Log.i("OnDeviceLlmEngine", "Received token chunk (${partialResponse.length} chars)")
                    trySend(partialResponse)
                }
                if (done) {
                    android.util.Log.i("OnDeviceLlmEngine", "Generation complete!")
                    close()
                }
            }
            awaitClose {
                try {
                    future.cancel(true)
                } catch (_: Exception) {}
            }
        } catch (e: Throwable) {
            android.util.Log.e("OnDeviceLlmEngine", "Error generating response: ${e.message}", e)
            close(e)
        }
    }.flowOn(Dispatchers.Default)

    fun unload() {
        try {
            llmInference?.close()
        } catch (_: Exception) {}
        llmInference = null
        activeModelPath = null
    }
}
