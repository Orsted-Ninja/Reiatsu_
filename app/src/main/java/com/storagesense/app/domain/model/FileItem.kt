package com.storagesense.app.domain.model

enum class FileCategory {
    DOCUMENT_PDF,
    DOCUMENT_WORD,
    DOCUMENT_SLIDES,
    DOCUMENT_TEXT,
    IMAGE_PHOTO,
    IMAGE_SCREENSHOT,
    ARCHIVE,
    INSTALLER,
    VIDEO,
    AUDIO,
    OTHER;

    companion object {
        fun fromExtension(ext: String): FileCategory {
            return when (ext.lowercase()) {
                "pdf" -> DOCUMENT_PDF
                "docx", "doc" -> DOCUMENT_WORD
                "pptx", "ppt" -> DOCUMENT_SLIDES
                "txt", "md", "csv", "json", "xml", "log" -> DOCUMENT_TEXT
                "jpg", "jpeg", "png", "webp", "gif" -> IMAGE_PHOTO
                "zip", "rar", "7z", "tar", "gz" -> ARCHIVE
                "apk", "exe", "msi" -> INSTALLER
                "mp4", "mkv", "avi", "mov", "webm" -> VIDEO
                "mp3", "wav", "m4a", "flac" -> AUDIO
                else -> OTHER
            }
        }
    }
}

data class FileItem(
    val id: Long = 0,
    val path: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val sha256Hash: String? = null,
    val category: FileCategory = FileCategory.fromExtension(extension),
    val isImportant: Boolean = false,
    val indexedEpochMs: Long = System.currentTimeMillis(),
    val imageLabels: List<String> = emptyList(),
    val hasFaces: Boolean = false
) {
    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$sizeBytes B"
            }
        }
}
