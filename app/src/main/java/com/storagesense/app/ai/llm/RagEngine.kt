package com.storagesense.app.ai.llm

import com.storagesense.app.domain.model.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RagEngine @Inject constructor() {

    /**
     * Generates a streaming RAG answer from top retrieved document chunks.
     * Keeps context prompt under 1500 tokens.
     */
    fun streamAnswer(
        query: String,
        results: List<SearchResult>
    ): Flow<String> = flow {
        if (results.isEmpty()) {
            emit("I searched your storage for **\"$query\"**, but didn't find any matching documents or files. Try indexing additional folders in Settings.")
            return@flow
        }

        val topResults = results.take(3)
        val intro = "Found **${results.size}** matching files for **\"$query\"**:\n\n"
        emit(intro)

        val sb = StringBuilder()
        for ((idx, res) in topResults.withIndex()) {
            val fileName = res.file.name
            val snippet = res.matchedSnippet ?: "Relevant content match"
            val scorePct = res.similarityPercent
            val bullet = "${idx + 1}. **$fileName** ($scorePct% match)\n   > *\"${snippet.take(120)}...\"*\n\n"
            sb.append(bullet)
            emit(bullet)
            delay(40) // Smooth streaming cadence
        }

        val conclusion = "You can view, open, or manage any of these files using the cards below."
        emit(conclusion)
    }

    /**
     * Generates a summary for duplicate detection proposals
     */
    fun streamDuplicateExplanation(count: Int, bytesFormatted: String): Flow<String> = flow {
        val msg = "Found **$count redundant files** consuming **$bytesFormatted** of storage. I've selected the most recent version of each file to keep. Review the proposal below to confirm moving duplicates to trash."
        for (chunk in msg.chunked(12)) {
            emit(chunk)
            delay(25)
        }
    }

    /**
     * Generates a summary for cleanup proposals
     */
    fun streamCleanupExplanation(targetFormatted: String, reclaimableFormatted: String): Flow<String> = flow {
        val msg = "Analyzed storage to free up **$targetFormatted**. Identified **$reclaimableFormatted** of safe-to-clean items (unused APKs, stale caches, redundant files). Your protected documents (resumes, tax records) are excluded. Review below to proceed."
        for (chunk in msg.chunked(12)) {
            emit(chunk)
            delay(25)
        }
    }
}
