package com.storagesense.app.ai.embedding

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class TokenizedInput(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TokenizedInput
        if (!inputIds.contentEquals(other.inputIds)) return false
        if (!attentionMask.contentEquals(other.attentionMask)) return false
        if (!tokenTypeIds.contentEquals(other.tokenTypeIds)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        result = 31 * result + tokenTypeIds.contentHashCode()
        return result
    }
}

class WordPieceTokenizer(private val vocab: Map<String, Int>) {

    companion object {
        const val CLS_TOKEN = "[CLS]"
        const val SEP_TOKEN = "[SEP]"
        const val UNK_TOKEN = "[UNK]"
        const val MAX_LENGTH = 128

        fun loadFromStream(inputStream: InputStream): WordPieceTokenizer {
            val vocabMap = mutableMapOf<String, Int>()
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                var index = 0
                var line = reader.readLine()
                while (line != null) {
                    val token = line.trim()
                    if (token.isNotEmpty()) {
                        vocabMap[token] = index++
                    }
                    line = reader.readLine()
                }
            }
            return WordPieceTokenizer(vocabMap)
        }

        fun createFallbackTokenizer(): WordPieceTokenizer {
            // Basic fallback vocabulary for essential subwords if vocab.txt asset is omitted
            val map = mutableMapOf<String, Int>()
            map["[PAD]"] = 0
            map["[UNK]"] = 100
            map["[CLS]"] = 101
            map["[SEP]"] = 102
            map["[MASK]"] = 103
            return WordPieceTokenizer(map)
        }
    }

    private val clsId = vocab[CLS_TOKEN] ?: 101
    private val sepId = vocab[SEP_TOKEN] ?: 102
    private val unkId = vocab[UNK_TOKEN] ?: 100

    fun tokenize(text: String, maxLen: Int = MAX_LENGTH): TokenizedInput {
        val tokens = mutableListOf<Long>()
        tokens.add(clsId.toLong())

        val words = text.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter { it.isNotBlank() }

        for (word in words) {
            if (tokens.size >= maxLen - 1) break
            val subwords = tokenizeWord(word)
            for (sw in subwords) {
                if (tokens.size >= maxLen - 1) break
                tokens.add(sw)
            }
        }

        tokens.add(sepId.toLong())

        val length = tokens.size
        val paddedLength = maxLen.coerceAtLeast(length)

        val inputIds = LongArray(paddedLength)
        val attentionMask = LongArray(paddedLength)
        val tokenTypeIds = LongArray(paddedLength)

        for (i in 0 until length) {
            inputIds[i] = tokens[i]
            attentionMask[i] = 1L
        }

        return TokenizedInput(inputIds, attentionMask, tokenTypeIds)
    }

    private fun tokenizeWord(word: String): List<Long> {
        val result = mutableListOf<Long>()
        var start = 0
        val len = word.length

        while (start < len) {
            var end = len
            var matchedId: Long? = null

            while (start < end) {
                val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) {
                    matchedId = id.toLong()
                    break
                }
                end--
            }

            if (matchedId == null) {
                // Character not in vocab, output UNK and advance
                result.add(unkId.toLong())
                start++
            } else {
                result.add(matchedId)
                start = end
            }
        }

        return result
    }
}
