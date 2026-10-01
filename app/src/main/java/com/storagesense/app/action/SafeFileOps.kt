package com.storagesense.app.action

import android.content.Context
import android.os.Environment
import com.storagesense.app.domain.model.FileItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class TrashRecord(
    val originalPath: String,
    val trashPath: String,
    val movedEpochMs: Long
)

@Singleton
class SafeFileOps @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val TRASH_DIR_NAME = ".storagesense/trash"
        const val MAX_RETENTION_DAYS = 30
    }

    fun getTrashDirectory(): File {
        val external = Environment.getExternalStorageDirectory()
        val trash = if (external != null && external.exists()) {
            File(external, TRASH_DIR_NAME)
        } else {
            File(context.filesDir, TRASH_DIR_NAME)
        }
        if (!trash.exists()) {
            trash.mkdirs()
        }
        return trash
    }

    /**
     * Reversible move to trash staging directory
     */
    suspend fun moveToTrash(fileItem: FileItem): TrashRecord? = withContext(Dispatchers.IO) {
        val srcFile = File(fileItem.path)
        if (!srcFile.exists()) return@withContext null

        val trashDir = getTrashDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uniqueName = "${timestamp}_${UUID.randomUUID().toString().take(6)}_${srcFile.name}"
        val destFile = File(trashDir, uniqueName)

        val success = moveFile(srcFile, destFile)
        if (success) {
            TrashRecord(
                originalPath = fileItem.path,
                trashPath = destFile.absolutePath,
                movedEpochMs = System.currentTimeMillis()
            )
        } else {
            null
        }
    }

    /**
     * Restores a trashed file to its original location
     */
    suspend fun restoreFromTrash(trashPath: String, originalPath: String): Boolean = withContext(Dispatchers.IO) {
        val trashFile = File(trashPath)
        if (!trashFile.exists()) return@withContext false

        val originalFile = File(originalPath)
        val parent = originalFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }

        moveFile(trashFile, originalFile)
    }

    /**
     * Explicit permanent delete (only after separate explicit user approval)
     */
    suspend fun permanentlyDelete(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (file.exists()) {
            file.delete()
        } else {
            true
        }
    }

    fun getTrashStats(): Pair<Int, Long> {
        val dir = getTrashDirectory()
        val files = dir.listFiles() ?: return Pair(0, 0L)
        val count = files.size
        val totalBytes = files.sumOf { it.length() }
        return Pair(count, totalBytes)
    }

    suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val dir = getTrashDirectory()
        val files = dir.listFiles() ?: return@withContext 0
        var deleted = 0
        for (f in files) {
            if (f.delete()) deleted++
        }
        deleted
    }

    private fun moveFile(src: File, dest: File): Boolean {
        // First try atomic rename
        if (src.renameTo(dest)) {
            return true
        }

        // Fallback to copy + delete
        return try {
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            src.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
