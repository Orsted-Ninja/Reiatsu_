package com.storagesense.app.ai.face

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.tasks.await
import kotlin.math.sqrt

data class DetectedFaceResult(
    val cropBitmap: Bitmap,
    val landmarkEmbedding: FloatArray
)

class FaceDetectionEngine {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.12f)
        .build()

    private val detector = FaceDetection.getClient(options)

    /**
     * Detects faces and computes pure on-device geometric feature vectors using Google ML Kit.
     * Requires ZERO external models or downloads.
     */
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

            if (width > 20 && height > 20) {
                val faceCrop = Bitmap.createBitmap(bitmap, left, top, width, height)
                val embedding = extractMlKitLandmarkVector(face, bounds.width().toFloat(), bounds.height().toFloat())
                results.add(DetectedFaceResult(cropBitmap = faceCrop, landmarkEmbedding = embedding))
            }
        }
        return results
    }

    /**
     * Compatibility method returning just crop bitmaps
     */
    suspend fun detectFaces(bitmap: Bitmap): List<Bitmap> {
        return detectFacesWithFeatures(bitmap).map { it.cropBitmap }
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
