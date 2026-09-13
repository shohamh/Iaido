package com.ninjakeys.core.recognition

class NgramContextScorer(
    private val bigrams: Map<Pair<String, String>, Double> = emptyMap(),
    private val trigrams: Map<Triple<String, String, String>, Double> = emptyMap(),
) {
    fun score(previousWords: List<String>, candidate: String): Double {
        val previous = previousWords.takeLast(2)
        val bigram = previous.lastOrNull()?.let { bigrams[it to candidate] ?: 0.0 } ?: 0.0
        val trigram = if (previous.size >= 2) trigrams[Triple(previous[0], previous[1], candidate)] ?: 0.0 else 0.0
        return bigram + trigram
    }
}
