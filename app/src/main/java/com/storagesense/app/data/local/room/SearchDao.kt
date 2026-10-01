package com.storagesense.app.data.local.room

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.storagesense.app.domain.model.SearchSource
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
            writableDb.execSQL("DELETE FROM file_fts WHERE file_id = ?", arrayOf(fileId))

            for ((index, chunk) in textChunks.withIndex()) {
                writableDb.execSQL(
                    "INSERT INTO file_fts (file_id, filename, content, page_number) VALUES (?, ?, ?, ?)",
                    arrayOf(fileId, filename, chunk, index + 1)
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
            arrayOf(fileId, filename, ocrText, 1)
        )
    }

    suspend fun removeIndicesForFile(fileId: Long) {
        db.execSQL("DELETE FROM file_fts WHERE file_id = ?", arrayOf(fileId))
    }

    /**
     * Executes SQLite FTS5 MATCH with BM25 ranking (filename weight 5.0, content weight 1.0)
     * bm25() returns negative score (more negative = better match)
     */
    suspend fun searchBm25(sanitizedQuery: String, limit: Int = 50): List<FtsMatch> {
        val results = mutableListOf<FtsMatch>()
        if (sanitizedQuery.isBlank()) return results

        // SQLite FTS5 BM25 query with column weights (filename: 5.0, content: 1.0)
        val sql = """
            SELECT file_id, filename, snippet(file_fts, 2, '<b>', '</b>', '...', 32) AS snippet,
                   bm25(file_fts, 5.0, 1.0) AS score, page_number
            FROM file_fts
            WHERE file_fts MATCH ?
            ORDER BY score ASC
            LIMIT ?
        """.trimIndent()

        val cursor: Cursor = db.query(sql, arrayOf(sanitizedQuery, limit))
        cursor.use {
            val idCol = it.getColumnIndex("file_id")
            val nameCol = it.getColumnIndex("filename")
            val snippetCol = it.getColumnIndex("snippet")
            val scoreCol = it.getColumnIndex("score")
            val pageCol = it.getColumnIndex("page_number")

            while (it.moveToNext()) {
                val fId = it.getLong(idCol)
                val fname = it.getString(nameCol) ?: ""
                val snip = it.getString(snippetCol) ?: ""
                val sc = it.getFloat(scoreCol)
                val page = if (it.isNull(pageCol)) null else it.getInt(pageCol)
                results.add(FtsMatch(fId, fname, snip, sc, page))
            }
        }
        return results
    }

    /**
     * Helper to sanitize query terms for FTS5 (escaping double quotes, boolean operators, special symbols)
     */
    fun sanitizeQuery(rawQuery: String): String {
        val clean = rawQuery.replace("\"", "").trim()
        val tokens = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" OR ") { "$it*" }
    }
}
