package com.storagesense.app.domain.repository

import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.ActionResult

interface ActionRepository {
    suspend fun executeAction(proposal: ActionProposal): ActionResult
    suspend fun undoLastAction(): ActionResult?
    suspend fun getRecentActions(limit: Int = 20): List<ActionProposal>
}
