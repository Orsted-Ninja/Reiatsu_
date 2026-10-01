package com.storagesense.app.domain.model

enum class DuplicateType {
    EXACT_HASH,
    NEAR_DOCUMENT,
    NEAR_IMAGE
}

data class DuplicateGroup(
    val groupId: String,
    val type: DuplicateType,
    val similarityScore: Float, // 1.0 for exact, 0.85-0.99 for near
    val keepCandidate: FileItem,
    val deleteCandidates: List<FileItem>
) {
    val reclaimableBytes: Long
        get() = deleteCandidates.sumOf { it.sizeBytes }

    val formattedReclaimable: String
        get() {
            val kb = reclaimableBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$reclaimableBytes B"
            }
        }
}
