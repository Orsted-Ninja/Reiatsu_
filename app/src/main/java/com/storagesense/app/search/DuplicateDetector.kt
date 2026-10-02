package com.storagesense.app.search

import com.storagesense.app.ai.clip.MobileCLIPModel
import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.ImageIndexDao
import com.storagesense.app.domain.model.DuplicateGroup
import com.storagesense.app.domain.model.DuplicateType
import com.storagesense.app.domain.model.FileItem
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DuplicateDetector @Inject constructor(
    private val fileMetadataDao: FileMetadataDao,
    private val documentChunkDao: DocumentChunkDao,
    private val imageIndexDao: ImageIndexDao,
    private val textEmbeddingModel: TextEmbeddingModel,
    private val mobileClipModel: MobileCLIPModel
) {
    companion object {
        const val DOCUMENT_SIMILARITY_THRESHOLD = 0.85f
        const val IMAGE_SIMILARITY_THRESHOLD = 0.90f
    }

    suspend fun findExactDuplicates(): List<DuplicateGroup> {
        val filesWithHashes = fileMetadataDao.getDuplicateCandidates().map { it.toDomain() }
        val grouped = filesWithHashes.groupBy { it.sha256Hash ?: "" }.filter { it.key.isNotEmpty() && it.value.size > 1 }

        val duplicateGroups = mutableListOf<DuplicateGroup>()
        for ((_, files) in grouped) {
            val sorted = sortCandidatesByKeepPriority(files)
            val keep = sorted.first()
            val remove = sorted.drop(1)

            duplicateGroups.add(
                DuplicateGroup(
                    groupId = UUID.randomUUID().toString(),
                    type = DuplicateType.EXACT_HASH,
                    similarityScore = 1.0f,
                    keepCandidate = keep,
                    deleteCandidates = remove
                )
            )
        }

        return duplicateGroups
    }

    suspend fun findNearDuplicateDocuments(): List<DuplicateGroup> {
        val chunks = documentChunkDao.getAllChunksWithEmbeddings()
        if (chunks.size < 2) return emptyList()

        val chunksByFile = chunks.groupBy { it.fileId }
        val fileIds = chunksByFile.keys.toList()
        val processedPairs = mutableSetOf<Pair<Long, Long>>()
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for (i in 0 until fileIds.size) {
            for (j in i + 1 until fileIds.size) {
                val fileIdA = fileIds[i]
                val fileIdB = fileIds[j]

                val pair = if (fileIdA < fileIdB) Pair(fileIdA, fileIdB) else Pair(fileIdB, fileIdA)
                if (processedPairs.contains(pair)) continue
                processedPairs.add(pair)

                val chunksA = chunksByFile[fileIdA] ?: continue
                val chunksB = chunksByFile[fileIdB] ?: continue

                var totalSim = 0f
                var comparisons = 0

                for (cA in chunksA) {
                    val embA = cA.embedding ?: continue
                    var maxChunkSim = 0f
                    for (cB in chunksB) {
                        val embB = cB.embedding ?: continue
                        val sim = textEmbeddingModel.cosineSimilarity(embA, embB)
                        if (sim > maxChunkSim) maxChunkSim = sim
                    }
                    totalSim += maxChunkSim
                    comparisons++
                }

                val avgSim = if (comparisons > 0) totalSim / comparisons else 0f
                if (avgSim >= DOCUMENT_SIMILARITY_THRESHOLD) {
                    val fileA = fileMetadataDao.getById(fileIdA)?.toDomain() ?: continue
                    val fileB = fileMetadataDao.getById(fileIdB)?.toDomain() ?: continue

                    val sorted = sortCandidatesByKeepPriority(listOf(fileA, fileB))
                    duplicateGroups.add(
                        DuplicateGroup(
                            groupId = UUID.randomUUID().toString(),
                            type = DuplicateType.NEAR_DOCUMENT,
                            similarityScore = avgSim,
                            keepCandidate = sorted.first(),
                            deleteCandidates = sorted.drop(1)
                        )
                    )
                }
            }
        }

        return duplicateGroups
    }

    suspend fun findNearDuplicateImages(): List<DuplicateGroup> {
        val images = imageIndexDao.getAllWithEmbeddings()
        if (images.size < 2) return emptyList()

        val duplicateGroups = mutableListOf<DuplicateGroup>()
        val processedPairs = mutableSetOf<Pair<Long, Long>>()

        for (i in 0 until images.size) {
            for (j in i + 1 until images.size) {
                val imgA = images[i]
                val imgB = images[j]

                val pair = if (imgA.fileId < imgB.fileId) Pair(imgA.fileId, imgB.fileId) else Pair(imgB.fileId, imgA.fileId)
                if (processedPairs.contains(pair)) continue
                processedPairs.add(pair)

                val embA = imgA.clipEmbedding ?: continue
                val embB = imgB.clipEmbedding ?: continue

                val sim = mobileClipModel.cosineSimilarity(embA, embB)
                if (sim >= IMAGE_SIMILARITY_THRESHOLD) {
                    val fileA = fileMetadataDao.getById(imgA.fileId)?.toDomain() ?: continue
                    val fileB = fileMetadataDao.getById(imgB.fileId)?.toDomain() ?: continue

                    val sorted = sortCandidatesByKeepPriority(listOf(fileA, fileB))
                    duplicateGroups.add(
                        DuplicateGroup(
                            groupId = UUID.randomUUID().toString(),
                            type = DuplicateType.NEAR_IMAGE,
                            similarityScore = sim,
                            keepCandidate = sorted.first(),
                            deleteCandidates = sorted.drop(1)
                        )
                    )
                }
            }
        }

        return duplicateGroups
    }

    /**
     * Determines which file to keep according to rule:
     * 1. Most recently modified (likely has latest edits)
     * 2. If dates equal: larger file (higher quality)
     * 3. If both equal: shorter path (more accessible)
     */
    fun sortCandidatesByKeepPriority(files: List<FileItem>): List<FileItem> {
        return files.sortedWith(
            compareByDescending<FileItem> { it.lastModifiedEpochMs }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.path.length }
        )
    }
}
