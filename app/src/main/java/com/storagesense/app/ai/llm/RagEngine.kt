package com.storagesense.app.ai.llm

import com.storagesense.app.domain.model.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RagEngine @Inject constructor(
    private val onDeviceLlmEngine: OnDeviceLlmEngine
) {

    /**
     * Generates a streaming RAG answer from top retrieved document chunks.
     * When Gemma weights are present on-device, executes pure on-device LLM reasoning.
     * When weights are absent, provides semantic on-device extraction.
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

        // 1. Pure On-Device Gemma LLM Execution (if model file is present)
        if (onDeviceLlmEngine.isModelAvailable()) {
            val contextBuilder = StringBuilder()
            for ((idx, res) in topResults.withIndex()) {
                val fileName = res.file.name
                val snippet = res.matchedSnippet ?: "Relevant content match"
                contextBuilder.append("Document ${idx + 1}: $fileName\nExcerpt: \"$snippet\"\n\n")
            }

            val prompt = """
                <start_of_turn>user
                You are StorageSense, an intelligent on-device personal file assistant running locally on Android.
                Answer the user's question directly and concisely based strictly on these matching files found in storage:

                $contextBuilder
                Question: $query
                Answer:<end_of_turn>
                <start_of_turn>model
            """.trimIndent()

            var emittedAny = false
            try {
                onDeviceLlmEngine.streamGenerate(prompt).collect { token ->
                    emittedAny = true
                    emit(token)
                }
            } catch (_: Exception) {
                // Fallback to semantic synthesizer on error
            }

            if (emittedAny) return@flow
        }

        // 2. On-Device Semantic Extractor (Zero-Cloud, Built-in Fallback)
        val intro = "Found **${results.size}** matching files for **\"$query\"**:\n\n"
        emit(intro)

        for ((idx, res) in topResults.withIndex()) {
            val fileName = res.file.name
            val snippet = res.matchedSnippet ?: "Relevant content match"
            val scorePct = res.similarityPercent
            val bullet = "${idx + 1}. **$fileName** ($scorePct% match)\n   > *\"${snippet.take(160)}\"*\n\n"
            emit(bullet)
        }

        val modelName = onDeviceLlmEngine.getDetectedModelName()
        val footer = if (modelName != null) {
            "*(Powered by on-device $modelName)*"
        } else {
            "*(💡 Place `gemma-2b-it.bin` or `gemma-4-e2b-it.litertlm` in `/sdcard/StorageSense/models/` for full generative on-device LLM reasoning)*"
        }
        emit("\n$footer")
    }

    /**
     * Generates a summary for duplicate detection proposals
     */
    fun streamDuplicateExplanation(count: Int, bytesFormatted: String): Flow<String> = flow {
        val msg = "Found **$count redundant files** consuming **$bytesFormatted** of storage. I've selected the most recent version of each file to keep while strictly protecting important tax, resume, and identity documents."
        emit(msg)
    }

    /**
     * Generates a summary for cleanup proposals
     */
    fun streamCleanupExplanation(targetFormatted: String, reclaimableFormatted: String): Flow<String> = flow {
        val msg = "Analyzed storage to free up **$targetFormatted**. Identified **$reclaimableFormatted** of safe-to-clean items (unused APKs, stale caches, redundant files). Your protected documents are strictly excluded."
        emit(msg)
    }
}
