package com.storagesense.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = FileMetadataEntity::class,
            parentColumns = ["id"],
            childColumns = ["fileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["fileId"]),
        Index(value = ["fileId", "chunkIndex"])
    ]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileId: Long,
    val chunkIndex: Int,
    val text: String,
    val embedding: FloatArray? = null,
    val pageNumber: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DocumentChunkEntity
        if (id != other.id) return false
        if (fileId != other.fileId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (text != other.text) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + chunkIndex
        result = 31 * result + text.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
