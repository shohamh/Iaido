package com.ninjakeys.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FlowCorrectionEngineTest {
    @Test
    fun `replacement requires the configured score margin`() {
        val engine = FlowCorrectionEngine(
            NgramContextScorer(bigrams = mapOf(("to" to "there") to 2.0, ("to" to "three") to 1.0)),
            margin = 1.5,
        )

        val result = engine.correct(listOf(FlowWord("three", listOf("three", "there"))), listOf("to"))

        assertEquals(listOf("three"), result.words)
        assertEquals(emptyList<WordCorrection>(), result.corrections)
    }

    @Test
    fun `correction records original and can be undone`() {
        val engine = FlowCorrectionEngine(
            NgramContextScorer(bigrams = mapOf(("to" to "there") to 3.0)),
            margin = 1.0,
        )

        val result = engine.correct(listOf(FlowWord("three", listOf("three", "there"))), listOf("to"))

        assertEquals(listOf("there"), result.words)
        assertEquals(WordCorrection(0, "three", "there"), result.corrections.single())
        assertEquals("three", engine.undo(result.corrections.single(), result.words[0]))
    }

    @Test
    fun `cascade depth limits how many earlier words can change`() {
        val engine = FlowCorrectionEngine(
            NgramContextScorer(bigrams = mapOf(("word1" to "alt2") to 1.0, ("alt2" to "alt3") to 1.0, ("word3" to "alt4") to 1.0)),
            margin = 0.1,
            maxCascadeDepth = 2,
        )
        val words = (0..4).map { FlowWord("word$it", listOf("word$it", "alt$it")) }

        assertEquals(2, engine.correct(words, listOf("seed")).corrections.size)
    }
}
