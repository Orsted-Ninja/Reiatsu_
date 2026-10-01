package com.storagesense.app.ai.llm

import com.google.gson.Gson
import com.storagesense.app.domain.model.KeepStrategy
import com.storagesense.app.domain.model.StorageIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentParserTest {

    private val parser = IntentParser(Gson())

    @Test
    fun testParseCleanupQuery() {
        val intent = parser.parse("Free up 5 GB without deleting important")
        assertTrue(intent is StorageIntent.Cleanup)
        val cleanup = intent as StorageIntent.Cleanup
        assertEquals(5L * 1024L * 1024L * 1024L, cleanup.targetBytes)
        assertTrue(cleanup.protectImportant)
    }

    @Test
    fun testParseDuplicateQuery() {
        val intent = parser.parse("Remove duplicate assignments, keep latest")
        assertTrue(intent is StorageIntent.Deduplicate)
        val dedup = intent as StorageIntent.Deduplicate
        assertEquals(KeepStrategy.KEEP_LATEST, dedup.keepStrategy)
    }

    @Test
    fun testParseSearchQuery() {
        val intent = parser.parse("Find all my DBMS notes")
        assertTrue(intent is StorageIntent.Search)
        val search = intent as StorageIntent.Search
        assertTrue(search.query.contains("DBMS", ignoreCase = true))
    }

    @Test
    fun testParseJsonLlmOutput() {
        val json = """{"action": "SEARCH", "query": "Kubernetes configuration", "is_image": false}"""
        val intent = parser.parse(json)
        assertTrue(intent is StorageIntent.Search)
        val search = intent as StorageIntent.Search
        assertEquals("Kubernetes configuration", search.query)
    }
}
