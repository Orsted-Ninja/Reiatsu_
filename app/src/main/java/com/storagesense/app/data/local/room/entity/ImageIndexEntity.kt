package com.storagesense.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "image_indices",
    foreignKeys = [
        ForeignKey(
            entity = FileMetadataEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["fileId"], unique = true)
    ]
)
data class ImageIndexEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileId: Long,
    val ocrText: String = "",
    val clipEmbedding: FloatArray? = null,
    val visualTags: String? = null,
    val isScreenshot: Boolean = false,
    val hasHandwrittenNotes: Boolean = false,
    val indexedEpochMs: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ImageIndexEntity
        if (id != other.id) return false
        if (fileId != other.fileId) return false
        if (ocrText != other.ocrText) return false
        if (clipEmbedding != null) {
            if (other.clipEmbedding == null) return false
            if (!clipEmbedding.contentEquals(other.clipEmbedding)) return false
        } else if (other.clipEmbedding != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + ocrText.hashCode()
        result = 31 * result + (clipEmbedding?.contentHashCode() ?: 0)
        return result
    }
}
