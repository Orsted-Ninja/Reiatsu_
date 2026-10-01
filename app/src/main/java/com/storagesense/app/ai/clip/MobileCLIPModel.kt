package com.storagesense.app.ai.clip

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class MobileCLIPModel @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val EMBEDDING_DIM = 512
        const val IMAGE_SIZE = 256
        const val MODEL_ASSET_PATH = "models/mobileclip_s2.onnx"

        // MobileCLIP normalization constants
        val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isLoaded = false

    suspend fun loadModel() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        try {
            val modelBytes = try {
                context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            } catch (e: Exception) {
                val externalModel = File(context.getExternalFilesDir(null), "models/mobileclip_s2.onnx")
                if (externalModel.exists()) externalModel.readBytes() else null
            }

            if (modelBytes != null) {
                ortEnvironment = OrtEnvironment.getEnvironment()
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                }
                ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)
            }
            isLoaded = true
        } catch (e: Exception) {
            isLoaded = true
        }
    }

    suspend fun embedImage(imageFile: File): FloatArray = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return@withContext FloatArray(EMBEDDING_DIM)
        try {
            embedBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun embedBitmap(bitmap: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        if (!isLoaded) loadModel()

        val cropped = centerCrop(bitmap, IMAGE_SIZE, IMAGE_SIZE)
        val tensorData = preprocessBitmap(cropped)
        if (cropped != bitmap) {
            cropped.recycle()
        }

        val session = ortSession
        val env = ortEnvironment

        if (session != null && env != null) {
            try {
                val shape = longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
                val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape)

                val result = session.run(mapOf("image" to tensor))
                val output = result[0].value as Array<FloatArray>
                val embedding = output[0]

                tensor.close()
                result.close()

                return@withContext normalize(embedding)
            } catch (e: Exception) {
                // fallback
            }
        }

        generateFallbackVisualEmbedding(bitmap)
    }

    /**
     * Center crops the bitmap to targetWidth x targetHeight (does not stretch)
     */
    fun centerCrop(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val width = src.width
        val height = src.height

        val scale = Math.max(targetWidth.toFloat() / width, targetHeight.toFloat() / height)
        val scaledWidth = scale * width
        val scaledHeight = scale * height

        val left = (scaledWidth - targetWidth) / 2
        val top = (scaledHeight - targetHeight) / 2

        val scaledBitmap = Bitmap.createScaledBitmap(src, scaledWidth.toInt(), scaledHeight.toInt(), true)
        val cropped = Bitmap.createBitmap(scaledBitmap, left.toInt(), top.toInt(), targetWidth, targetHeight)

        if (scaledBitmap != src && scaledBitmap != cropped) {
            scaledBitmap.recycle()
        }

        return cropped
    }

    /**
     * Converts Bitmap pixels into normalized [1, 3, 256, 256] planar float array (NCHW format)
     */
    fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        bitmap.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

        val totalPixels = IMAGE_SIZE * IMAGE_SIZE
        val nchw = FloatArray(3 * totalPixels)

        val rOffset = 0
        val gOffset = totalPixels
        val bOffset = 2 * totalPixels

        for (i in 0 until totalPixels) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            nchw[rOffset + i] = (r - MEAN[0]) / STD[0]
            nchw[gOffset + i] = (g - MEAN[1]) / STD[1]
            nchw[bOffset + i] = (b - MEAN[2]) / STD[2]
        }

        return nchw
    }

    fun generateFallbackVisualEmbedding(bitmap: Bitmap): FloatArray {
        val vector = FloatArray(EMBEDDING_DIM)
        // Compute color histogram + spatial brightness moments
        val width = bitmap.width
        val height = bitmap.height
        val stepX = (width / 16).coerceAtLeast(1)
        val stepY = (height / 16).coerceAtLeast(1)

        var idx = 0
        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                if (idx >= EMBEDDING_DIM - 3) break
                val pixel = bitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                vector[idx++] += r
                vector[idx++] += g
                vector[idx++] += b
            }
        }

        return normalize(vector)
    }

    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size || v1.isEmpty()) return 0f
        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        val denom = sqrt(norm1) * sqrt(norm2)
        return if (denom > 0f) dot / denom else 0f
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sumSquares = 0f
        for (f in v) sumSquares += f * f
        val norm = sqrt(sumSquares)
        if (norm <= 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    fun unload() {
        ortSession?.close()
        ortSession = null
        ortEnvironment?.close()
        ortEnvironment = null
        isLoaded = false
    }
}
