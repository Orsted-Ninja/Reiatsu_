package com.storagesense.app.action

import com.storagesense.app.data.local.room.ActionLogDao
import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.entity.ActionLogEntity
import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.ActionResult
import com.storagesense.app.domain.model.ActionType
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.repository.ActionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionEngine @Inject constructor(
    private val safeFileOps: SafeFileOps,
    private val actionLogDao: ActionLogDao,
    private val fileMetadataDao: FileMetadataDao
) : ActionRepository {

    fun proposeTrash(files: List<FileItem>, reason: String = "Move to Trash"): ActionProposal {
        return ActionProposal(
            actionId = UUID.randomUUID().toString(),
            actionType = ActionType.MOVE_TO_TRASH,
            description = reason,
            targetFiles = files
        )
    }

    fun proposePermanentDelete(files: List<FileItem>, reason: String = "Permanent Delete"): ActionProposal {
        return ActionProposal(
            actionId = UUID.randomUUID().toString(),
            actionType = ActionType.PERMANENT_DELETE,
            description = reason,
            targetFiles = files
        )
    }

    override suspend fun executeAction(proposal: ActionProposal): ActionResult {
        var reclaimed = 0L
        var count = 0

        when (proposal.actionType) {
            ActionType.MOVE_TO_TRASH -> {
                for (file in proposal.targetFiles) {
                    val record = safeFileOps.moveToTrash(file)
                    if (record != null) {
                        count++
                        reclaimed += file.sizeBytes

                        // Log action for undo
                        actionLogDao.insertLog(
                            ActionLogEntity(
                                actionId = proposal.actionId,
                                actionType = ActionType.MOVE_TO_TRASH.name,
                                originalPath = record.originalPath,
                                trashPath = record.trashPath,
                                fileSize = file.sizeBytes
                            )
                        )

                        // Update or delete metadata record
                        fileMetadataDao.deleteByPath(file.path)
                    }
                }
            }
            ActionType.PERMANENT_DELETE -> {
                for (file in proposal.targetFiles) {
                    val deleted = safeFileOps.permanentlyDelete(file.path)
                    if (deleted) {
                        count++
                        reclaimed += file.sizeBytes
                        fileMetadataDao.deleteByPath(file.path)
                    }
                }
            }
            ActionType.RESTORE_FROM_TRASH -> {
                return undoLastAction() ?: ActionResult(
                    actionId = proposal.actionId,
                    success = false,
                    affectedFilesCount = 0,
                    reclaimedBytes = 0,
                    message = "No actions to restore",
                    undoAvailable = false
                )
            }
        }

        return ActionResult(
            actionId = proposal.actionId,
            success = count > 0,
            affectedFilesCount = count,
            reclaimedBytes = reclaimed,
            message = "Successfully processed $count files (${formatBytes(reclaimed)})",
            undoAvailable = proposal.actionType == ActionType.MOVE_TO_TRASH
        )
    }

    override suspend fun undoLastAction(): ActionResult? {
        val lastLog = actionLogDao.getLastAction() ?: return null
        val logs = actionLogDao.getByActionId(lastLog.actionId)

        var restoredCount = 0
        var restoredBytes = 0L

        for (log in logs) {
            val trashPath = log.trashPath ?: continue
            val restored = safeFileOps.restoreFromTrash(trashPath, log.originalPath)
            if (restored) {
                restoredCount++
                restoredBytes += log.fileSize
            }
        }

        actionLogDao.markActionUndone(lastLog.actionId)

        return ActionResult(
            actionId = lastLog.actionId,
            success = restoredCount > 0,
            affectedFilesCount = restoredCount,
            reclaimedBytes = restoredBytes,
            message = "Undone! Restored $restoredCount files (${formatBytes(restoredBytes)})",
            undoAvailable = false
        )
    }

    override suspend fun getRecentActions(limit: Int): List<ActionProposal> {
        val logs = actionLogDao.getRecentActions(limit)
        val grouped = logs.groupBy { it.actionId }

        return grouped.map { (actionId, actionLogs) ->
            val first = actionLogs.first()
            val totalSize = actionLogs.sumOf { it.fileSize }
            ActionProposal(
                actionId = actionId,
                actionType = try { ActionType.valueOf(first.actionType) } catch (e: Exception) { ActionType.MOVE_TO_TRASH },
                description = "Moved ${actionLogs.size} files to trash",
                targetFiles = emptyList(),
                totalSizeBytes = totalSize,
                timestampEpochMs = first.timestampEpochMs
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            kb >= 1.0 -> String.format("%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
