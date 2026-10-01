package com.storagesense.app.search

import com.storagesense.app.domain.model.FileCategory
import com.storagesense.app.domain.model.FileItem
import com.storagesense.app.domain.model.SearchResult
import com.storagesense.app.domain.model.SearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrfFusionTest {

    private fun createFile(id: Long, name: String): FileItem {
        return FileItem(
            id = id,
            path = "/storage/emulated/0/Documents/$name",
            name = name,
            extension = "pdf",
            sizeBytes = 1024L,
            lastModifiedEpochMs = 1000L,
            category = FileCategory.DOCUMENT_PDF
        )
    }

    @Test
    fun testRrfFusionCombinesRankedLists() {
        val fileA = createFile(1L, "DBMS_Notes.pdf")
        val fileB = createFile(2L, "SQL_Basics.pdf")
        val fileC = createFile(3L, "Networks.pdf")

        // List 1 (BM25): File A, File B, File C
        val bm25List = listOf(
            SearchResult(file = fileA, score = 0.9f, source = SearchSource.BM25),
            SearchResult(file = fileB, score = 0.8f, source = SearchSource.BM25),
            SearchResult(file = fileC, score = 0.5f, source = SearchSource.BM25)
        )

        // List 2 (Vector): File B, File A
        val vectorList = listOf(
            SearchResult(file = fileB, score = 0.95f, source = SearchSource.VECTOR),
            SearchResult(file = fileA, score = 0.85f, source = SearchSource.VECTOR)
        )

        val fused = RrfFusion.fuse(listOf(bm25List, vectorList), k = 60)

        // Both File A and File B appear in both lists, so they must rank higher than File C
        assertTrue(fused.size >= 2)
        val topIds = fused.map { it.file.id }
        assertTrue(topIds.contains(1L))
        assertTrue(topIds.contains(2L))
        assertTrue(fused.last().file.id == 3L)
    }
}
