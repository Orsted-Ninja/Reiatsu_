package com.storagesense.app.ai.face

import kotlin.math.sqrt

class FaceClusterer(private val threshold: Float = 0.6f) {
    
    fun clusterFaces(embeddings: List<Pair<Long, FloatArray>>): Map<Int, List<Long>> {
        val clusters = mutableListOf<MutableList<Pair<Long, FloatArray>>>()
        var currentClusterId = 0
        
        val faceToClusterMap = mutableMapOf<Int, MutableList<Long>>()
        
        for (embedding in embeddings) {
            var bestClusterId = -1
            var highestSimilarity = -1.0f
            
            // Compare with existing clusters (using cluster center or max similarity)
            for ((clusterIdx, clusterList) in clusters.withIndex()) {
                for (clusterFace in clusterList) {
                    val sim = cosineSimilarity(embedding.second, clusterFace.second)
                    if (sim > threshold && sim > highestSimilarity) {
                        highestSimilarity = sim
                        bestClusterId = clusterIdx
                    }
                }
            }
            
            if (bestClusterId != -1) {
                clusters[bestClusterId].add(embedding)
                faceToClusterMap[bestClusterId]?.add(embedding.first)
            } else {
                clusters.add(mutableListOf(embedding))
                faceToClusterMap[currentClusterId] = mutableListOf(embedding.first)
                currentClusterId++
            }
        }
        
        return faceToClusterMap
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (sqrt(normA) * sqrt(normB)))
    }
}
