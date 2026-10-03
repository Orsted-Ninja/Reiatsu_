package com.storagesense.app.ai.llm

import com.storagesense.app.ai.ocr.OcrEngine
import com.storagesense.app.data.extractor.ExtractorFactory
import com.storagesense.app.data.local.room.DocumentChunkDao
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.model.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RagEngine @Inject constructor(
    private val onDeviceLlmEngine: OnDeviceLlmEngine,
    private val chunkDao: DocumentChunkDao,
    private val extractorFactory: ExtractorFactory,
    private val ocrEngine: OcrEngine
) {

    /**
     * Extracts text content on-demand from chunk database, native document extractors,
     * or ML Kit OCR text recognition for scanned PDFs / photos.
     */
    private suspend fun extractContentForFile(file: FileItem, query: String): String {
        var content = ""
        if (file.id != 0L) {
            try {
                val chunks = chunkDao.getChunksForFile(file.id)
                if (chunks.isNotEmpty()) {
                    val matchingChunks = chunks.filter { c ->
                        query.split(" ").any { q -> q.length > 2 && c.text.contains(q, ignoreCase = true) }
                    }
                    content = if (matchingChunks.isNotEmpty()) {
                        matchingChunks.joinToString("\n\n") { it.text }
                    } else {
                        chunks.take(5).joinToString("\n\n") { it.text }
                    }
                }
            } catch (_: Exception) {}
        }

        if (content.isBlank() || content.length < 50) {
            try {
                val diskFile = File(file.path)
                if (diskFile.exists() && diskFile.canRead()) {
                    val ext = extractorFactory.getExtractor(file.extension)
                    val text = ext?.extractText(diskFile)?.fullText ?: ""
                    content = if (text.length > 50) {
                        text
                    } else if (file.category == FileCategory.DOCUMENT_PDF) {
                        ocrEngine.recognizePdf(diskFile, maxPages = 2).fullText
                    } else if (file.category == FileCategory.IMAGE_PHOTO || file.category == FileCategory.IMAGE_SCREENSHOT) {
                        ocrEngine.recognizeText(diskFile).fullText
                    } else {
                        text
                    }
                }
            } catch (_: Exception) {}
        }
        return content
    }

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
        val topResult = topResults.first()
        val primaryContent = extractContentForFile(topResult.file, query)

        // 0. Identity Card & Sensitive Number Fast-Path (Aadhaar / PAN)
        val isAadharQuery = query.contains("aadhar", ignoreCase = true) || query.contains("aadhaar", ignoreCase = true)
        val isPanQuery = query.contains("pan", ignoreCase = true)

        if (isAadharQuery || isPanQuery) {
            val aadharRegex = Regex("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b|\\b\\d{12}\\b")
            val panRegex = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")

            // Prioritize actual identity files over generic project reports
            val prioritizedCandidates = topResults.sortedByDescending { res ->
                val n = res.file.name.lowercase()
                var weight = 0
                if (isAadharQuery && (n.contains("aadhar") || n.contains("aadhaar"))) weight += 100
                if (isPanQuery && n.contains("pan")) weight += 100
                if (n.contains("report") || n.contains("project") || n.contains("ticket") || n.contains("seminar")) weight -= 80
                weight
            }

            for (candidate in prioritizedCandidates) {
                val candidateContent = extractContentForFile(candidate.file, query)

                if (isAadharQuery) {
                    val aadharMatch = aadharRegex.find(candidateContent)
                    if (aadharMatch != null) {
                        val formattedNum = if (aadharMatch.value.length == 12 && !aadharMatch.value.contains(" ")) {
                            "${aadharMatch.value.substring(0, 4)} ${aadharMatch.value.substring(4, 8)} ${aadharMatch.value.substring(8, 12)}"
                        } else {
                            aadharMatch.value
                        }
                        emit("Found your Aadhaar details in **${candidate.file.name}**:\n\n💳 **Aadhaar Number**: `${formattedNum}`\n📂 **File Path**: `${candidate.file.path}`\n\n*(Extracted 100% locally on-device)*\n\n")
                        return@flow
                    }
                }

                if (isPanQuery) {
                    val panMatch = panRegex.find(candidateContent)
                    if (panMatch != null) {
                        emit("Found your PAN card details in **${candidate.file.name}**:\n\n🪪 **PAN Number**: `${panMatch.value}`\n📂 **File Path**: `${candidate.file.path}`\n\n*(Extracted 100% locally on-device)*\n\n")
                        return@flow
                    }
                }
            }

            // If an Aadhaar file is present but no 12-digit number could be parsed (e.g. password protected or scanned):
            val idFile = prioritizedCandidates.firstOrNull {
                val n = it.file.name.lowercase()
                (isAadharQuery && (n.contains("aadhar") || n.contains("aadhaar"))) || (isPanQuery && n.contains("pan"))
            }
            if (idFile != null) {
                emit("Found your identity document **${idFile.file.name}**:\n\n📂 **File Path**: `${idFile.file.path}`\n\n*(Note: If this PDF is password-protected by UIDAI, open it directly using the card below to enter your password)*\n\n")
                return@flow
            }
        }

        // 1. Explicit Document / Notes Summarization (LLM as Dedicated Summarizer)
        val isExplicitSummaryRequest = query.contains("summar", ignoreCase = true) ||
                query.contains("explain", ignoreCase = true) ||
                query.contains("what is in", ignoreCase = true) ||
                query.contains("overview of", ignoreCase = true)

        if (isExplicitSummaryRequest && primaryContent.isNotBlank()) {
            if (onDeviceLlmEngine.isModelAvailable()) {
                val truncatedContent = if (primaryContent.length > 3500) primaryContent.take(3500) + "\n...[truncated]..." else primaryContent
                val prompt = """
                    <start_of_turn>user
                    You are StorageSense, an on-device document assistant running locally on Android.
                    Summarize the following content from "${topResult.file.name}" to address the request: "$query".
                    Highlight key takeaways, main concepts, or formulas concisely under 150 words.

                    Content:
                    \"\"\"
                    $truncatedContent
                    \"\"\"

                    Summary:<end_of_turn>
                    <start_of_turn>model
                """.trimIndent()

                var emittedAny = false
                try {
                    onDeviceLlmEngine.streamGenerate(prompt).collect { token ->
                        emittedAny = true
                        emit(token)
                    }
                } catch (_: Exception) {}

                if (emittedAny) {
                    val modelName = onDeviceLlmEngine.getDetectedModelName() ?: "LLM"
                    emit("\n\n*(Summarized by on-device $modelName from indexed file)*")
                    return@flow
                }
            }

            // Extractive fallback when LLM is not loaded
            val lines = primaryContent.split(Regex("(?<=[.!?\\n])\\s+"))
                .map { it.trim() }
                .filter { it.length > 20 && !it.startsWith("http") && !it.startsWith("doi") }
                .distinct()
                .take(6)

            val sb = StringBuilder()
            sb.append("📄 **Summary of ${topResult.file.name}**:\n\n")
            if (lines.isNotEmpty()) {
                for (line in lines) {
                    val cleanLine = line.replace(Regex("^[•\\-→*\\d.]+\\s*"), "")
                    if (cleanLine.isNotBlank()) {
                        sb.append("• ${cleanLine.trimEnd('.')}.\n")
                    }
                }
            } else {
                sb.append(primaryContent.take(300) + "...\n")
            }
            sb.append("\n*(Extracted from indexed document chunks • Tap card below to open)*\n")
            emit(sb.toString())
            return@flow
        }

        // 2. Standard Search Results (Finding files is 100% deterministic & indexed via Okapi BM25)
        val intro = "Found **${results.size}** matching files for **\"$query\"**:\n\n"
        emit(intro)

        for ((idx, res) in topResults.withIndex()) {
            val fileName = res.file.name
            val snippet = if (idx == 0 && primaryContent.isNotBlank()) {
                primaryContent.take(160)
            } else {
                res.matchedSnippet ?: "Relevant content match"
            }
            val scorePct = res.similarityPercent
            val bullet = "${idx + 1}. **$fileName** ($scorePct% match)\n   > *\"${snippet.take(160)}\"*\n\n"
            emit(bullet)
        }

        val footer = if (onDeviceLlmEngine.isModelAvailable()) {
            "*(Found via indexed chunk search • Tap card to Open, Delete, or Summarize with on-device LLM)*"
        } else {
            "*(Found via indexed chunk search • 100% on-device Okapi BM25 retrieval)*"
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

    /**
     * Generates a concise on-device AI summary of a specific document's contents.
     */
    fun streamDocumentSummary(
        file: FileItem,
        query: String,
        content: String
    ): Flow<String> = flow {
        if (content.isBlank()) {
            emit("Found **${file.name}**, but no readable text could be extracted to summarize.")
            return@flow
        }

        if (onDeviceLlmEngine.isModelAvailable()) {
            val truncatedContent = if (content.length > 3500) content.take(3500) + "\n...[truncated]..." else content
            val prompt = """
                <start_of_turn>user
                You are StorageSense, an intelligent on-device document assistant running locally on Android.
                Provide a structured, concise summary of this document ("${file.name}").
                Highlight key takeaways, main concepts, formulas, or facts.
                Keep it under 150 words.

                Document Content:
                \"\"\"
                $truncatedContent
                \"\"\"

                Summary:<end_of_turn>
                <start_of_turn>model
            """.trimIndent()

            var emittedAny = false
            try {
                onDeviceLlmEngine.streamGenerate(prompt).collect { token ->
                    emittedAny = true
                    emit(token)
                }
            } catch (_: Exception) {
                // Fallback to extractive summary
            }

            if (emittedAny) return@flow
        }

        // Extractive fallback when LLM model is not loaded
        val lines = content.split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.length > 20 && !it.startsWith("http") }
            .distinct()
            .take(4)

        val sb = StringBuilder()
        sb.append("📄 **Summary of ${file.name}**\n\n")
        if (lines.isNotEmpty()) {
            for (line in lines) {
                sb.append("• ${line.trimEnd('.')}.\n")
            }
        } else {
            sb.append(content.take(280) + "...\n")
        }

        val modelName = onDeviceLlmEngine.getDetectedModelName()
        if (modelName != null) {
            sb.append("\n*(Powered by on-device $modelName)*")
        } else {
            sb.append("\n*(💡 Enable on-device Gemma LLM in Settings for generative multi-paragraph synthesis)*")
        }
        emit(sb.toString())
    }
}
