package com.iaido.core.recognition

data class SentenceRerankResult(
    val words: List<SessionWord>,
    val affectedIds: Set<Int>,
)

/** Reorders only the bounded sentence neighborhood around a changed word. */
class SentenceCandidateReranker(
    private val contextScorer: NgramContextScorer,
    private val frequency: (String) -> Double = { 0.0 },
    private val neighborWindow: Int = 3,
    private val confidenceMargin: Double = 1.0,
) {
    init {
        require(neighborWindow > 0) { "neighborWindow must be positive" }
        require(confidenceMargin >= 0.0) { "confidenceMargin must not be negative" }
    }

    fun rerank(
        words: List<SessionWord>,
        changedId: Int,
        previousWords: List<String> = emptyList(),
    ): SentenceRerankResult {
        val changedIndex = words.indexOfFirst { it.id == changedId }
        if (changedIndex < 0) return SentenceRerankResult(words, emptySet())
        val first = (changedIndex - neighborWindow).coerceAtLeast(0)
        val last = (changedIndex + neighborWindow).coerceAtMost(words.lastIndex)
        val updated = words.toMutableList()
        val affected = (first..last).map { index ->
            val entry = words[index]
            val context = previousWords + words.take(index).map { it.current }
            val following = words.drop(index + 1).take(contextScorer.windowSize - 1).map { it.current }
            val candidates = (listOf(entry.current) + entry.candidates).distinct()
            val rankedAlternatives = candidates.withIndex().filter { it.value != entry.current }.sortedWith(
                compareByDescending<IndexedValue<String>> {
                    contextScorer.scoreInContext(context, it.value, following) + frequency(it.value)
                }
                    .thenBy { it.index }
                    .thenBy { it.value },
            ).map { it.value }
            val bestAlternative = rankedAlternatives.firstOrNull()
            val currentScore = contextScorer.scoreInContext(context, entry.current, following) + frequency(entry.current)
            val bestScore = bestAlternative?.let {
                contextScorer.scoreInContext(context, it, following) + frequency(it)
            }
            val ranked = if (bestAlternative != null && bestScore!! - currentScore >= confidenceMargin) {
                listOf(bestAlternative) + rankedAlternatives.drop(1) + entry.current
            } else {
                listOf(entry.current) + rankedAlternatives
            }
            updated[index] = entry.copy(candidates = ranked)
            entry.id
        }.toSet()
        return SentenceRerankResult(updated, affected)
    }
}
