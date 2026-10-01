package com.storagesense.app.domain.usecase

import com.storagesense.app.action.ActionEngine
import com.storagesense.app.domain.model.ActionProposal
import com.storagesense.app.domain.model.ActionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExecuteActionUseCase @Inject constructor(
    private val actionEngine: ActionEngine
) {
    suspend fun execute(proposal: ActionProposal): ActionResult {
        return actionEngine.executeAction(proposal)
    }

    suspend fun undo(): ActionResult? {
        return actionEngine.undoLastAction()
    }
}
