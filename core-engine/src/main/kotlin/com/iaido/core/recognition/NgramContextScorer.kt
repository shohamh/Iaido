package com.iaido.core.recognition

class NgramContextScorer(
    private val windowSize: Int = 2,
    private val bigramWeight: Double = 1.0,
    private val trigramWeight: Double = 1.0,
    private val bigrams: Map<Pair<String, String>, Double> = emptyMap(),
    private val trigrams: Map<Triple<String, String, String>, Double> = emptyMap(),
    private val scoreStore: NgramScoreStore = EmptyNgramScoreStore,
) {
    init {
        require(windowSize in 1..3)
        require(bigramWeight >= 0.0)
        require(trigramWeight >= 0.0)
        require(bigrams.values.all { it >= 0.0 })
        require(trigrams.values.all { it >= 0.0 })
    }

    fun score(previousWords: List<String>, candidate: String): Double {
        val previous = previousWords.takeLast(windowSize)
        val bigram = previous.lastOrNull()?.let {
            bigrams[it to candidate] ?: scoreStore.bigram(it, candidate)
        } ?: 0.0
        val trigram = if (previous.size >= 2) {
            val first = previous[previous.lastIndex - 1]
            val second = previous.last()
            trigrams[Triple(first, second, candidate)] ?: scoreStore.trigram(first, second, candidate)
        } else 0.0
        return bigramWeight * bigram + trigramWeight * trigram
    }
}
