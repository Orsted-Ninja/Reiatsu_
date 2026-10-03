package com.storagesense.app.ai.face

import kotlin.math.min
import kotlin.math.sqrt

class FaceClusterer(private val threshold: Float = 0.58f) {
    
    /**
     * Groups face embeddings into clusters based on cosine similarity.
     * Each cluster maintains a moving centroid for stability and accurate grouping.
     * Returns a map of clusterId (1-indexed: 1, 2, 3...) to list of face entity IDs.
     */
    fun clusterFaces(embeddings: List<Pair<Long, FloatArray>>): Map<Int, List<Long>> {
        if (embeddings.isEmpty()) return emptyMap()

        data class ClusterInfo(
            val centroid: FloatArray,
            val faceIds: MutableList<Long>,
            var memberCount: Int = 1
        )

        val clusters = mutableListOf<ClusterInfo>()

        for ((faceId, embedding) in embeddings) {
            var bestClusterIdx = -1
            var bestSimilarity = -1.0f

            for ((idx, cluster) in clusters.withIndex()) {
                val sim = cosineSimilarity(embedding, cluster.centroid)
                if (sim >= threshold && sim > bestSimilarity) {
                    bestSimilarity = sim
                    bestClusterIdx = idx
                }
            }

            if (bestClusterIdx != -1) {
                val cluster = clusters[bestClusterIdx]
                cluster.faceIds.add(faceId)
                // Update centroid with moving average
                val n = cluster.memberCount + 1
                for (i in cluster.centroid.indices) {
                    cluster.centroid[i] = (cluster.centroid[i] * cluster.memberCount + embedding[i]) / n
                }
                normalizeInPlace(cluster.centroid)
                cluster.memberCount = n
            } else {
                val newCentroid = embedding.clone()
                normalizeInPlace(newCentroid)
                clusters.add(ClusterInfo(newCentroid, mutableListOf(faceId), 1))
            }
        }

        // Return 1-based cluster IDs (Person 1, Person 2, ...)
        val result = mutableMapOf<Int, List<Long>>()
        for ((idx, cluster) in clusters.withIndex()) {
            result[idx + 1] = cluster.faceIds
        }
        return result
    }

    private fun normalizeInPlace(v: FloatArray) {
        var sumSquares = 0f
        for (x in v) sumSquares += x * x
        val norm = sqrt(sumSquares)
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}
