package com.storagesense.app.search

import com.storagesense.app.ai.clip.MobileCLIPModel
import com.storagesense.app.ai.embedding.TextEmbeddingModel
import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.data.local.room.FileMetadataDao
import com.storagesense.app.data.local.room.ImageIndexDao
import com.storagesense.app.data.local.room.entity.DocumentChunkEntity
import com.storagesense.app.domain.model.DuplicateGroup
import com.storagesense.app.domain.model.DuplicateType
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.indexing.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

@Singleton
class DuplicateDetector @Inject constructor(
    private val fileMetadataDao: FileMetadataDao,
    private val documentChunkDao: DocumentChunkDao,
    private val imageIndexDao: ImageIndexDao,
    private val textEmbeddingModel: TextEmbeddingModel,
    private val mobileClipModel: MobileCLIPModel,
    private val fileScanner: FileScanner
) {
    companion object {
        const val CENTROID_SIMILARITY_GATE = 0.70f
        const val DOCUMENT_SIMILARITY_THRESHOLD = 0.85f
        const val IMAGE_SIMILARITY_THRESHOLD = 0.90f
    }

    suspend fun findExactDuplicates(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allFiles = fileMetadataDao.getAll().map { it.toDomain() }
        val sizeCandidateGroups = allFiles.filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes }
            .filter { it.value.size > 1 }

        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for ((_, candidateFiles) in sizeCandidateGroups) {
            // Compute hashes on-demand for exact size matches
            val hashedFiles = candidateFiles.map { fileItem ->
                if (fileItem.sha256Hash.isNullOrEmpty()) {
                    val file = java.io.File(fileItem.path)
                    if (file.exists()) {
                        val computedHash = fileScanner.computeSha256(file)
                        val updated = fileItem.copy(sha256Hash = computedHash)
                        fileMetadataDao.insertOrUpdate(com.storagesense.app.data.local.room.entity.FileMetadataEntity.fromDomain(updated))
                        updated
                    } else fileItem
                } else {
                    fileItem
                }
            }
            
            val fastHashGroups = hashedFiles.groupBy { fileItem ->
                fileItem.sha256Hash ?: ""
            }.filter { it.key.isNotEmpty() && it.value.size > 1 }

            for ((_, sameHashFiles) in fastHashGroups) {
                // If they share the exact byte length and the indexer's exact hash, they are exact duplicates
                val fullHashGroups = mapOf(sameHashFiles.first().sha256Hash!! to sameHashFiles)

                for ((_, exactFiles) in fullHashGroups) {
                    val sorted = sortCandidatesByKeepPriority(exactFiles)
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
            }
        }

        duplicateGroups
    }

    suspend fun findNearDuplicateDocuments(): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val chunks = documentChunkDao.getAllChunksWithEmbeddings()
        if (chunks.size < 2) return@withContext emptyList()

        val chunksByFile = chunks.groupBy { it.fileId }
        val fileIds = chunksByFile.keys.toList()

        // 1. Precompute normalized document centroids
        val centroids = mutableMapOf<Long, FloatArray>()
        for ((fileId, fileChunks) in chunksByFile) {
            computeCentroid(fileChunks)?.let { centroids[fileId] = it }
        }

        val processedPairs = mutableSetOf<Pair<Long, Long>>()
        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for (i in 0 until fileIds.size) {
            val fileIdA = fileIds[i]
            val centroidA = centroids[fileIdA] ?: continue
            val chunksA = chunksByFile[fileIdA] ?: continue

            for (j in i + 1 until fileIds.size) {
                val fileIdB = fileIds[j]
                val centroidB = centroids[fileIdB] ?: continue
                val chunksB = chunksByFile[fileIdB] ?: continue

                val pair = if (fileIdA < fileIdB) Pair(fileIdA, fileIdB) else Pair(fileIdB, fileIdA)
                if (processedPairs.contains(pair)) continue
                processedPairs.add(pair)

                // 2. High-speed Centroid Gate: Only evaluate chunks if overall document theme is similar
                val centroidSim = textEmbeddingModel.cosineSimilarity(centroidA, centroidB)
                if (centroidSim < CENTROID_SIMILARITY_GATE) continue

                // 3. Fine-grained chunk alignment
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

        duplicateGroups
    }

    private fun computeCentroid(chunks: List<DocumentChunkEntity>): FloatArray? {
        val validEmbeddings = chunks.mapNotNull { it.embedding }
        if (validEmbeddings.isEmpty()) return null

        val dim = validEmbeddings.first().size
        val centroid = FloatArray(dim)

        for (emb in validEmbeddings) {
            for (k in 0 until dim) {
                centroid[k] += emb[k]
            }
        }

        val count = validEmbeddings.size.toFloat()
        var normSq = 0f
        for (k in 0 until dim) {
            centroid[k] /= count
            normSq += centroid[k] * centroid[k]
        }

        val norm = sqrt(normSq)
        if (norm > 1e-6f) {
            for (k in 0 until dim) {
                centroid[k] /= norm
            }
        }

        return centroid
    }

    suspend fun findNearDuplicateImages(): List<DuplicateGroup> = withContext(Dispatchers.Default) {
        val images = imageIndexDao.getAllWithEmbeddings()
        if (images.size < 2) return@withContext emptyList()

        val duplicateGroups = mutableListOf<DuplicateGroup>()
        val processedPairs = mutableSetOf<Pair<Long, Long>>()

        for (i in 0 until images.size) {
            val imgA = images[i]
            val embA = imgA.clipEmbedding ?: continue

            for (j in i + 1 until images.size) {
                val imgB = images[j]
                val embB = imgB.clipEmbedding ?: continue

                val pair = if (imgA.fileId < imgB.fileId) Pair(imgA.fileId, imgB.fileId) else Pair(imgB.fileId, imgA.fileId)
                if (processedPairs.contains(pair)) continue
                processedPairs.add(pair)

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

        duplicateGroups
    }

    /**
     * Determines which file to keep according to rule:
     * 1. Protected documents (resumes, tax records, IDs) MUST ALWAYS BE KEPT.
     * 2. Most recently modified (likely has latest edits)
     * 3. If dates equal: larger file (higher quality)
     * 4. If both equal: shorter path (more accessible)
     */
    fun sortCandidatesByKeepPriority(files: List<FileItem>): List<FileItem> {
        return files.sortedWith(
            compareByDescending<FileItem> {
                if (it.isImportant || fileScanner.isImportantFile(it.name, it.path)) 1 else 0
            }
                .thenByDescending { it.lastModifiedEpochMs }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.path.length }
        )
    }
}
