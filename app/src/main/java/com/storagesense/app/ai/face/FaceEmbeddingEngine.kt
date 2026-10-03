package com.storagesense.app.ai.face

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class FaceEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MODEL_ASSET_PATH = "models/face_recognition_sface_int8.onnx"
        const val EMBEDDING_DIM = 128
        const val INPUT_SIZE = 112
        private const val TAG = "FaceEmbeddingEngine"
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var isInitialized = false

    @Synchronized
    private fun initSession() {
        if (isInitialized && session != null) return

        try {
            // Load from assets or fallback to external storage
            val modelBytes = try {
                context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            } catch (e: Exception) {
                val externalModel = File("/sdcard/StorageSense/models/face_recognition_sface_int8.onnx")
                if (externalModel.exists()) externalModel.readBytes() else null
            }

            if (modelBytes != null && modelBytes.isNotEmpty()) {
                env = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                }
                session = env?.createSession(modelBytes, sessionOptions)
                isInitialized = true
                Log.i(TAG, "OpenCV SFace INT8 Face Model loaded successfully (${modelBytes.size} bytes)")
            } else {
                Log.w(TAG, "Face recognition model not found in assets or external storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FaceEmbeddingEngine: ${e.message}", e)
        }
    }

    suspend fun getEmbedding(faceBitmap: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            initSession()
        }
        val sess = session ?: return@withContext null
        val environment = env ?: return@withContext null

        try {
            // SFace input is 112x112 RGB
            val resized = if (faceBitmap.width == INPUT_SIZE && faceBitmap.height == INPUT_SIZE) {
                faceBitmap
            } else {
                Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            }

            // NCHW format: [1, 3, 112, 112]
            val imgData = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            for (i in 0 until INPUT_SIZE) {
                for (j in 0 until INPUT_SIZE) {
                    val pixel = pixels[i * INPUT_SIZE + j]
                    // Normalize RGB to [-1.0, 1.0]
                    val r = (((pixel shr 16) and 0xFF) - 127.5f) / 128.0f
                    val g = (((pixel shr 8) and 0xFF) - 127.5f) / 128.0f
                    val b = ((pixel and 0xFF) - 127.5f) / 128.0f

                    imgData.put(i * INPUT_SIZE + j, r)
                    imgData.put(INPUT_SIZE * INPUT_SIZE + i * INPUT_SIZE + j, g)
                    imgData.put(2 * INPUT_SIZE * INPUT_SIZE + i * INPUT_SIZE + j, b)
                }
            }

            val inputName = sess.inputNames.iterator().next()
            val inputTensor = OnnxTensor.createTensor(environment, imgData, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))

            val result = sess.run(mapOf(inputName to inputTensor))
            val rawOutput = result.get(0).value

            result.close()
            inputTensor.close()

            val vector: FloatArray? = when (rawOutput) {
                is Array<*> -> {
                    (rawOutput.firstOrNull() as? FloatArray)
                }
                is FloatArray -> rawOutput
                else -> null
            }

            if (vector != null) {
                normalize(vector)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating face embedding: ${e.message}")
            null
        }
    }

    private fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vector) sumSquares += v * v
        val norm = sqrt(sumSquares.toDouble()).toFloat()
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }
}
