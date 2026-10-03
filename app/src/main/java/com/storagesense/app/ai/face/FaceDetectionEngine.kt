package com.storagesense.app.ai.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class DetectedFaceResult(
    val cropBitmap: Bitmap?,
    val landmarkEmbedding: FloatArray
)

@Singleton
class FaceDetectionEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.10f)
        .build()

    private val detector = FaceDetection.getClient(options)

    /**
     * Efficiently processes a photo file directly via Google ML Kit.
     * Uses InputImage.fromFilePath to handle EXIF rotation and large (12-108MP) camera photos without OutOfMemoryError.
     */
    suspend fun detectFacesInFile(file: File): List<DetectedFaceResult> = withContext(Dispatchers.Default) {
        if (!file.exists() || !file.canRead() || file.length() == 0L) return@withContext emptyList()

        try {
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val faces = detector.process(image).await()
            if (faces.isEmpty()) return@withContext emptyList()

            Log.d("FaceDetectionEngine", "Detected ${faces.size} faces in ${file.name}")

            val results = mutableListOf<DetectedFaceResult>()
            for (face in faces) {
                val bounds = face.boundingBox
                val embedding = extractMlKitLandmarkVector(face, bounds.width().toFloat(), bounds.height().toFloat())
                val crop = cropFaceSafely(file, bounds)
                results.add(DetectedFaceResult(cropBitmap = crop, landmarkEmbedding = embedding))
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
            val left = bounds.left.coerceAtLeast(0)
            val top = bounds.top.coerceAtLeast(0)
            val right = bounds.right.coerceAtMost(bitmap.width)
            val bottom = bounds.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top

            val faceCrop = if (width > 20 && height > 20) {
                Bitmap.createBitmap(bitmap, left, top, width, height)
            } else null

            val embedding = extractMlKitLandmarkVector(face, bounds.width().toFloat(), bounds.height().toFloat())
            results.add(DetectedFaceResult(cropBitmap = faceCrop, landmarkEmbedding = embedding))
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

            val left = bounds.left.coerceIn(0, origW - 1)
            val top = bounds.top.coerceIn(0, origH - 1)
            val right = bounds.right.coerceIn(left + 1, origW)
            val bottom = bounds.bottom.coerceIn(top + 1, origH)
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

    /**
     * Builds a 94-dimensional normalized geometric biometric descriptor using Google ML Kit:
     * - 10 Core facial landmarks (Left/Right Eye, Nose Base, Mouth corners, Cheeks, Ears) -> 20 dims
     * - 36 Face contour boundary points normalized to face centroid and scale -> 72 dims
     * - Inter-pupillary distance ratio & aspect ratio -> 2 dims
     */
    private fun extractMlKitLandmarkVector(face: Face, boxW: Float, boxH: Float): FloatArray {
        val vector = FloatArray(94)
        val bw = if (boxW > 0) boxW else 1f
        val bh = if (boxH > 0) boxH else 1f
        val bLeft = face.boundingBox.left.toFloat()
        val bTop = face.boundingBox.top.toFloat()

        var idx = 0

        val landmarkTypes = intArrayOf(
            FaceLandmark.LEFT_EYE,
            FaceLandmark.RIGHT_EYE,
            FaceLandmark.NOSE_BASE,
            FaceLandmark.MOUTH_LEFT,
            FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM,
            FaceLandmark.LEFT_EAR,
            FaceLandmark.RIGHT_EAR,
            FaceLandmark.LEFT_CHEEK,
            FaceLandmark.RIGHT_CHEEK
        )

        var leftEyePos: PointF? = null
        var rightEyePos: PointF? = null

        for (lt in landmarkTypes) {
            val lm = face.getLandmark(lt)
            if (lm != null) {
                val nx = (lm.position.x - bLeft) / bw
                val ny = (lm.position.y - bTop) / bh
                vector[idx++] = nx
                vector[idx++] = ny

                if (lt == FaceLandmark.LEFT_EYE) leftEyePos = lm.position
                if (lt == FaceLandmark.RIGHT_EYE) rightEyePos = lm.position
            } else {
                vector[idx++] = 0.5f
                vector[idx++] = 0.5f
            }
        }

        // Contour points (36 points for FaceContour.FACE)
        val faceContour = face.getContour(FaceContour.FACE)
        val contourPoints = faceContour?.points ?: emptyList()
        for (i in 0 until 36) {
            if (i < contourPoints.size) {
                val pt = contourPoints[i]
                vector[idx++] = (pt.x - bLeft) / bw
                vector[idx++] = (pt.y - bTop) / bh
            } else {
                vector[idx++] = 0.5f
                vector[idx++] = 0.5f
            }
        }

        // Inter-ocular distance ratio
        if (leftEyePos != null && rightEyePos != null) {
            val dx = (rightEyePos.x - leftEyePos.x) / bw
            val dy = (rightEyePos.y - leftEyePos.y) / bh
            vector[idx++] = sqrt(dx * dx + dy * dy)
        } else {
            vector[idx++] = 0.35f
        }

        // Face Aspect Ratio
        vector[idx] = bw / bh

        // L2 Normalize descriptor for cosine similarity matching
        var sumSquares = 0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }

        return vector
    }
}
