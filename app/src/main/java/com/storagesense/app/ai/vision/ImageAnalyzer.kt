package com.storagesense.app.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class VisionResult(
    val labels: List<String>,
    val hasFaces: Boolean
)

@Singleton
class ImageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Official ML Kit components with balanced confidence threshold
    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.45f)
            .build()
    )

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    suspend fun analyzeImage(filePath: String): VisionResult {
        var bitmap: Bitmap? = null
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead() || file.length() == 0L) {
                return VisionResult(emptyList(), false)
            }

            // Downsample to max dimension ~512px to guarantee sub-20ms inference and zero OOM
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, boundsOpts)
            val origW = boundsOpts.outWidth
            val origH = boundsOpts.outHeight
            if (origW <= 0 || origH <= 0) {
                return VisionResult(emptyList(), false)
            }

            var inSampleSize = 1
            val maxDim = max(origW, origH)
            while (maxDim / inSampleSize > 512) {
                inSampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            bitmap = BitmapFactory.decodeFile(filePath, decodeOpts)
                ?: return VisionResult(emptyList(), false)

            val image = InputImage.fromBitmap(bitmap, 0)

            // Await Tasks in parallel
            val (labelsList, facesList) = kotlinx.coroutines.coroutineScope {
                val labelsDeferred = async { labeler.process(image).await() }
                val facesDeferred = async { faceDetector.process(image).await() }
                Pair(labelsDeferred.await(), facesDeferred.await())
            }

            val labels = labelsList.map { it.text }
            val hasFaces = facesList.isNotEmpty()

            VisionResult(labels, hasFaces)
        } catch (e: Exception) {
            Log.w("ImageAnalyzer", "Vision analysis skipped for: $filePath (${e.message})")
            VisionResult(emptyList(), false)
        } finally {
            bitmap?.recycle()
        }
    }
}
