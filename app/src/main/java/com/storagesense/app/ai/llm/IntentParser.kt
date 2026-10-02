package com.storagesense.app.ai.llm

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.KeepStrategy
import com.storagesense.app.domain.model.StorageIntent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntentParser @Inject constructor(
    private val gson: Gson,
    private val llmEngine: OnDeviceLlmEngine
) {
    /**
     * Parses natural language user input into a strongly-typed StorageIntent.
     * Uses deterministic fast-paths for instant response, and Gemma on-device LLM for complex queries.
     */
    suspend fun parse(userInput: String): StorageIntent {
        val raw = userInput.trim()
        val lower = raw.lowercase()

        // 0. Fast-path Undo & Safety Help Queries
        if (lower.contains("how to undo") || lower.contains("how do i undo") ||
            lower.contains("how can i undo") || lower.contains("how to restore")) {
            return StorageIntent.Help(topic = "undo")
        }

        if (lower == "undo" || lower.startsWith("undo ") || lower.contains("undo deletion") ||
            lower.contains("undo last") || lower.contains("restore last") || lower == "restore" ||
            lower == "revert") {
            return StorageIntent.Undo(query = raw)
        }

        // Fast path for explicit JSON
        if (raw.startsWith("{") && raw.endsWith("}")) {
            tryParseJson(raw)?.let { return it }
        }

        // 1. Storage Audit & Space Breakdown
        if (lower.contains("taking up space") || lower.contains("storage space") ||
            lower.contains("storage stats") || lower.contains("storage breakdown") ||
            lower.contains("storage overview") || lower == "storage" || lower == "stats") {
            return StorageIntent.Audit(showLargest = true)
        }

        // 2. Largest files / Size queries
        if (lower.contains("largest files") || lower.contains("biggest files") ||
            lower.contains("heavy files") || lower.contains("large files") || lower.contains("top files")) {
            return StorageIntent.Filter(minSizeBytes = 0L, label = "Largest files on your device")
        }

        val sizeMatch = Regex("(larger|bigger|greater|>|over)\\s+(than\\s+)?(\\d+(\\.\\d+)?\\s*(gb|mb))", RegexOption.IGNORE_CASE).find(lower)
        if (sizeMatch != null) {
            val bytes = parseBytes(sizeMatch.value)
            if (bytes != null) {
                return StorageIntent.Filter(minSizeBytes = bytes, label = "Files ${sizeMatch.value}")
            }
        }

        // 3. WhatsApp & Messaging Specific Queries
        if (lower.contains("whatsapp") || lower.contains("whats app")) {
            val cat = when {
                lower.contains("pdf") || lower.contains("document") || lower.contains("doc") -> FileCategory.DOCUMENT_PDF
                lower.contains("video") -> FileCategory.VIDEO
                lower.contains("photo") || lower.contains("picture") || lower.contains("image") -> FileCategory.IMAGE_PHOTO
                else -> null
            }
            val title = if (cat != null) "WhatsApp ${cat.name.replace('_', ' ')}" else "WhatsApp Media & Files"
            return StorageIntent.Filter(folderKeyword = "whatsapp", category = cat, label = title)
        }

        if (lower.contains("telegram")) {
            return StorageIntent.Filter(folderKeyword = "telegram", label = "Telegram Downloads & Media")
        }

        // 4. If On-Device Gemma LLM is available, use it for intelligent intent extraction
        if (llmEngine.isModelAvailable()) {
            val prompt = """
You are the StorageSense intent classifier. The user wants to manage their Android files.
Analyze this input: "$raw"
Output ONLY a valid JSON object with an "action" and "query" or other parameters. No markdown formatting or explanation.

Actions:
- SEARCH: User wants to find files (e.g. "find pdfs", "show cat pics") -> {"action": "SEARCH", "query": "pdfs", "is_image": false}
- DEDUPLICATE: User wants to find/remove duplicates (e.g. "dedup downloads") -> {"action": "DEDUPLICATE", "query": "downloads"}
- CLEANUP: User wants to free up space (e.g. "free up 2 gb") -> {"action": "CLEANUP", "target_gb": 2.0}
- DELETE: User wants to delete something specific (e.g. "delete old assignments") -> {"action": "DELETE", "query": "old assignments"}
- SUMMARIZE: User asks about space or stats (e.g. "what is taking up space") -> {"action": "SUMMARIZE", "query": ""}
- UNDO: User wants to reverse a deletion -> {"action": "UNDO", "query": ""}
- AUDIT: User asks for an overview of their storage -> {"action": "AUDIT", "query": ""}

Output JSON:
""".trimIndent()

            var jsonResponse = ""
            try {
                llmEngine.streamGenerate(prompt).collect { chunk ->
                    jsonResponse += chunk
                }
                val cleanJson = jsonResponse.replace("```json", "").replace("```", "").trim()
                tryParseJson(cleanJson)?.let { return it }
            } catch (_: Exception) {
                // Fallback to pattern matching
            }
        }

        // 5. Pattern Match Fallback
        val isDeduplicate = lower.contains("duplicate") || lower.contains("dupe") || lower.contains("dedup")
        val isCleanup = lower.contains("clean") || lower.contains("free up") || lower.contains("clear space")
        val isDelete = lower.startsWith("delete") || lower.startsWith("remove") || lower.startsWith("trash")

        if (isDeduplicate) {
            val cleanQuery = raw.replace(Regex("(?i)(remove|find|delete|show|detect)\\s+(all\\s+)?(duplicate[s]?|dupe[s]?)\\s*(of|in)?"), "").trim()
            return StorageIntent.Deduplicate(targetQuery = cleanQuery.ifEmpty { null })
        }

        if (isCleanup) {
            val targetBytes = parseTargetBytes(raw) ?: (5L * 1024L * 1024L * 1024L)
            return StorageIntent.Cleanup(targetBytes = targetBytes)
        }

        if (isDelete) {
            val query = raw.replace(Regex("(?i)^(delete|remove|trash)\\s+"), "").trim()
            return StorageIntent.Delete(query = query, permanent = false)
        }

        // Clean search query prefix
        val cleanQuery = raw
            .replace(Regex("(?i)^(find|search|show|get|list|locate|display)\\s+(all\\s+)?(my\\s+)?"), "")
            .trim()

        return StorageIntent.Search(query = cleanQuery.ifEmpty { raw })
    }

    private fun tryParseJson(jsonStr: String): StorageIntent? {
        return try {
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)
            val action = obj.get("action")?.asString?.uppercase() ?: "SEARCH"
            val query = obj.get("query")?.asString ?: ""

            when (action) {
                "SEARCH" -> {
                    val isImage = obj.get("is_image")?.asBoolean ?: false
                    StorageIntent.Search(query = query, isImageSearch = isImage)
                }
                "DEDUPLICATE" -> {
                    StorageIntent.Deduplicate(targetQuery = query.ifEmpty { null })
                }
                "CLEANUP" -> {
                    val gb = obj.get("target_gb")?.asFloat ?: 5.0f
                    val targetBytes = (gb * 1024L * 1024L * 1024L).toLong()
                    StorageIntent.Cleanup(targetBytes = targetBytes)
                }
                "DELETE" -> {
                    val permanent = obj.get("permanent")?.asBoolean ?: false
                    StorageIntent.Delete(query = query, permanent = permanent)
                }
                "SUMMARIZE" -> {
                    StorageIntent.Summarize(query = query)
                }
                "AUDIT" -> {
                    StorageIntent.Audit()
                }
                "UNDO" -> {
                    StorageIntent.Undo()
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBytes(text: String): Long? {
        val numMatch = Regex("(\\d+(\\.\\d+)?)\\s*(gb|mb|kb)", RegexOption.IGNORE_CASE).find(text) ?: return null
        val num = numMatch.groupValues[1].toDoubleOrNull() ?: return null
        val unit = numMatch.groupValues[3].uppercase()
        return when (unit) {
            "GB" -> (num * 1024 * 1024 * 1024).toLong()
            "MB" -> (num * 1024 * 1024).toLong()
            "KB" -> (num * 1024).toLong()
            else -> null
        }
    }

    private fun parseTargetBytes(input: String): Long? {
        return parseBytes(input)
    }
}
