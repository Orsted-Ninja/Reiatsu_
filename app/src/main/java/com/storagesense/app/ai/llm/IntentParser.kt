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
    private val gson: Gson
) {
    /**
     * Parses natural language user input into a strongly-typed StorageIntent.
     * Supports audits, size filters, category views, recency, deduplication, cleanup, and search.
     */
    fun parse(userInput: String): StorageIntent {
        val raw = userInput.trim()
        val lower = raw.lowercase()

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

        // 4. Category Queries
        when {
            lower.contains("all pdf") || lower.contains("pdf documents") || lower.contains("show pdfs") || lower == "pdfs" -> {
                return StorageIntent.Filter(category = FileCategory.DOCUMENT_PDF, label = "PDF Documents")
            }
            lower.contains("word docs") || lower.contains("word documents") || lower.contains("show docx") -> {
                return StorageIntent.Filter(category = FileCategory.DOCUMENT_WORD, label = "Word Documents")
            }
            lower.contains("presentations") || lower.contains("slides") || lower.contains("show ppt") -> {
                return StorageIntent.Filter(category = FileCategory.DOCUMENT_SLIDES, label = "Presentations")
            }
            lower.contains("apk") || lower.contains("installers") -> {
                return StorageIntent.Filter(category = FileCategory.INSTALLER, label = "APKs & Installers")
            }
            lower.contains("videos") || lower.contains("show video") -> {
                return StorageIntent.Filter(category = FileCategory.VIDEO, label = "Videos")
            }
            lower.contains("screenshots") || lower.contains("show screenshot") -> {
                return StorageIntent.Filter(category = FileCategory.IMAGE_SCREENSHOT, label = "Screenshots")
            }
            lower.contains("photos") || lower.contains("pictures") || lower.contains("images") -> {
                return StorageIntent.Filter(category = FileCategory.IMAGE_PHOTO, label = "Images & Photos")
            }
            lower.contains("archives") || lower.contains("zip files") -> {
                return StorageIntent.Filter(category = FileCategory.ARCHIVE, label = "Archives & Zip files")
            }
        }

        // 4. Recency Queries
        if (lower.contains("recent downloads") || lower.contains("recent files") ||
            lower.contains("downloads from") || lower.contains("from this week") ||
            lower.contains("latest files") || lower.contains("downloaded recently")) {
            return StorageIntent.Filter(recentDays = 7, label = "Recent files (last 7 days)")
        }

        // 5. Cleanup
        if (lower.startsWith("free up") || lower.contains("clean up") || lower.contains("cleanup")) {
            val bytes = parseBytes(lower) ?: (5L * 1024L * 1024L * 1024L) // default 5 GB
            return StorageIntent.Cleanup(
                targetBytes = bytes,
                protectImportant = true
            )
        }

        // 6. Deduplication
        if (lower.contains("duplicate") || lower.contains("dupes") || lower.contains("dedup")) {
            val keepStrategy = when {
                lower.contains("largest") -> KeepStrategy.KEEP_LARGEST
                lower.contains("shortest") -> KeepStrategy.KEEP_SHORTEST_PATH
                else -> KeepStrategy.KEEP_LATEST
            }
            return StorageIntent.Deduplicate(
                targetQuery = extractSubject(raw, listOf("duplicate", "dupes", "deduplicate")),
                keepStrategy = keepStrategy
            )
        }

        // 7. Delete
        if (lower.startsWith("delete ") || lower.startsWith("remove ") || lower.startsWith("trash ")) {
            val query = raw.replace(Regex("^(delete|remove|trash)\\s+", RegexOption.IGNORE_CASE), "").trim()
            val permanent = lower.contains("permanently") || lower.contains("forever")
            return StorageIntent.Delete(
                query = query,
                permanent = permanent
            )
        }

        // 8. Summarize
        if (lower.startsWith("summarize ") || lower.startsWith("what is in ") || lower.startsWith("explain ")) {
            val subject = raw.replace(Regex("^(summarize|what is in|explain)\\s+", RegexOption.IGNORE_CASE), "").trim()
            return StorageIntent.Summarize(
                targetPath = null,
                query = subject
            )
        }

        // 9. JSON string check
        if (raw.startsWith("{") && raw.endsWith("}")) {
            tryParseJson(raw)?.let { return it }
        }

        val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(raw)
        if (jsonMatch != null) {
            tryParseJson(jsonMatch.value)?.let { return it }
        }

        // 10. Default: Content & Semantic Search
        val isImage = lower.contains("screenshot") || lower.contains("photo") ||
                lower.contains("image") || lower.contains("receipt") || lower.contains("picture")

        val cleanedQuery = raw.replace(Regex("^(find|search|show|get|list|display)\\s+(all\\s+)?(my\\s+)?", RegexOption.IGNORE_CASE), "").trim()

        return StorageIntent.Search(
            query = if (cleanedQuery.isNotBlank()) cleanedQuery else raw,
            isImageSearch = isImage,
            naturalLanguageExplanation = "Searching your storage for relevant matches"
        )
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
                    StorageIntent.Deduplicate(targetQuery = query)
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
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseBytes(input: String): Long? {
        val gbMatch = Regex("(\\d+(\\.\\d+)?)\\s*gb", RegexOption.IGNORE_CASE).find(input)
        if (gbMatch != null) {
            val num = gbMatch.groupValues[1].toDoubleOrNull() ?: return null
            return (num * 1024.0 * 1024.0 * 1024.0).toLong()
        }

        val mbMatch = Regex("(\\d+(\\.\\d+)?)\\s*mb", RegexOption.IGNORE_CASE).find(input)
        if (mbMatch != null) {
            val num = mbMatch.groupValues[1].toDoubleOrNull() ?: return null
            return (num * 1024.0 * 1024.0).toLong()
        }

        return null
    }

    private fun extractSubject(input: String, keywords: List<String>): String? {
        var result = input
        for (kw in keywords) {
            result = result.replace(Regex(kw, RegexOption.IGNORE_CASE), "")
        }
        val clean = result.replace(Regex("^(all|my|the|of|for|keep latest|keep|latest|assignments)\\s+", RegexOption.IGNORE_CASE), "").trim()
        return if (clean.length > 2) clean else null
    }
}
