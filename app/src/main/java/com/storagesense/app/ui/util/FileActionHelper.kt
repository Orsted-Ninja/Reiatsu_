package com.storagesense.app.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.storagesense.app.domain.model.FileItem
import java.io.File

object FileActionHelper {

    fun openFile(context: Context, fileItem: FileItem) {
        val file = File(fileItem.path)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(file)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "Open with...").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!fromMap.isNullOrBlank()) return fromMap

        return when (ext) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "ppt" -> "application/vnd.ms-powerpoint"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "xls" -> "application/vnd.ms-excel"
            "apk" -> "application/vnd.android.package-archive"
            "txt", "log", "csv", "json", "xml" -> "text/plain"
            "mp4", "mkv", "mov", "avi", "webm" -> "video/*"
            "mp3", "m4a", "wav", "flac", "ogg", "aac" -> "audio/*"
            "jpg", "jpeg", "png", "webp", "gif", "bmp" -> "image/*"
            "zip", "rar", "7z", "tar", "gz" -> "application/zip"
            else -> "*/*"
        }
    }
}
