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
     * Executes SQLite FTS4 MATCH with Okapi BM25 matchinfo('pcx') scoring.
     * Compatible with standard Android SQLite FTS4 virtual tables.
     */
    suspend fun searchBm25(sanitizedQuery: String, limit: Int = 50): List<FtsMatch> {
        val results = mutableListOf<FtsMatch>()
        if (sanitizedQuery.isBlank()) return results

        val totalDocs = getTotalDocCount()

        val sql = """
            SELECT file_id, filename, snippet(file_fts, '<b>', '</b>', '...', -1, 32) AS snippet,
                   matchinfo(file_fts, 'pcx') AS match_data, page_number
            FROM file_fts
            WHERE file_fts MATCH ?
            LIMIT ?
        """.trimIndent()

        try {
            val cursor: Cursor = db.query(sql, arrayOf(sanitizedQuery, limit.toString()))
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
                    val score = calculateScore(matchBytes, totalDocs)
                    val page = if (it.isNull(pageCol)) null else it.getString(pageCol)?.toIntOrNull()
                    results.add(FtsMatch(fId, fname, snip, score, page))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        results.sortByDescending { it.bm25Score }
        return results
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
     * Helper to sanitize query terms for FTS4 (escaping quotes, non-alphanumeric, etc.)
     */
    fun sanitizeQuery(rawQuery: String): String {
        val clean = rawQuery.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ").trim()
        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "$it*" }
    }
}
