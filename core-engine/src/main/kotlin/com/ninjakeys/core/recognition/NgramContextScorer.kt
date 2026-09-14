package com.ninjakeys.core.recognition

class NgramContextScorer(
    private val windowSize: Int = 2,
    private val bigrams: Map<Pair<String, String>, Double> = emptyMap(),
    private val trigrams: Map<Triple<String, String, String>, Double> = emptyMap(),
) {
    init { require(windowSize in 1..3) }

    fun score(previousWords: List<String>, candidate: String): Double {
        val previous = previousWords.takeLast(windowSize)
        val bigram = previous.lastOrNull()?.let { bigrams[it to candidate] ?: 0.0 } ?: 0.0
        val trigram = if (previous.size >= 2) trigrams[Triple(previous[0], previous[1], candidate)] ?: 0.0 else 0.0
        return bigram + trigram
    }
}
