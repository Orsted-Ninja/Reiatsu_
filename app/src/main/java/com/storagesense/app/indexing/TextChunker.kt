package com.storagesense.app.indexing

import javax.inject.Inject
import javax.inject.Singleton

data class TextChunk(
    val index: Int,
    val text: String,
    val pageNumber: Int? = null
)

@Singleton
class TextChunker @Inject constructor() {

    companion object {
        // Approximate 400 tokens ~ 1600 characters
        const val DEFAULT_CHUNK_SIZE_CHARS = 1600
        // Approximate 80 tokens ~ 320 characters
        const val DEFAULT_OVERLAP_CHARS = 320
    }

    /**
     * Splits text into overlapping chunks using recursive character splitting.
     * Order of split delimiters: \n\n -> \n -> . -> ' '
     */
    fun chunk(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE_CHARS,
        overlap: Int = DEFAULT_OVERLAP_CHARS,
        pageNumber: Int? = null
    ): List<TextChunk> {
        if (text.isBlank()) return emptyList()
        val cleaned = text.trim()
        if (cleaned.length <= chunkSize) {
            return listOf(TextChunk(0, cleaned, pageNumber))
        }

        val chunks = mutableListOf<String>()
        var startIndex = 0

        while (startIndex < cleaned.length) {
            var endIndex = (startIndex + chunkSize).coerceAtMost(cleaned.length)

            // If not at the end of the text, look for a natural break point backwards
            if (endIndex < cleaned.length) {
                val window = cleaned.substring(startIndex, endIndex)
                val breakOffset = findBreakPoint(window)
                if (breakOffset > (chunkSize / 2)) {
                    endIndex = startIndex + breakOffset
                }
            }

            val chunkText = cleaned.substring(startIndex, endIndex).trim()
            if (chunkText.isNotEmpty()) {
                chunks.add(chunkText)
            }

            if (endIndex >= cleaned.length) break
            // Move forward by chunkSize - overlap
            val step = (endIndex - startIndex - overlap).coerceAtLeast(1)
            startIndex += step
        }

        return chunks.mapIndexed { index, content ->
            TextChunk(index, content, pageNumber)
        }
    }

    private fun findBreakPoint(text: String): Int {
        // Try paragraph break
        val pBreak = text.lastIndexOf("\n\n")
        if (pBreak != -1) return pBreak + 2

        // Try single newline
        val nBreak = text.lastIndexOf('\n')
        if (nBreak != -1) return nBreak + 1

        // Try sentence end
        val sBreak = text.lastIndexOf(". ")
        if (sBreak != -1) return sBreak + 2

        // Try whitespace
        val wBreak = text.lastIndexOf(' ')
        if (wBreak != -1) return wBreak + 1

        return text.length
    }
}
