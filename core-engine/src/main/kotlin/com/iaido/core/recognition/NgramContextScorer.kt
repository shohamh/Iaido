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
        val size = previousWords.size
        val previousCount = minOf(size, windowSize)
        val bigram = if (previousCount >= 1) {
            val last = previousWords[size - 1]
            (if (bigrams.isEmpty()) null else bigrams[last to candidate]) ?: scoreStore.bigram(last, candidate)
        } else 0.0
        val trigram = if (previousCount >= 2) {
            val first = previousWords[size - 2]
            val second = previousWords[size - 1]
            (if (trigrams.isEmpty()) null else trigrams[Triple(first, second, candidate)])
                ?: scoreStore.trigram(first, second, candidate)
        } else 0.0
        return bigramWeight * bigram + trigramWeight * trigram
    }
}
