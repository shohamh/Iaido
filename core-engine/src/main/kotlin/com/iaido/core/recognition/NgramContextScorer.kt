package com.iaido.core.recognition

import java.util.Locale
import kotlin.math.ln

class NgramContextScorer(
    val windowSize: Int = 2,
    private val bigramWeight: Double = 1.0,
    private val trigramWeight: Double = 1.0,
    private val bigrams: Map<Pair<String, String>, Double> = emptyMap(),
    private val trigrams: Map<Triple<String, String, String>, Double> = emptyMap(),
    private val scoreStore: NgramScoreStore = EmptyNgramScoreStore,
    private val personalBigramBoost: (previous: String, next: String) -> Double = { _, _ -> 1.0 },
    private val personalBigramWeight: Double = 0.35,
) {
    init {
        require(windowSize in 1..3)
        require(bigramWeight >= 0.0)
        require(trigramWeight >= 0.0)
        require(bigrams.values.all { it >= 0.0 })
        require(trigrams.values.all { it >= 0.0 })
        require(personalBigramWeight >= 0.0)
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
        val learnedBoost = if (size > 0 && personalBigramWeight > 0.0) {
            personalBigramBoost(context.last(), nextWord)
                .takeIf { it.isFinite() && it > 0.0 }
                ?.coerceAtMost(6.0)
                ?.let { personalBigramWeight * ln(it) }
                ?: 0.0
        } else 0.0
        return bigramWeight * bigram + trigramWeight * trigram + learnedBoost
    }

    /** Scores the candidate and the bounded sequence that follows it. */
    fun scoreInContext(previousWords: List<String>, candidate: String, nextWords: List<String>): Double {
        val future = nextWords.take((windowSize - 1).coerceAtLeast(0))
        var context = previousWords.takeLast(windowSize) + candidate
        var total = score(previousWords, candidate)
        future.forEach { next ->
            total += score(context, next)
            context = (context + next).takeLast(windowSize)
        }
        return total
    }
}
