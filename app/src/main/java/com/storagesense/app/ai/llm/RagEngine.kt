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
            val digest = buildRepresentativeDigest(primaryContent)

            if (onDeviceLlmEngine.isModelAvailable()) {
                val prompt = """
                    <start_of_turn>user
                    You are Reiatsu, an intelligent on-device document assistant running locally on Android.
                    Summarize the following representative content from "${topResult.file.name}" to address the request: "$query".
                    Structure your summary clearly:
                    1. 📌 Overview & Core Objective
                    2. 🔑 Key Concepts & Findings
                    3. 💡 Main Takeaways
                    Keep the response concise and under 200 words.

                    Content Digest:
                    \"\"\"
                    $digest
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

            // Structured generator fallback when LLM is not loaded
            val summaryText = generateStructuredSummary(topResult.file, primaryContent)
            emit(summaryText)
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
     * Takes introductory sections ("a few of the top") plus key body and conclusion excerpts.
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

        val digest = buildRepresentativeDigest(content)

        if (onDeviceLlmEngine.isModelAvailable()) {
            val prompt = """
                <start_of_turn>user
                You are Reiatsu, an intelligent on-device document assistant running locally on Android.
                Provide a structured, comprehensive summary of this document ("${file.name}").
                Structure your response into:
                1. 📌 Overview & Core Objective
                2. 🔑 Key Concepts, Findings & Details
                3. 💡 Main Takeaways
                Keep it concise and under 200 words.

                Document Digest:
                \"\"\"
                $digest
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
                // Fallback to structured extractive summary
            }

            if (emittedAny) {
                val modelName = onDeviceLlmEngine.getDetectedModelName() ?: "LLM"
                emit("\n\n*(Summarized by on-device $modelName)*")
                return@flow
            }
        }

        // Structured synthesis generator when LLM is not loaded
        val summaryText = generateStructuredSummary(file, content)
        emit(summaryText)
    }

    /**
     * Intelligently samples from the document:
     * - Top introductory section (~1400 chars) for title, abstract, and core objective
     * - Core middle section (~1200 chars) for body findings and methods
     * - Concluding section (~1000 chars) for final takeaways and results
     */
    private fun buildRepresentativeDigest(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.length <= 3600) return trimmed

        val topPortion = trimmed.take(1400).trimEnd()
        val remaining = trimmed.drop(1400)

        val middleStart = (remaining.length / 2 - 600).coerceAtLeast(0)
        val middleEnd = (middleStart + 1200).coerceAtMost(remaining.length)
        val middlePortion = if (middleEnd > middleStart) {
            remaining.substring(middleStart, middleEnd).trim()
        } else ""

        val endPortion = trimmed.takeLast(1000).trimStart()

        val sb = StringBuilder()
        sb.append(topPortion)
        if (middlePortion.isNotBlank()) {
            sb.append("\n\n[... Core Body Excerpt ...]\n")
            sb.append(middlePortion)
        }
        if (endPortion.isNotBlank()) {
            sb.append("\n\n[... Concluding Summary & Takeaways ...]\n")
            sb.append(endPortion)
        }
        return sb.toString()
    }

    /**
     * Generates a high-quality, structured executive summary when the LLM is not loaded.
     * Takes top introductory context and key informational sentences across the entire document.
     */
    private fun generateStructuredSummary(file: FileItem, content: String): String {
        val sentences = content.split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { line ->
                line.length in 25..350 &&
                        !line.startsWith("http", ignoreCase = true) &&
                        !line.startsWith("doi:", ignoreCase = true) &&
                        !line.contains("Page ", ignoreCase = true) &&
                        !line.contains("All rights reserved", ignoreCase = true)
            }
            .distinct()

        val sb = StringBuilder()
        sb.append("📄 **Executive Summary: ${file.name}**\n\n")

        // 1. Overview: Pick 1-2 clean sentences from the top
        val topSentences = sentences.take(4).map { it.replace(Regex("^[•\\-→*\\d.]+\\s*"), "").trim() }
            .filter { it.isNotBlank() }
        val overview = if (topSentences.isNotEmpty()) {
            topSentences.take(2).joinToString(" ") { it.trimEnd('.') + "." }
        } else {
            content.take(200).trim() + "..."
        }

        sb.append("📌 **Overview & Core Objective**:\n")
        sb.append("$overview\n\n")

        // 2. Key Highlights: Informative scoring across body & conclusion
        val keyKeywords = listOf(
            "propose", "method", "system", "result", "found", "show", "analy", "key",
            "feature", "design", "model", "develop", "conclud", "importan", "percent", "%",
            "increas", "decreas", "benefit", "requir", "evaluat", "perform", "solut"
        )

        val candidateSentences = sentences.drop(2)
        val scored = candidateSentences.map { s ->
            val lower = s.lowercase()
            var score = 0
            for (kw in keyKeywords) {
                if (lower.contains(kw)) score += 2
            }
            if (s.any { it.isDigit() }) score += 1
            if (s.startsWith("•") || s.startsWith("-") || s.startsWith("*")) score += 2
            Pair(s, score)
        }.sortedByDescending { it.second }

        val highlights = scored.map { it.first }
            .map { it.replace(Regex("^[•\\-→*\\d.]+\\s*"), "").trim() }
            .filter { it.isNotBlank() && it.length > 30 }
            .distinct()
            .take(4)

        if (highlights.isNotEmpty()) {
            sb.append("🔑 **Key Highlights & Main Takeaways**:\n")
            for (h in highlights) {
                sb.append("• ${h.trimEnd('.')}.\n")
            }
            sb.append("\n")
        }

        // 3. Document Scope & Guidance
        sb.append("📊 **Document Scope**:\n")
        sb.append("• Location: `${file.path}` (${file.formattedSize})\n")

        val modelName = onDeviceLlmEngine.getDetectedModelName()
        if (modelName != null) {
            sb.append("*(Synthesized with on-device $modelName)*\n")
        } else {
            sb.append("*(💡 Copy a Gemma `.litertlm` or `.bin` model into `/sdcard/StorageSense/models/` for full multi-paragraph generative reasoning)*\n")
        }

        return sb.toString()
    }
}
