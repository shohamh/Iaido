package com.iaido.core.recognition

data class FlowWord(val current: String, val candidates: List<String>)
data class WordCorrection(val index: Int, val before: String, val after: String)
data class FlowCorrectionResult(val words: List<String>, val corrections: List<WordCorrection>)

class FlowCorrectionEngine(
    private val contextScorer: NgramContextScorer,
    private val margin: Double = 1.0,
    private val maxCascadeDepth: Int = 2,
) {
    fun correct(words: List<FlowWord>, previousWords: List<String>): FlowCorrectionResult {
        val corrected = words.map { it.current }.toMutableList()
        val changes = mutableListOf<WordCorrection>()
        val start = (corrected.size - 3).coerceAtLeast(0)
        for (index in start until corrected.size) {
            if (changes.size >= maxCascadeDepth) break
            val context = previousWords + corrected.take(index)
            val current = corrected[index]
            val best = words[index].candidates.maxByOrNull { contextScorer.score(context, it) } ?: current
            val gain = contextScorer.score(context, best) - contextScorer.score(context, current)
            if (best != current && gain >= margin) {
                corrected[index] = best
                changes += WordCorrection(index, current, best)
            }
        }
        return FlowCorrectionResult(corrected, changes)
    }

    fun undo(correction: WordCorrection, current: String): String =
        if (current == correction.after) correction.before else current
}
