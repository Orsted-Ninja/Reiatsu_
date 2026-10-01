package com.storagesense.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.storagesense.app.domain.repository.FileRepository
import com.storagesense.app.indexing.FileScanner
import com.storagesense.app.indexing.IndexingPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class FullScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileScanner: FileScanner,
    private val indexingPipeline: IndexingPipeline,
    private val fileRepository: FileRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val scannedFiles = fileScanner.scanDirectories()
            for (fileItem in scannedFiles) {
                fileRepository.insertOrUpdate(fileItem)
                indexingPipeline.indexFile(fileItem)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
