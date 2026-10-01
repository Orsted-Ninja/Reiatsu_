package com.storagesense.app.action

import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.domain.model.CleanupCategory
import com.storagesense.app.domain.model.CleanupPlan
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.search.DuplicateDetector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpaceReclaimer @Inject constructor(
    private val fileMetadataDao: FileMetadataDao,
    private val duplicateDetector: DuplicateDetector
) {
    companion object {
        const val STALE_DAYS_MS = 60L * 24L * 60L * 60L * 1000L // 60 days
        const val RECENT_PROTECTION_DAYS_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    }

    suspend fun generateCleanupPlan(targetBytes: Long = 5L * 1024L * 1024L * 1024L): CleanupPlan {
        val allFiles = fileMetadataDao.getAll().map { it.toDomain() }
        val now = System.currentTimeMillis()

        // 1. Filter out protected files (important files and very recently modified files)
        val candidateFiles = mutableListOf<FileItem>()
        var protectedCount = 0

        for (file in allFiles) {
            if (file.isImportant || (now - file.lastModifiedEpochMs < RECENT_PROTECTION_DAYS_MS)) {
                protectedCount++
            } else {
                candidateFiles.add(file)
            }
        }

        // 2. Identify duplicate delete candidates
        val duplicates = duplicateDetector.findExactDuplicates()
        val duplicateDeleteItems = duplicates.flatMap { it.deleteCandidates }
        val duplicatePaths = duplicateDeleteItems.map { it.path }.toSet()

        // Remove duplicate items from general candidate list to avoid double counting
        val remainingCandidates = candidateFiles.filterNot { duplicatePaths.contains(it.path) }

        // Category 1: Old APK and Installer files (highest replaceability)
        val installers = remainingCandidates.filter { it.category == FileCategory.INSTALLER }
            .sortedByDescending { it.sizeBytes }

        // Category 2: Duplicate copies
        val duplicateCategoryItems = duplicateDeleteItems.sortedByDescending { it.sizeBytes }

        // Category 3: Stale archive files (.zip, .rar, .7z)
        val staleArchives = remainingCandidates.filter {
            it.category == FileCategory.ARCHIVE && (now - it.lastModifiedEpochMs > STALE_DAYS_MS)
        }.sortedByDescending { it.sizeBytes }

        // Category 4: Large stale videos (> 100 MB, > 60 days old)
        val largeVideos = remainingCandidates.filter {
            it.category == FileCategory.VIDEO &&
                    it.sizeBytes > (100L * 1024L * 1024L) &&
                    (now - it.lastModifiedEpochMs > STALE_DAYS_MS)
        }.sortedByDescending { it.sizeBytes }

        val categories = mutableListOf<CleanupCategory>()

        if (installers.isNotEmpty()) {
            categories.add(
                CleanupCategory(
                    title = "Unused Installers & APKs",
                    description = "Installation files that have already been installed or downloaded",
                    items = installers,
                    priority = 4
                )
            )
        }

        if (duplicateCategoryItems.isNotEmpty()) {
            categories.add(
                CleanupCategory(
                    title = "Exact Duplicate Files",
                    description = "Redundant copies; latest versions will be preserved",
                    items = duplicateCategoryItems,
                    priority = 3
                )
            )
        }

        if (staleArchives.isNotEmpty()) {
            categories.add(
                CleanupCategory(
                    title = "Old Archive Packages",
                    description = "Zip and compressed files untouched for over 60 days",
                    items = staleArchives,
                    priority = 2
                )
            )
        }

        if (largeVideos.isNotEmpty()) {
            categories.add(
                CleanupCategory(
                    title = "Large Stale Videos",
                    description = "Videos over 100 MB not viewed or modified recently",
                    items = largeVideos,
                    priority = 1
                )
            )
        }

        // Greedily collect items up to targetBytes, honoring priority order
        val selectedItems = mutableListOf<FileItem>()
        var accumulatedBytes = 0L

        for (category in categories.sortedByDescending { it.priority }) {
            for (item in category.items) {
                if (accumulatedBytes >= targetBytes) break
                selectedItems.add(item)
                accumulatedBytes += item.sizeBytes
            }
            if (accumulatedBytes >= targetBytes) break
        }

        val totalReclaimable = categories.sumOf { it.totalBytes }

        return CleanupPlan(
            requestedBytes = targetBytes,
            totalReclaimableBytes = totalReclaimable,
            categories = categories,
            protectedFilesCount = protectedCount
        )
    }
}
