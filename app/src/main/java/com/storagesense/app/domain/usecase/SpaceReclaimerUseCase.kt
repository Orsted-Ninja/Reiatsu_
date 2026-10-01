package com.storagesense.app.domain.usecase

import com.storagesense.app.action.SpaceReclaimer
import com.storagesense.app.domain.model.CleanupPlan
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpaceReclaimerUseCase @Inject constructor(
    private val spaceReclaimer: SpaceReclaimer
) {
    suspend operator fun invoke(targetBytes: Long = 5L * 1024L * 1024L * 1024L): CleanupPlan {
        return spaceReclaimer.generateCleanupPlan(targetBytes)
    }
}
