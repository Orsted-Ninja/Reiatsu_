package com.storagesense.app.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    private val chunker = TextChunker()

    @Test
    fun testEmptyInputReturnsEmptyList() {
        val chunks = chunker.chunk("")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun testShortTextReturnsSingleChunk() {
        val text = "Relational database management systems use SQL for structured queries."
        val chunks = chunker.chunk(text, chunkSize = 1000)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
        assertEquals(0, chunks[0].index)
    }

    @Test
    fun testLongTextProducesMultipleChunksWithOverlap() {
        val sb = StringBuilder()
        for (i in 1..20) {
            sb.append("This is sentence number $i about databases and storage management systems.\n\n")
        }
        val text = sb.toString()
        val chunks = chunker.chunk(text, chunkSize = 100, overlap = 20)

        assertTrue(chunks.size > 1)
        for (c in chunks) {
            assertTrue(c.text.isNotBlank())
        }
    }
}
