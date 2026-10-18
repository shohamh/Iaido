package com.iaido.core.recognition

import com.iaido.core.recognition.Nkg2TestEncoder.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactNgramScoreStoreNkg2Test {
    private val ids = mapOf(
        "the" to 0, "of" to 1, "day" to 2, "today" to 3, "to" to 4, "x" to 5, "y" to 6, "z" to 7,
    )
    private val unigram = mapOf(
        "the" to 0.05, "of" to 0.03, "day" to 0.002, "today" to 0.001, "to" to 0.03,
        "x" to 0.01, "y" to 0.0001, "z" to 0.01,
    )

    private fun store(
        bigrams: Map<Int, Context>,
        trigrams: Map<Pair<Int, Int>, Context> = emptyMap(),
    ) = CompactNgramScoreStore.fromBytes(
        Nkg2TestEncoder.encode(ids.size, bigrams, trigrams),
        ids,
    ) { unigram.getValue(it) }

    @Test
    fun `seen pair outscores count-one pair outscores pruned pair`() {
        val s = store(mapOf(2 to Context(1000, 400, listOf(1 to 100, 4 to 5, 5 to 1))))
        val seen = s.bigram("day", "of")
        val rare = s.bigram("day", "x")
        val pruned = s.bigram("day", "y")
        assertTrue(seen > 0.0, "seen=$seen")
        assertTrue(seen > s.bigram("day", "to"))
        assertTrue(s.bigram("day", "to") > rare)
        assertTrue(rare > pruned, "rare=$rare pruned=$pruned")
    }

    @Test
    fun `pruned pair is a small smooth negative score not a cliff`() {
        val s = store(mapOf(2 to Context(1000, 400, listOf(1 to 100)), 3 to Context(20, 8, listOf(1 to 2))))
        val prunedBusy = s.bigram("day", "y")
        val prunedRare = s.bigram("today", "z")
        assertTrue(prunedBusy < 0.0 && prunedBusy >= -2.0, "prunedBusy=$prunedBusy")
        assertTrue(prunedRare < 0.0 && prunedRare >= -2.0, "prunedRare=$prunedRare")
        // lambda = 0.75 * distinct / total
        assertEquals(ln(0.75 * 400 / 1000), prunedBusy, 1e-9)
        assertEquals(ln(0.75 * 8 / 20), prunedRare, 1e-9)
        assertTrue(s.bigram("today", "of") - prunedRare < 3.0)
    }

    @Test
    fun `score depends on conditional probability not on context frequency`() {
        val s = store(
            mapOf(
                2 to Context(100, 10, listOf(1 to 10)),
                3 to Context(10_000, 10, listOf(1 to 1000)),
            ),
        )
        assertEquals(s.bigram("day", "of"), s.bigram("today", "of"), 0.15)
        // the legacy ln(1+count) would differ by ln(1001/11) ~ 4.5
        assertTrue(ln(1001.0 / 11.0) > 4.0)
    }

    @Test
    fun `scores are clamped`() {
        val s = store(mapOf(2 to Context(1000, 2, listOf(6 to 999))))
        assertEquals(8.0, s.bigram("day", "y"), 0.0)
    }

    @Test
    fun `trigram backs off to bigram then unigram`() {
        val s = store(
            bigrams = mapOf(
                2 to Context(1000, 400, listOf(1 to 100)),
                4 to Context(500, 20, listOf(5 to 50)),
            ),
            trigrams = mapOf((0 to 2) to Context(200, 150, listOf(1 to 40))),
        )
        assertEquals(s.bigram("day", "of"), s.trigram("to", "day", "of"), 1e-12)
        assertTrue(s.trigram("the", "day", "of") > s.bigram("day", "of"))
        val lambda3 = 0.75 * 150 / 200
        val r2 = exp(s.bigram("day", "z"))
        assertEquals(ln(lambda3 * r2), s.trigram("the", "day", "z"), 1e-9)
        assertEquals(0.0, s.trigram("x", "y", "z"), 0.0)
        assertEquals(s.bigram("day", "of"), s.trigram("nope", "day", "of"), 1e-12)
        assertEquals(0.0, s.bigram("day", "nope"), 0.0)
        assertEquals(0.0, s.bigram("nope", "of"), 0.0)
    }

    @Test
    fun `without unigram supplier it falls back to a uniform prior`() {
        val s = CompactNgramScoreStore.fromBytes(
            Nkg2TestEncoder.encode(ids.size, mapOf(2 to Context(1000, 50, listOf(1 to 500)))),
            ids,
        )
        assertTrue(s.bigram("day", "of") > 0.0)
    }

    @Test
    fun `rejects nkg2 magic with the wrong version`() {
        val bytes = Nkg2TestEncoder.encode(ids.size, emptyMap()).also { it[4] = 1 }
        assertThrows(IllegalArgumentException::class.java) { CompactNgramScoreStore.fromBytes(bytes, ids) }
    }

    @Test
    fun `nkg1 assets ignore the unigram supplier and keep legacy scores`() {
        val legacy = nkg1Bytes()
        val words = mapOf("hello" to 0, "world" to 1, "there" to 2)
        val plain = CompactNgramScoreStore.fromBytes(legacy, words)
        val withUnigram = CompactNgramScoreStore.fromBytes(legacy, words) { 0.5 }
        assertEquals(10.0, plain.bigram("hello", "world"), 1e-4)
        assertEquals(plain.bigram("hello", "world"), withUnigram.bigram("hello", "world"), 0.0)
        assertEquals(plain.trigram("hello", "world", "there"), withUnigram.trigram("hello", "world", "there"), 0.0)
        assertEquals(0.0, withUnigram.bigram("world", "hello"), 0.0)
    }

    @Test
    fun `reads the asset emitted by the python generator`() {
        val bytes = javaClass.getResourceAsStream("/ngram/tiny.ngram2.bin")!!.use { it.readBytes() }
        val words = listOf("the", "cat", "sat", "mat", "dog").withIndex().associate { it.value to it.index }
        val s = CompactNgramScoreStore.fromBytes(bytes, words) { 0.2 }
        // the -> cat: c=4, T=6, distinct=2, Pu=0.2, D=0.75
        assertEquals(ln((4 - 0.75) / (6 * 0.2) + 0.75 * 2 / 6), s.bigram("the", "cat"), 1e-9)
        val r2 = (3 - 0.75) / (4 * 0.2) + 0.75 * 2 / 4
        assertEquals(ln(r2), s.bigram("cat", "sat"), 1e-9)
        assertEquals(ln((3 - 0.75) / (4 * 0.2) + 0.75 * 2 / 4 * r2), s.trigram("the", "cat", "sat"), 1e-9)
        // seen continuation beats a never-seen one
        assertTrue(s.bigram("the", "cat") > s.bigram("the", "mat"))
        assertTrue(s.bigram("the", "mat") < 0.0)
    }

    private fun nkg1Bytes(): ByteArray = ByteBuffer.allocate(36 + 16 + 20 + 3 + 3)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            put("NKG1".toByteArray()); put(1); put(0); putShort(0)
            putInt(4); putInt(1); putInt(1); putInt(3); putInt(3); putDouble(10.0)
            putInt(0); putInt(0); putInt(3); putShort(1); putShort(0)
            putInt(0); putInt(1); putInt(0); putInt(3); putShort(1); putShort(0)
            put(1); putShort(0xFFFF.toShort()); put(2); putShort(0x8000.toShort())
        }.array()
}
