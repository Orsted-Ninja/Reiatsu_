package com.storagesense.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem

@Entity(
    tableName = "file_metadata",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["sha256Hash"]),
        Index(value = ["category"]),
        Index(value = ["sizeBytes"])
    ]
)
data class FileMetadataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val path: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val sha256Hash: String? = null,
    val category: String,
    val isImportant: Boolean = false,
    val indexedEpochMs: Long = System.currentTimeMillis()
) {
    fun toDomain(): FileItem {
        return FileItem(
            id = id,
            path = path,
            name = name,
            extension = extension,
            sizeBytes = sizeBytes,
            lastModifiedEpochMs = lastModifiedEpochMs,
            sha256Hash = sha256Hash,
            category = try {
                FileCategory.valueOf(category)
            } catch (e: Exception) {
                FileCategory.fromExtension(extension)
            },
            isImportant = isImportant,
            indexedEpochMs = indexedEpochMs
        )
    }

    companion object {
        fun fromDomain(item: FileItem): FileMetadataEntity {
            return FileMetadataEntity(
                id = item.id,
                path = item.path,
                name = item.name,
                extension = item.extension,
                sizeBytes = item.sizeBytes,
                lastModifiedEpochMs = item.lastModifiedEpochMs,
                sha256Hash = item.sha256Hash,
                category = item.category.name,
                isImportant = item.isImportant,
                indexedEpochMs = item.indexedEpochMs
            )
        }
    }
}
