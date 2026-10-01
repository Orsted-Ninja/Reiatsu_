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

    /**
     * Executes SQLite FTS4 MATCH with matchinfo('pcx') scoring.
     * Compatible with standard Android SQLite (which does not include FTS5).
     */
    suspend fun searchBm25(sanitizedQuery: String, limit: Int = 50): List<FtsMatch> {
        val results = mutableListOf<FtsMatch>()
        if (sanitizedQuery.isBlank()) return results

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
                    val score = calculateScore(matchBytes)
                    val page = if (it.isNull(pageCol)) null else it.getString(pageCol)?.toIntOrNull()
                    results.add(FtsMatch(fId, fname, snip, score, page))
                }
            }
        } catch (e: Exception) {
            // Log or fallback safely
            e.printStackTrace()
        }

        results.sortByDescending { it.bm25Score }
        return results
    }

    /**
     * Calculates BM25 / TF-IDF style relevance score from FTS4 matchinfo('pcx')
     */
    private fun calculateScore(blob: ByteArray?): Float {
        if (blob == null || blob.size < 8) return 1.0f
        return try {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val p = buffer.int // number of phrases in query
            val c = buffer.int // number of columns (file_id, filename, content, page_number)

            // Weight per column: file_id = 0.0, filename = 5.0, content = 1.0, page_number = 0.0
            val weights = floatArrayOf(0.0f, 5.0f, 1.0f, 0.0f)
            var totalScore = 0.0f

            for (phrase in 0 until p) {
                for (col in 0 until c) {
                    val hitsThisRow = buffer.int
                    val hitsAllRows = buffer.int
                    val docsWithHits = buffer.int

                    val weight = if (col < weights.size) weights[col] else 1.0f
                    if (hitsThisRow > 0) {
                        val tf = hitsThisRow.toFloat() / (hitsThisRow + 1.0f)
                        totalScore += tf * weight
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
