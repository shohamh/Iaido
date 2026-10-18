package com.iaido.core.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/**
 * Reads the deterministic NKG1/NKG2 assets emitted by tools/generate_ngrams.py.
 * The byte array can be backed by an Android asset; no n-gram map is built.
 *
 * NKG1 stores ln(1+count) per stored pair and scores everything else 0 (legacy behaviour, unchanged).
 *
 * NKG2 stores exact counts plus per-context totals and distinct-successor counts and scores with
 * interpolated absolute discounting, reported as a log-ratio against the unigram prior:
 *
 *   R2(w|b)   = max(c(b,w) - D, 0) / (T(b) * Pu(w)) + lambda(b) * 1
 *   R3(w|a,b) = max(c(a,b,w) - D, 0) / (T(a,b) * Pu(w)) + lambda(a,b) * R2(w|b)
 *   lambda(h) = min(1, D * distinct(h) / T(h)),  score = clamp(ln R, MIN_SCORE, MAX_SCORE)
 *
 * A context missing from the asset contributes R = 1 (neutral, i.e. falls back to the lower order).
 * Pu(w) comes from `unigramFrequency` (uniform 1/|vocabulary| when absent); D is stored in the header.
 */
class CompactNgramScoreStore private constructor(
    private val bytes: ByteArray,
    private val wordIds: Map<String, Int>,
    unigramFrequency: ((String) -> Double)?,
) : NgramScoreStore {
    private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val bigramIndexStart: Int
    private val trigramIndexStart: Int
    private val bigramEdgesStart: Int
    private val trigramEdgesStart: Int
    private val bigramContexts: Int
    private val trigramContexts: Int
    private val maxLogCount: Double
    private val smoothed: Boolean
    private val bigramIndexSize: Int
    private val trigramIndexSize: Int
    private val discount: Double
    private val unigram: DoubleArray

    init {
        require(bytes.size >= HEADER_SIZE) { "N-gram asset is truncated" }
        val magic = bytes.copyOfRange(0, 4)
        smoothed = magic.contentEquals(MAGIC_V2)
        require(smoothed || magic.contentEquals(MAGIC)) { "Unsupported n-gram asset magic" }
        require(buffer.get(VERSION_OFFSET).toInt() == (if (smoothed) VERSION_V2 else VERSION)) {
            "Unsupported n-gram asset version"
        }
        bigramIndexSize = if (smoothed) BIGRAM_INDEX_SIZE_V2 else BIGRAM_INDEX_SIZE
        trigramIndexSize = if (smoothed) TRIGRAM_INDEX_SIZE_V2 else TRIGRAM_INDEX_SIZE
        val wordCount = buffer.getInt(WORD_COUNT_OFFSET)
        require(wordCount >= 0) { "Invalid n-gram vocabulary size" }
        bigramContexts = buffer.getInt(BIGRAM_CONTEXT_COUNT_OFFSET)
        trigramContexts = buffer.getInt(TRIGRAM_CONTEXT_COUNT_OFFSET)
        val bigramBytes = buffer.getInt(BIGRAM_EDGE_BYTES_OFFSET)
        val trigramBytes = buffer.getInt(TRIGRAM_EDGE_BYTES_OFFSET)
        // Header slot at byte 28: NKG1 max ln(1+count); NKG2 absolute discount D.
        val slot = buffer.getDouble(MAX_LOG_COUNT_OFFSET)
        maxLogCount = if (smoothed) 0.0 else slot
        discount = if (smoothed) slot else 0.0
        require(!smoothed || (discount > 0.0 && discount < 1.0)) { "Invalid n-gram discount" }
        require(bigramContexts >= 0 && trigramContexts >= 0) { "Invalid n-gram context count" }
        require(bigramBytes >= 0 && trigramBytes >= 0) { "Invalid n-gram edge byte count" }
        require(wordIds.values.all { it >= 0 && it < wordCount }) { "Dictionary does not match n-gram asset" }
        bigramIndexStart = HEADER_SIZE
        trigramIndexStart = bigramIndexStart + bigramContexts * bigramIndexSize
        bigramEdgesStart = trigramIndexStart + trigramContexts * trigramIndexSize
        trigramEdgesStart = bigramEdgesStart + bigramBytes
        require(trigramEdgesStart + trigramBytes == bytes.size) { "N-gram asset section sizes do not match" }
        unigram = DoubleArray(if (smoothed) wordCount else 0) { 1.0 / maxOf(wordCount, 1) }
        if (smoothed && unigramFrequency != null) {
            for ((word, id) in wordIds) {
                val probability = unigramFrequency(word)
                if (probability > 0.0) unigram[id] = maxOf(probability, MIN_UNIGRAM)
            }
        }
    }

    override fun bigram(previous: String, next: String): Double {
        val previousId = wordIds[previous] ?: return 0.0
        val nextId = wordIds[next] ?: return 0.0
        if (smoothed) return clamp(ln(bigramRatio(previousId, nextId)))
        val position = findBigramPosition(previousId)
        if (position < 0) return 0.0
        return findEdge(
            bigramEdgesStart,
            buffer.getInt(position + 4),
            buffer.getInt(position + 8),
            buffer.getShort(position + 12).toInt() and 0xFFFF,
            nextId,
        )
    }

    override fun trigram(first: String, second: String, next: String): Double {
        val secondId = wordIds[second] ?: return 0.0
        val nextId = wordIds[next] ?: return 0.0
        if (smoothed) {
            // An unknown first word simply has no trigram context: back off to the bigram.
            val firstId = wordIds[first] ?: return clamp(ln(bigramRatio(secondId, nextId)))
            return clamp(ln(trigramRatio(firstId, secondId, nextId)))
        }
        val firstId = wordIds[first] ?: return 0.0
        val position = findTrigramPosition(firstId, secondId)
        if (position < 0) return 0.0
        return findEdge(
            trigramEdgesStart,
            buffer.getInt(position + 8),
            buffer.getInt(position + 12),
            buffer.getShort(position + 16).toInt() and 0xFFFF,
            nextId,
        )
    }

    private fun clamp(logRatio: Double): Double = logRatio.coerceIn(MIN_SCORE, MAX_SCORE)

    /** P(next | previous) / Pu(next) under interpolated absolute discounting; 1 when the context is unknown. */
    private fun bigramRatio(previousId: Int, nextId: Int): Double {
        val position = findBigramPosition(previousId)
        if (position < 0) return 1.0
        val total = buffer.getInt(position + 16).toLong() and 0xFFFFFFFFL
        if (total == 0L) return 1.0
        val count = findCount(
            bigramEdgesStart,
            buffer.getInt(position + 4),
            buffer.getInt(position + 8),
            buffer.getShort(position + 12).toInt() and 0xFFFF,
            nextId,
        )
        val distinct = buffer.getShort(position + 14).toInt() and 0xFFFF
        return discountedRatio(count, total, distinct, nextId, 1.0)
    }

    private fun trigramRatio(firstId: Int, secondId: Int, nextId: Int): Double {
        val lower = bigramRatio(secondId, nextId)
        val position = findTrigramPosition(firstId, secondId)
        if (position < 0) return lower
        val total = buffer.getInt(position + 20).toLong() and 0xFFFFFFFFL
        if (total == 0L) return lower
        val count = findCount(
            trigramEdgesStart,
            buffer.getInt(position + 8),
            buffer.getInt(position + 12),
            buffer.getShort(position + 16).toInt() and 0xFFFF,
            nextId,
        )
        val distinct = buffer.getShort(position + 18).toInt() and 0xFFFF
        return discountedRatio(count, total, distinct, nextId, lower)
    }

    private fun discountedRatio(count: Int, total: Long, distinct: Int, nextId: Int, lowerRatio: Double): Double {
        val lambda = minOf(1.0, discount * distinct / total)
        val seen = if (count > 0) maxOf(count - discount, 0.0) / (total * unigram[nextId]) else 0.0
        return seen + lambda * lowerRatio
    }

    private fun readVarint(position: Int, end: Int): Long {
        var value = 0L
        var shift = 0
        var cursor = position
        while (true) {
            check(cursor < end && shift <= 28) { "Malformed n-gram varint" }
            val byte = buffer.get(cursor++).toInt() and 0xFF
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        // Encode consumed length in the high bits so callers can advance without a second pass.
        return value or ((cursor - position).toLong() shl 40)
    }

    /** Exact count of [targetId] among the context's stored successors, or 0 when pruned (NKG2). */
    private fun findCount(edgesStart: Int, offset: Int, length: Int, edges: Int, targetId: Int): Int {
        var position = edgesStart + offset
        val end = position + length
        var previousId = 0
        repeat(edges) {
            val deltaRaw = readVarint(position, end)
            position += (deltaRaw ushr 40).toInt()
            val countRaw = readVarint(position, end)
            position += (countRaw ushr 40).toInt()
            val nextId = previousId + (deltaRaw and 0xFFFFFFFFFFL).toInt()
            if (nextId == targetId) return (countRaw and 0xFFFFFFFFFFL).toInt()
            if (nextId > targetId) return 0
            previousId = nextId
        }
        return 0
    }

    /** Byte position of the context's index record, or -1. */
    private fun findBigramPosition(contextId: Int): Int {
        var low = 0
        var high = bigramContexts - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val position = bigramIndexStart + middle * bigramIndexSize
            val found = buffer.getInt(position)
            when {
                found < contextId -> low = middle + 1
                found > contextId -> high = middle - 1
                else -> return position
            }
        }
        return -1
    }

    private fun findTrigramPosition(firstId: Int, secondId: Int): Int {
        var low = 0
        var high = trigramContexts - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val position = trigramIndexStart + middle * trigramIndexSize
            val foundFirst = buffer.getInt(position)
            val foundSecond = buffer.getInt(position + 4)
            when {
                foundFirst < firstId || (foundFirst == firstId && foundSecond < secondId) -> low = middle + 1
                foundFirst > firstId || (foundFirst == firstId && foundSecond > secondId) -> high = middle - 1
                else -> return position
            }
        }
        return -1
    }

    private fun findEdge(edgesStart: Int, offset: Int, length: Int, count: Int, targetId: Int): Double {
        var position = edgesStart + offset
        val end = position + length
        var previousId = 0
        repeat(count) {
            var delta = 0
            var shift = 0
            while (true) {
                check(position < end && shift <= 28) { "Malformed n-gram varint" }
                val byte = buffer.get(position++).toInt() and 0xFF
                delta = delta or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
            }
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

    companion object {
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'K'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte())
        private val MAGIC_V2 = byteArrayOf('N'.code.toByte(), 'K'.code.toByte(), 'G'.code.toByte(), '2'.code.toByte())
        private const val VERSION = 1
        private const val VERSION_V2 = 2
        private const val HEADER_SIZE = 36
        private const val BIGRAM_INDEX_SIZE = 16
        private const val TRIGRAM_INDEX_SIZE = 20
        private const val BIGRAM_INDEX_SIZE_V2 = 20
        private const val TRIGRAM_INDEX_SIZE_V2 = 24
        private const val SCORE_SIZE = 2
        private const val UINT16_MAX = 65535
        private const val MIN_SCORE = -2.0
        private const val MAX_SCORE = 8.0
        private const val MIN_UNIGRAM = 1e-9
        private const val VERSION_OFFSET = 4
        private const val WORD_COUNT_OFFSET = 8
        private const val BIGRAM_CONTEXT_COUNT_OFFSET = 12
        private const val TRIGRAM_CONTEXT_COUNT_OFFSET = 16
        private const val BIGRAM_EDGE_BYTES_OFFSET = 20
        private const val TRIGRAM_EDGE_BYTES_OFFSET = 24
        private const val MAX_LOG_COUNT_OFFSET = 28

        fun fromBytes(bytes: ByteArray, wordIds: Map<String, Int>): CompactNgramScoreStore =
            CompactNgramScoreStore(bytes, wordIds, null)

        /**
         * [unigramFrequency] maps a word to its unigram probability (e.g. `WordEntry.frequency`). It is only
         * used by NKG2 assets and is read once per word at construction.
         */
        fun fromBytes(
            bytes: ByteArray,
            wordIds: Map<String, Int>,
            unigramFrequency: ((String) -> Double)?,
        ): CompactNgramScoreStore = CompactNgramScoreStore(bytes, wordIds, unigramFrequency)
    }
}
