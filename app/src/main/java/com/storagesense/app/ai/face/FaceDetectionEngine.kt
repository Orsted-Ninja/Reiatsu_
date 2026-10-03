package com.storagesense.app.ai.face

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await

class FaceDetectionEngine {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(options)

    suspend fun detectFaces(bitmap: Bitmap): List<Bitmap> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val faces = detector.process(image).await()
        
        val faceBitmaps = mutableListOf<Bitmap>()
        for (face in faces) {
            val bounds = face.boundingBox
            // Ensure bounds are within the image
            val left = bounds.left.coerceAtLeast(0)
            val top = bounds.top.coerceAtLeast(0)
            val right = bounds.right.coerceAtMost(bitmap.width)
            val bottom = bounds.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top

            if (width > 0 && height > 0) {
                val faceCrop = Bitmap.createBitmap(bitmap, left, top, width, height)
                faceBitmaps.add(faceCrop)
            }
        }
        return faceBitmaps
    }
}
