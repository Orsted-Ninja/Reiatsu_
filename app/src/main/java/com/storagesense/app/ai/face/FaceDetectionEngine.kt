package com.storagesense.app.ai.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DetectedFaceResult(
    val cropBitmap: Bitmap?,
    val embedding: FloatArray,
    val thumbnailPath: String? = null
)

@Singleton
class FaceDetectionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val faceEmbeddingEngine: FaceEmbeddingEngine
) {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.10f)
        .build()

    private val detector = FaceDetection.getClient(options)

    /**
     * Efficiently processes a photo file directly via Google ML Kit.
     * Extracts deep 128-d face recognition embeddings using on-device OpenCV SFace INT8 ONNX.
     * Saves a lightweight 160x160 face crop avatar to cache for Google Photos style UI.
     */
    suspend fun detectFacesInFile(file: File): List<DetectedFaceResult> = withContext(Dispatchers.Default) {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return@withContext emptyList()

        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val faces = detector.process(image).await()
            if (faces.isEmpty()) return@withContext emptyList()

            Log.d("FaceDetectionEngine", "Detected ${faces.size} faces in ${file.name}")

            val results = mutableListOf<DetectedFaceResult>()
            for ((idx, face) in faces.withIndex()) {
                val bounds = face.boundingBox
                val crop = cropFaceSafely(file, bounds)
                if (crop != null) {
                    val embedding = faceEmbeddingEngine.getEmbedding(crop) ?: FloatArray(FaceEmbeddingEngine.EMBEDDING_DIM)
                    val thumbPath = saveFaceThumbnail(crop, file, idx)
                    results.add(DetectedFaceResult(cropBitmap = crop, embedding = embedding, thumbnailPath = thumbPath))
                }
            }
            results
        } catch (t: Throwable) {
            Log.e("FaceDetectionEngine", "Failed detecting faces in ${file.name}: ${t.message}")
            emptyList()
        }
    }

    suspend fun detectFacesWithFeatures(bitmap: Bitmap): List<DetectedFaceResult> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(image).await()

        val results = mutableListOf<DetectedFaceResult>()
        for (face in faces) {
            val bounds = face.boundingBox
            val padX = (bounds.width() * 0.15f).toInt()
            val padY = (bounds.height() * 0.15f).toInt()
            val left = (bounds.left - padX).coerceAtLeast(0)
            val top = (bounds.top - padY).coerceAtLeast(0)
            val right = (bounds.right + padX).coerceAtMost(bitmap.width)
            val bottom = (bounds.bottom + padY).coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top

            val faceCrop = if (width > 20 && height > 20) {
                Bitmap.createBitmap(bitmap, left, top, width, height)
            } else null

            if (faceCrop != null) {
                val embedding = faceEmbeddingEngine.getEmbedding(faceCrop) ?: FloatArray(FaceEmbeddingEngine.EMBEDDING_DIM)
                results.add(DetectedFaceResult(cropBitmap = faceCrop, embedding = embedding, thumbnailPath = null))
            }
        }
        return results
    }

    suspend fun detectFaces(bitmap: Bitmap): List<Bitmap> {
        return detectFacesWithFeatures(bitmap).mapNotNull { it.cropBitmap }
    }

    private fun cropFaceSafely(file: File, bounds: Rect): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val origW = boundsOptions.outWidth
            val origH = boundsOptions.outHeight
            if (origW <= 0 || origH <= 0) return null

            // Add 15% framing margin around face
            val padX = (bounds.width() * 0.15f).toInt()
            val padY = (bounds.height() * 0.15f).toInt()

            val left = (bounds.left - padX).coerceIn(0, origW - 1)
            val top = (bounds.top - padY).coerceIn(0, origH - 1)
            val right = (bounds.right + padX).coerceIn(left + 1, origW)
            val bottom = (bounds.bottom + padY).coerceIn(top + 1, origH)
            val rect = Rect(left, top, right, bottom)

            val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(file.absolutePath)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(file.absolutePath, false)
            }

            val decodeOpts = BitmapFactory.Options().apply {
                val targetSize = 160
                val maxDim = maxOf(rect.width(), rect.height())
                var sample = 1
                while (maxDim / (sample * 2) >= targetSize) {
                    sample *= 2
                }
                inSampleSize = sample
            }
            decoder?.decodeRegion(rect, decodeOpts)
        } catch (t: Throwable) {
            null
        }
    }

    private fun saveFaceThumbnail(crop: Bitmap, file: File, faceIndex: Int): String? {
        return try {
            val facesDir = File(context.cacheDir, "faces")
            if (!facesDir.exists()) facesDir.mkdirs()
            val thumbName = "face_${Math.abs(file.name.hashCode())}_$faceIndex.jpg"
            val thumbFile = File(facesDir, thumbName)
            if (!thumbFile.exists()) {
                val scaled = if (crop.width > 200 || crop.height > 200) {
                    Bitmap.createScaledBitmap(crop, 160, 160, true)
                } else crop
                thumbFile.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
            thumbFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}
