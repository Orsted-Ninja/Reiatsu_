package com.storagesense.app.data.local.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "action_logs",
    indices = [
        Index(value = ["actionId"]),
        Index(value = ["originalPath"])
    ]
)
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val actionId: String,
    val actionType: String,
    val originalPath: String,
    val trashPath: String? = null,
    val fileSize: Long = 0,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val isUndone: Boolean = false
)
