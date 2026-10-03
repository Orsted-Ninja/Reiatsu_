package com.storagesense.app.ai.face

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "face_clusters")
data class FaceClusterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,
    val faceEmbedding: ByteArray, // Store as ByteArray of FloatArray
    val personClusterId: Int = -1 // -1 means unclustered
)
