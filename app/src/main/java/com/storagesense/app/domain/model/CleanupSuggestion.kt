package com.storagesense.app.domain.model

data class CleanupCategory(
    val title: String,
    val description: String,
    val items: List<FileItem>,
    val priority: Int // higher means safer/higher priority to clean
) {
    val totalBytes: Long = items.sumOf { it.sizeBytes }

    val formattedSize: String
        get() {
            val kb = totalBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$totalBytes B"
            }
        }
}

data class CleanupPlan(
    val requestedBytes: Long,
    val totalReclaimableBytes: Long,
    val categories: List<CleanupCategory>,
    val protectedFilesCount: Int
) {
    val formattedReclaimable: String
        get() {
            val kb = totalReclaimableBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$totalReclaimableBytes B"
            }
        }
}
