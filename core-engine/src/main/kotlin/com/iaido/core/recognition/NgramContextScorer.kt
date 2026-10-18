package com.iaido.core.recognition

import java.util.Locale

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
        // Capitalization belongs to displayed text. Corpus and personal n-gram keys are normalized.
        val context = previousWords.takeLast(windowSize).map { it.lowercase(Locale.ROOT) }
        val nextWord = candidate.lowercase(Locale.ROOT)
        val size = context.size
        val previousCount = size
        val bigram = if (previousCount >= 1) {
            val last = context[size - 1]
            (if (bigrams.isEmpty()) null else bigrams[last to nextWord]) ?: scoreStore.bigram(last, nextWord)
        } else 0.0
        val trigram = if (previousCount >= 2) {
            val first = context[size - 2]
            val second = context[size - 1]
            (if (trigrams.isEmpty()) null else trigrams[Triple(first, second, nextWord)])
                ?: scoreStore.trigram(first, second, nextWord)
        } else 0.0
        return bigramWeight * bigram + trigramWeight * trigram
    }
}
