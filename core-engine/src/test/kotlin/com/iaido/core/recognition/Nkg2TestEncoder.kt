package com.iaido.core.recognition

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal NKG2 writer mirroring tools/generate_ngrams.py, for reader tests. */
object Nkg2TestEncoder {
    class Context(val total: Int, val distinct: Int, val edges: List<Pair<Int, Int>>)

    fun encode(
        wordCount: Int,
        bigrams: Map<Int, Context>,
        trigrams: Map<Pair<Int, Int>, Context> = emptyMap(),
        discount: Double = 0.75,
    ): ByteArray {
        val bigramKeys = bigrams.keys.sorted()
        val trigramKeys = trigrams.keys.sortedWith(compareBy({ it.first }, { it.second }))
        val bigramEdges = ByteArrayOutputStream()
        val trigramEdges = ByteArrayOutputStream()
        val bigramIndex = ByteBuffer.allocate(bigramKeys.size * 20).order(ByteOrder.LITTLE_ENDIAN)
        val trigramIndex = ByteBuffer.allocate(trigramKeys.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        for (key in bigramKeys) {
            val context = bigrams.getValue(key)
            val offset = bigramEdges.size()
            writeEdges(bigramEdges, context.edges)
            bigramIndex.putInt(key).putInt(offset).putInt(bigramEdges.size() - offset)
            bigramIndex.putShort(context.edges.size.toShort()).putShort(context.distinct.toShort()).putInt(context.total)
        }
        for (key in trigramKeys) {
            val context = trigrams.getValue(key)
            val offset = trigramEdges.size()
            writeEdges(trigramEdges, context.edges)
            trigramIndex.putInt(key.first).putInt(key.second).putInt(offset).putInt(trigramEdges.size() - offset)
            trigramIndex.putShort(context.edges.size.toShort()).putShort(context.distinct.toShort()).putInt(context.total)
        }
        val header = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("NKG2".toByteArray())
            put(2)
            put(0)
            putShort(0)
            putInt(wordCount)
            putInt(bigramKeys.size)
            putInt(trigramKeys.size)
            putInt(bigramEdges.size())
            putInt(trigramEdges.size())
            putDouble(discount)
        }
        return header.array() + bigramIndex.array() + trigramIndex.array() +
            bigramEdges.toByteArray() + trigramEdges.toByteArray()
    }

    private fun writeEdges(out: ByteArrayOutputStream, edges: List<Pair<Int, Int>>) {
        var previous = 0
        for ((id, count) in edges.sortedBy { it.first }) {
            writeVarint(out, id - previous)
            writeVarint(out, count)
            previous = id
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var rest = value
        while (rest >= 0x80) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }
}
