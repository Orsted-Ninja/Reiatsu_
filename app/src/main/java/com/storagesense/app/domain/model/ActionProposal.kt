package com.storagesense.app.domain.model

enum class ActionType {
    MOVE_TO_TRASH,
    PERMANENT_DELETE,
    RESTORE_FROM_TRASH
}

data class ActionProposal(
    val actionId: String = java.util.UUID.randomUUID().toString(),
    val actionType: ActionType,
    val description: String,
    val targetFiles: List<FileItem>,
    val totalSizeBytes: Long = targetFiles.sumOf { it.sizeBytes },
    val timestampEpochMs: Long = System.currentTimeMillis()
) {
    val formattedTotalSize: String
        get() {
            val kb = totalSizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$totalSizeBytes B"
            }
        }
}

data class ActionResult(
    val actionId: String,
    val success: Boolean,
    val affectedFilesCount: Int,
    val reclaimedBytes: Long,
    val message: String,
    val undoAvailable: Boolean
)
