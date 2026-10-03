package com.storagesense.app.ai.face

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer

class FaceEmbeddingEngine(private val modelPath: String) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    init {
        val modelFile = File(modelPath)
        if (modelFile.exists()) {
            env = OrtEnvironment.getEnvironment()
            session = env?.createSession(modelPath, OrtSession.SessionOptions())
            Log.d("FaceEmbeddingEngine", "ONNX Face Model loaded from $modelPath")
        } else {
            Log.e("FaceEmbeddingEngine", "ONNX Model not found at $modelPath")
        }
    }

    suspend fun getEmbedding(faceBitmap: Bitmap): FloatArray? = withContext(Dispatchers.Default) {
        if (session == null || env == null) return@withContext null

        try {
            // MobileFaceNet typically expects 112x112
            val resized = Bitmap.createScaledBitmap(faceBitmap, 112, 112, true)
            
            // NCHW format
            val imgData = FloatBuffer.allocate(1 * 3 * 112 * 112)
            val pixels = IntArray(112 * 112)
            resized.getPixels(pixels, 0, 112, 0, 0, 112, 112)
            
            for (i in 0 until 112) {
                for (j in 0 until 112) {
                    val pixel = pixels[i * 112 + j]
                    // Normalize to [-1, 1]
                    val r = (((pixel shr 16) and 0xFF) - 127.5f) / 128.0f
                    val g = (((pixel shr 8) and 0xFF) - 127.5f) / 128.0f
                    val b = ((pixel and 0xFF) - 127.5f) / 128.0f
                    
                    imgData.put(i * 112 + j, r)
                    imgData.put(112 * 112 + i * 112 + j, g)
                    imgData.put(2 * 112 * 112 + i * 112 + j, b)
                }
            }

            val inputName = session?.inputNames?.iterator()?.next() ?: return@withContext null
            val inputTensor = OnnxTensor.createTensor(env, imgData, longArrayOf(1, 3, 112, 112))
            
            val result = session?.run(mapOf(inputName to inputTensor))
            val output = result?.get(0)?.value as? Array<FloatArray>
            
            result?.close()
            inputTensor.close()
            
            return@withContext output?.get(0)?.let { normalize(it) }
        } catch (e: Exception) {
            Log.e("FaceEmbeddingEngine", "Error generating embedding: ${e.message}")
            return@withContext null
        }
    }
    
    private fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0f
        for (v in vector) sum += v * v
        val norm = Math.sqrt(sum.toDouble()).toFloat()
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }
        return vector
    }
}
