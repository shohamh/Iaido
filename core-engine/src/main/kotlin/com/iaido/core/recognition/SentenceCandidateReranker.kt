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
) {
    init {
        require(neighborWindow > 0) { "neighborWindow must be positive" }
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
            val candidates = (listOf(entry.current) + entry.candidates).distinct()
            val ranked = candidates.withIndex().sortedWith(
                compareByDescending<IndexedValue<String>> {
                    contextScorer.score(context, it.value) + frequency(it.value)
                }.thenBy { if (it.value == entry.current) 0 else 1 }
                    .thenBy { it.index }
                    .thenBy { it.value },
            ).map { it.value }
            updated[index] = entry.copy(candidates = ranked)
            entry.id
        }.toSet()
        return SentenceRerankResult(updated, affected)
    }
}
