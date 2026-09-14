package com.iaido.core.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the deterministic NKG1 asset emitted by tools/generate_ngrams.py.
 * The byte array can be backed by an Android asset; no n-gram map is built.
 */
class CompactNgramScoreStore private constructor(
    private val bytes: ByteArray,
    private val wordIds: Map<String, Int>,
) : NgramScoreStore {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val bigramIndexStart: Int
    private val trigramIndexStart: Int
    private val bigramEdgesStart: Int
    private val trigramEdgesStart: Int
    private val bigramContexts: Int
    private val trigramContexts: Int
    private val maxLogCount: Double

    init {
        require(bytes.size >= HEADER_SIZE) { "N-gram asset is truncated" }
        require(bytes.copyOfRange(0, 4).contentEquals(MAGIC)) { "Unsupported n-gram asset magic" }
        require(buffer.get(VERSION_OFFSET).toInt() == VERSION) { "Unsupported n-gram asset version" }
        val wordCount = buffer.getInt(WORD_COUNT_OFFSET)
        require(wordCount >= 0) { "Invalid n-gram vocabulary size" }
        bigramContexts = buffer.getInt(BIGRAM_CONTEXT_COUNT_OFFSET)
        trigramContexts = buffer.getInt(TRIGRAM_CONTEXT_COUNT_OFFSET)
        val bigramBytes = buffer.getInt(BIGRAM_EDGE_BYTES_OFFSET)
        val trigramBytes = buffer.getInt(TRIGRAM_EDGE_BYTES_OFFSET)
        maxLogCount = buffer.getDouble(MAX_LOG_COUNT_OFFSET)
        require(bigramContexts >= 0 && trigramContexts >= 0) { "Invalid n-gram context count" }
        require(bigramBytes >= 0 && trigramBytes >= 0) { "Invalid n-gram edge byte count" }
        require(wordIds.values.all { it >= 0 && it < wordCount }) { "Dictionary does not match n-gram asset" }
        bigramIndexStart = HEADER_SIZE
        trigramIndexStart = bigramIndexStart + bigramContexts * BIGRAM_INDEX_SIZE
        bigramEdgesStart = trigramIndexStart + trigramContexts * TRIGRAM_INDEX_SIZE
        trigramEdgesStart = bigramEdgesStart + bigramBytes
        require(trigramEdgesStart + trigramBytes == bytes.size) { "N-gram asset section sizes do not match" }
    }

    override fun bigram(previous: String, next: String): Double {
        val previousId = wordIds[previous] ?: return 0.0
        val nextId = wordIds[next] ?: return 0.0
        val index = findBigram(previousId) ?: return 0.0
        return findEdge(bigramEdgesStart, index.offset, index.length, index.count, nextId)
    }

    override fun trigram(first: String, second: String, next: String): Double {
        val firstId = wordIds[first] ?: return 0.0
        val secondId = wordIds[second] ?: return 0.0
        val nextId = wordIds[next] ?: return 0.0
        val index = findTrigram(firstId, secondId) ?: return 0.0
        return findEdge(trigramEdgesStart, index.offset, index.length, index.count, nextId)
    }

    private fun findBigram(contextId: Int): EdgeIndex? {
        var low = 0
        var high = bigramContexts - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val position = bigramIndexStart + middle * BIGRAM_INDEX_SIZE
            val found = buffer.getInt(position)
            when {
                found < contextId -> low = middle + 1
                found > contextId -> high = middle - 1
                else -> return EdgeIndex(
                    offset = buffer.getInt(position + 4),
                    length = buffer.getInt(position + 8),
                    count = buffer.getShort(position + 12).toInt() and 0xFFFF,
                )
            }
        }
        return null
    }

    private fun findTrigram(firstId: Int, secondId: Int): EdgeIndex? {
        var low = 0
        var high = trigramContexts - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val position = trigramIndexStart + middle * TRIGRAM_INDEX_SIZE
            val foundFirst = buffer.getInt(position)
            val foundSecond = buffer.getInt(position + 4)
            when {
                foundFirst < firstId || (foundFirst == firstId && foundSecond < secondId) -> low = middle + 1
                foundFirst > firstId || (foundFirst == firstId && foundSecond > secondId) -> high = middle - 1
                else -> return EdgeIndex(
                    offset = buffer.getInt(position + 8),
                    length = buffer.getInt(position + 12),
                    count = buffer.getShort(position + 16).toInt() and 0xFFFF,
                )
            }
        }
        return null
    }

    private fun findEdge(edgesStart: Int, offset: Int, length: Int, count: Int, targetId: Int): Double {
        var position = edgesStart + offset
        val end = position + length
        var previousId = 0
        repeat(count) {
            val (delta, nextPosition) = readVarInt(position, end)
            position = nextPosition
            val nextId = previousId + delta
            require(position + SCORE_SIZE <= end) { "Truncated n-gram edge" }
            val quantized = buffer.getShort(position).toInt() and 0xFFFF
            position += SCORE_SIZE
            if (nextId == targetId) return quantized / UINT16_MAX.toDouble() * maxLogCount
            if (nextId > targetId) return 0.0
            previousId = nextId
        }
        return 0.0
    }

    private fun readVarInt(start: Int, end: Int): Pair<Int, Int> {
        var position = start
        var value = 0
        var shift = 0
        while (position < end && shift <= 28) {
            val byte = buffer.get(position++).toInt() and 0xFF
            value = value or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return value to position
            shift += 7
        }
        error("Malformed n-gram varint")
    }

    private data class EdgeIndex(val offset: Int, val length: Int, val count: Int)

    companion object {
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'K'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
        private const val VERSION = 1
        private const val HEADER_SIZE = 36
        private const val BIGRAM_INDEX_SIZE = 16
        private const val TRIGRAM_INDEX_SIZE = 20
        private const val SCORE_SIZE = 2
        private const val UINT16_MAX = 65535
        private const val VERSION_OFFSET = 4
        private const val WORD_COUNT_OFFSET = 8
        private const val BIGRAM_CONTEXT_COUNT_OFFSET = 12
        private const val TRIGRAM_CONTEXT_COUNT_OFFSET = 16
        private const val BIGRAM_EDGE_BYTES_OFFSET = 20
        private const val TRIGRAM_EDGE_BYTES_OFFSET = 24
        private const val MAX_LOG_COUNT_OFFSET = 28

        fun fromBytes(bytes: ByteArray, wordIds: Map<String, Int>): CompactNgramScoreStore =
            CompactNgramScoreStore(bytes, wordIds)
    }
}
