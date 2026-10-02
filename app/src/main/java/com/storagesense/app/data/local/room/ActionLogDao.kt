package com.storagesense.app.data.local.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.storagesense.app.data.local.room.entity.ActionLogEntity

@Dao
interface ActionLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ActionLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ActionLogEntity>)

    @Query("SELECT * FROM action_logs WHERE actionId = :actionId")
    suspend fun getByActionId(actionId: String): List<ActionLogEntity>

    @Query("SELECT * FROM action_logs WHERE isUndone = 0 ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun getRecentActions(limit: Int = 20): List<ActionLogEntity>

    @Query("SELECT * FROM action_logs WHERE trashPath = :trashPath LIMIT 1")
    suspend fun getByTrashPath(trashPath: String): ActionLogEntity?

    @Query("SELECT * FROM action_logs WHERE isUndone = 0 ORDER BY timestampEpochMs DESC LIMIT 1")
    suspend fun getLastAction(): ActionLogEntity?

    @Query("UPDATE action_logs SET isUndone = 1 WHERE actionId = :actionId")
    suspend fun markActionUndone(actionId: String)

    @Query("UPDATE action_logs SET isUndone = 1 WHERE trashPath = :trashPath")
    suspend fun markTrashPathUndone(trashPath: String)

    @Query("DELETE FROM action_logs WHERE trashPath = :trashPath")
    suspend fun deleteByTrashPath(trashPath: String)
}
