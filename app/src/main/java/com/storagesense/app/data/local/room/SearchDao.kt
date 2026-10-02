package com.storagesense.app.data.local.room

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

data class FtsMatch(
    val fileId: Long,
    val filename: String,
    val snippet: String,
    val bm25Score: Float,
    val pageNumber: Int?
)

@Singleton
class SearchDao @Inject constructor(
    private val database: StorageSenseDatabase
) {
    private val db: SupportSQLiteDatabase
        get() = database.openHelper.writableDatabase

    suspend fun indexDocumentText(fileId: Long, filename: String, textChunks: List<String>) {
        val writableDb = db
        writableDb.beginTransaction()
        try {
            // Remove previous FTS entries for this file
            writableDb.execSQL("DELETE FROM file_fts WHERE file_id = ?", arrayOf(fileId.toString()))

            for ((index, chunk) in textChunks.withIndex()) {
                writableDb.execSQL(
                    "INSERT INTO file_fts (file_id, filename, content, page_number) VALUES (?, ?, ?, ?)",
                    arrayOf(fileId.toString(), filename, chunk, (index + 1).toString())
                )
            }
            writableDb.setTransactionSuccessful()
        } finally {
            writableDb.endTransaction()
        }
    }

    suspend fun indexImageOcr(fileId: Long, filename: String, ocrText: String) {
        val writableDb = db
        writableDb.execSQL(
            "INSERT INTO file_fts (file_id, filename, content, page_number) VALUES (?, ?, ?, ?)",
            arrayOf(fileId.toString(), filename, ocrText, "1")
        )
    }

    suspend fun removeIndicesForFile(fileId: Long) {
        db.execSQL("DELETE FROM file_fts WHERE file_id = ?", arrayOf(fileId.toString()))
    }

    suspend fun cleanupOrphanFts() {
        try {
            db.execSQL("DELETE FROM file_fts WHERE CAST(file_id AS INTEGER) NOT IN (SELECT id FROM file_metadata)")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var cachedTotalDocs: Long = 0L
    private var lastDocCountTimestamp: Long = 0L

    private fun getTotalDocCount(): Long {
        val now = System.currentTimeMillis()
        if (cachedTotalDocs > 0 && (now - lastDocCountTimestamp) < 30_000L) {
            return cachedTotalDocs
        }
        return try {
            val cursor = db.query("SELECT count(*) FROM file_fts", emptyArray())
            cursor.use {
                if (it.moveToFirst()) {
                    cachedTotalDocs = it.getLong(0).coerceAtLeast(1L)
                    lastDocCountTimestamp = now
                }
            }
            cachedTotalDocs.coerceAtLeast(1L)
        } catch (e: Exception) {
            cachedTotalDocs.coerceAtLeast(100L)
        }
    }

    /**
     * Executes multi-stage precision search:
     * 1. Direct file_metadata filename LIKE match (score: 50.0+)
     * 2. High-precision FTS4 AND match (token1* token2*...) with BM25 matchinfo('pcx')
     * 3. Candidate pool expansion and score fusion (INNER JOIN on file_metadata eliminates orphans)
     */
    suspend fun searchBm25(rawQuery: String, limit: Int = 50): List<FtsMatch> {
        val resultsMap = mutableMapOf<Long, FtsMatch>()
        val stopWords = setOf(
            "find", "search", "show", "get", "list", "locate", "display",
            "what", "where", "which", "who", "whom", "how", "tell", "me",
            "my", "your", "our", "their", "the", "a", "an", "is", "are",
            "was", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "can", "could", "should", "would",
            "in", "on", "at", "to", "for", "of", "with", "by", "from",
            "and", "or", "not", "all", "please", "summarize",
            "summary", "give", "about", "details", "info", "information"
        )
        // File-extension and storage container words that should not pollute content matching
        val genericWords = setOf("file", "files", "document", "documents", "pdf", "image", "copy")

        val lowerRaw = rawQuery.lowercase()
        val isNotesQuery = lowerRaw.contains("note") || lowerRaw.contains("module") ||
                lowerRaw.contains("lecture") || lowerRaw.contains("unit") || lowerRaw.contains("syllabus")
        val isAadharQuery = lowerRaw.contains("aadhar") || lowerRaw.contains("aadhaar")
        val isPanQuery = lowerRaw.contains("pan")
        val isIdentityQuery = isAadharQuery || isPanQuery || lowerRaw.contains("passport") ||
                lowerRaw.contains("voter") || lowerRaw.contains("license") || lowerRaw.contains("licence")

        val clean = rawQuery.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ").trim()
        val allTokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        val filtered = allTokens.filter { it.lowercase() !in stopWords }
        val tokens = if (filtered.isNotEmpty()) filtered else allTokens
        if (tokens.isEmpty()) return emptyList()

        val coreTokens = tokens.filter { it.lowercase() !in genericWords }
        val searchTokens = if (coreTokens.isNotEmpty()) coreTokens else tokens

        // Acronym & educational expansion: "deep learning" -> DL / FDL
        val hasDeepLearning = searchTokens.any { it.equals("deep", ignoreCase = true) } &&
                searchTokens.any { it.equals("learning", ignoreCase = true) }

        // Stage 1: Direct file_metadata filename search
        try {
            if (isAadharQuery) {
                val aadharSql = "SELECT id, name, path FROM file_metadata WHERE (name LIKE '%aadhar%' OR name LIKE '%aadhaar%') LIMIT 10"
                val aadharCursor = db.query(aadharSql, emptyArray())
                aadharCursor.use {
                    val idCol = it.getColumnIndex("id")
                    val nameCol = it.getColumnIndex("name")
                    while (it.moveToNext()) {
                        val fid = it.getLong(idCol)
                        val fname = it.getString(nameCol) ?: ""
                        resultsMap[fid] = FtsMatch(
                            fileId = fid,
                            filename = fname,
                            snippet = "Identity Document: $fname",
                            bm25Score = 250.0f,
                            pageNumber = 1
                        )
                    }
                }
            }

            if (isNotesQuery && hasDeepLearning) {
                val dlSql = "SELECT id, name, path FROM file_metadata WHERE (name LIKE '%DL%MOD%' OR name LIKE '%FDL%' OR name LIKE '%deep%learning%') LIMIT 20"
                val dlCursor = db.query(dlSql, emptyArray())
                dlCursor.use {
                    val idCol = it.getColumnIndex("id")
                    val nameCol = it.getColumnIndex("name")
                    while (it.moveToNext()) {
                        val fid = it.getLong(idCol)
                        val fname = it.getString(nameCol) ?: ""
                        val nameLower = fname.lowercase()
                        var score = 160.0f
                        if (nameLower.contains("mod") || nameLower.contains("module") || nameLower.contains("unit") || nameLower.contains("lecture") || nameLower.contains("note")) {
                            score += 70.0f
                        }
                        if (fname.startsWith("1-s2.0") || nameLower.contains("journal")) {
                            score = 20.0f
                        }
                        resultsMap[fid] = FtsMatch(
                            fileId = fid,
                            filename = fname,
                            snippet = "Course Notes: $fname",
                            bm25Score = score,
                            pageNumber = 1
                        )
                    }
                }
            }

            // General filename LIKE matching
            val likeClauses = mutableListOf<String>()
            val likeParams = mutableListOf<String>()
            for (t in searchTokens) {
                val lower = t.lowercase()
                if (lower == "aadhar" || lower == "aadhaar") {
                    likeClauses.add("(name LIKE ? OR name LIKE ?)")
                    likeParams.add("%aadhar%")
                    likeParams.add("%aadhaar%")
                } else {
                    likeClauses.add("name LIKE ?")
                    likeParams.add("%$lower%")
                }
            }
            if (likeClauses.isNotEmpty()) {
                val metaSql = "SELECT id, name, path FROM file_metadata WHERE ${likeClauses.joinToString(" AND ")} LIMIT 30"
                val metaCursor = db.query(metaSql, likeParams.toTypedArray())
                metaCursor.use {
                    val idCol = it.getColumnIndex("id")
                    val nameCol = it.getColumnIndex("name")
                    while (it.moveToNext()) {
                        val fid = it.getLong(idCol)
                        val fname = it.getString(nameCol) ?: ""
                        val nameLower = fname.lowercase()
                        var score = 50.0f
                        if (isNotesQuery && (nameLower.contains("mod") || nameLower.contains("module") || nameLower.contains("unit") || nameLower.contains("lecture") || nameLower.contains("note"))) {
                            score += 80.0f
                        }
                        if (isIdentityQuery && (nameLower.contains("aadhar") || nameLower.contains("aadhaar") || nameLower.contains("pan"))) {
                            score += 150.0f
                        }
                        val existing = resultsMap[fid]
                        if (existing == null || score > existing.bm25Score) {
                            resultsMap[fid] = FtsMatch(
                                fileId = fid,
                                filename = fname,
                                snippet = "Filename match: $fname",
                                bm25Score = score,
                                pageNumber = 1
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Stage 2: Precision FTS4 search (AND query)
        val totalDocs = getTotalDocCount()
        val ftsParts = mutableListOf<String>()
        for (t in searchTokens) {
            val lower = t.lowercase()
            if (lower == "aadhar" || lower == "aadhaar") {
                ftsParts.add("(aadhar* OR aadhaar*)")
            } else if (hasDeepLearning && (lower == "deep" || lower == "learning")) {
                ftsParts.add("(deep* OR DL*)")
            } else if (isNotesQuery && (lower == "note" || lower == "notes")) {
                ftsParts.add("(note* OR mod* OR module* OR unit* OR lecture*)")
            } else {
                ftsParts.add("$lower*")
            }
        }
        val andQuery = ftsParts.joinToString(" ")

        val sqlAnd = """
            SELECT fts.file_id, fts.filename, snippet(file_fts, '<b>', '</b>', '...', -1, 32) AS snippet,
                   matchinfo(file_fts, 'pcx') AS match_data, fts.page_number
            FROM file_fts fts
            INNER JOIN file_metadata meta ON meta.id = CAST(fts.file_id AS INTEGER)
            WHERE file_fts MATCH ?
            LIMIT 120
        """.trimIndent()

        try {
            val cursor: Cursor = db.query(sqlAnd, arrayOf(andQuery))
            cursor.use {
                val idCol = it.getColumnIndex("file_id")
                val nameCol = it.getColumnIndex("filename")
                val snippetCol = it.getColumnIndex("snippet")
                val dataCol = it.getColumnIndex("match_data")
                val pageCol = it.getColumnIndex("page_number")

                while (it.moveToNext()) {
                    val fId = it.getString(idCol)?.toLongOrNull() ?: 0L
                    val fname = it.getString(nameCol) ?: ""
                    val snip = it.getString(snippetCol) ?: ""
                    val matchBytes = if (!it.isNull(dataCol)) it.getBlob(dataCol) else null
                    var score = calculateScore(matchBytes, totalDocs)
                    val page = if (it.isNull(pageCol)) null else it.getString(pageCol)?.toIntOrNull()

                    val nameLower = fname.lowercase()
                    if (isNotesQuery) {
                        if (nameLower.contains("mod") || nameLower.contains("module") || nameLower.contains("unit") || nameLower.contains("lecture") || nameLower.contains("note")) {
                            score += 120.0f
                        }
                        if (nameLower.contains("dl") || nameLower.contains("fdl")) {
                            score += 50.0f
                        }
                        if (fname.startsWith("1-s2.0") || nameLower.contains("arxiv") || nameLower.contains("journal") || nameLower.contains("proceedings") || nameLower.contains("ieee")) {
                            score -= 100.0f
                        }
                    }

                    if (isIdentityQuery) {
                        if (nameLower.contains("aadhar") || nameLower.contains("aadhaar") || nameLower.contains("pan") || nameLower.contains("passport")) {
                            score += 200.0f
                        }
                        if (nameLower.contains("report") || nameLower.contains("project") || nameLower.contains("ticket") || nameLower.contains("irctc") || nameLower.contains("seminar")) {
                            score -= 60.0f
                        }
                    }

                    val existing = resultsMap[fId]
                    if (existing != null) {
                        resultsMap[fId] = existing.copy(
                            bm25Score = existing.bm25Score + score + 20.0f,
                            snippet = snip
                        )
                    } else {
                        resultsMap[fId] = FtsMatch(fId, fname, snip, score + 10.0f, page)
                    }
                }
            }
        } catch (_: Exception) {}

        // Stage 3: If candidate count is low (< 10), run relaxed OR FTS query
        if (resultsMap.size < 10 && ftsParts.size > 1) {
            val orQuery = ftsParts.joinToString(" OR ")
            val sqlOr = """
                SELECT fts.file_id, fts.filename, snippet(file_fts, '<b>', '</b>', '...', -1, 32) AS snippet,
                       matchinfo(file_fts, 'pcx') AS match_data, fts.page_number
                FROM file_fts fts
                INNER JOIN file_metadata meta ON meta.id = CAST(fts.file_id AS INTEGER)
                WHERE file_fts MATCH ?
                LIMIT 150
            """.trimIndent()

            try {
                val cursor: Cursor = db.query(sqlOr, arrayOf(orQuery))
                cursor.use {
                    val idCol = it.getColumnIndex("file_id")
                    val nameCol = it.getColumnIndex("filename")
                    val snippetCol = it.getColumnIndex("snippet")
                    val dataCol = it.getColumnIndex("match_data")
                    val pageCol = it.getColumnIndex("page_number")

                    while (it.moveToNext()) {
                        val fId = it.getString(idCol)?.toLongOrNull() ?: 0L
                        if (!resultsMap.containsKey(fId)) {
                            val fname = it.getString(nameCol) ?: ""
                            val snip = it.getString(snippetCol) ?: ""
                            val matchBytes = if (!it.isNull(dataCol)) it.getBlob(dataCol) else null
                            var score = calculateScore(matchBytes, totalDocs)
                            val page = if (it.isNull(pageCol)) null else it.getString(pageCol)?.toIntOrNull()

                            val nameLower = fname.lowercase()
                            if (isNotesQuery) {
                                if (nameLower.contains("mod") || nameLower.contains("module") || nameLower.contains("unit") || nameLower.contains("lecture") || nameLower.contains("note")) {
                                    score += 100.0f
                                }
                                if (nameLower.contains("dl") || nameLower.contains("fdl")) {
                                    score += 40.0f
                                }
                                if (fname.startsWith("1-s2.0") || nameLower.contains("arxiv") || nameLower.contains("journal")) {
                                    score -= 80.0f
                                }
                            }

                            if (isIdentityQuery) {
                                if (nameLower.contains("aadhar") || nameLower.contains("aadhaar") || nameLower.contains("pan")) {
                                    score += 150.0f
                                }
                                if (nameLower.contains("report") || nameLower.contains("project") || nameLower.contains("ticket") || nameLower.contains("irctc")) {
                                    score -= 50.0f
                                }
                            }

                            resultsMap[fId] = FtsMatch(fId, fname, snip, score, page)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return resultsMap.values.sortedByDescending { it.bm25Score }.take(limit)
    }

    /**
     * Calculates authentic Okapi BM25 relevance score from FTS4 matchinfo('pcx').
     * Incorporates term frequency saturation (k1=1.2), column weights (filename=5.0, content=1.0),
     * and logarithmic Inverse Document Frequency (IDF).
     */
    private fun calculateScore(blob: ByteArray?, totalDocs: Long): Float {
        if (blob == null || blob.size < 8) return 1.0f
        return try {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val p = buffer.int // number of phrases in query
            val c = buffer.int // number of columns (file_id, filename, content, page_number)

            // Weight per column: file_id = 0.0, filename = 5.0, content = 1.0, page_number = 0.0
            val weights = floatArrayOf(0.0f, 5.0f, 1.0f, 0.0f)
            val k1 = 1.2f
            val nDocs = totalDocs.toFloat().coerceAtLeast(1.0f)
            var totalScore = 0.0f

            for (phrase in 0 until p) {
                for (col in 0 until c) {
                    val hitsThisRow = buffer.int
                    val hitsAllRows = buffer.int
                    val docsWithHits = buffer.int

                    val weight = if (col < weights.size) weights[col] else 1.0f
                    if (hitsThisRow > 0 && weight > 0f) {
                        // Okapi BM25 TF saturation
                        val tf = (hitsThisRow * (k1 + 1.0f)) / (hitsThisRow + k1)
                        // Okapi BM25 IDF: ln(1 + (N - n + 0.5) / (n + 0.5))
                        val n = docsWithHits.toFloat().coerceAtLeast(1.0f)
                        val idf = kotlin.math.ln(1.0f + ((nDocs - n + 0.5f) / (n + 0.5f))).coerceAtLeast(0.1f)

                        totalScore += tf * idf * weight
                    }
                }
            }
            if (totalScore <= 0f) 1.0f else totalScore
        } catch (e: Exception) {
            1.0f
        }
    }

    /**
     * Stopword-aware query sanitizer for FTS4.
     * Strips conversational filler, expands key synonyms (aadhar/aadhaar),
     * and builds a balanced match expression.
     */
    fun sanitizeQuery(rawQuery: String): String {
        val stopWords = setOf(
            "find", "search", "show", "get", "list", "locate", "display",
            "what", "where", "which", "who", "whom", "how", "tell", "me",
            "my", "your", "our", "their", "the", "a", "an", "is", "are",
            "was", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "can", "could", "should", "would",
            "in", "on", "at", "to", "for", "of", "with", "by", "from",
            "and", "or", "not", "all", "please", "card", "summarize",
            "summary", "give", "about", "details", "info", "information"
        )
        val clean = rawQuery.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ").trim()
        val allTokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        val filtered = allTokens.filter { it.lowercase() !in stopWords }
        val tokens = if (filtered.isNotEmpty()) filtered else allTokens
        if (tokens.isEmpty()) return ""

        val parts = mutableListOf<String>()
        for (t in tokens) {
            val lower = t.lowercase()
            if (lower == "aadhar" || lower == "aadhaar") {
                parts.add("aadhar* OR aadhaar*")
            } else {
                parts.add("$lower*")
            }
        }
        return parts.joinToString(" OR ")
    }
}
