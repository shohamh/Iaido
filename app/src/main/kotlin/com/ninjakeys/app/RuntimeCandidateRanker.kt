package com.ninjakeys.app

import com.ninjakeys.core.recognition.ScoredCandidate

/** Invokes the primitive-only entrypoint when a verified update has been staged. */
class RuntimeCandidateRanker(private val loader: CoreEngineDexLoader) {
    fun rank(candidates: List<ScoredCandidate>): List<ScoredCandidate> {
        if (candidates.isEmpty()) return candidates
        val entrypoint = loader.load("com.ninjakeys.dynamic.CoreEngineDynamicEntrypoint") ?: return candidates
        val orderedWords = runCatching {
            entrypoint.getMethod("rank", Array<String>::class.java, DoubleArray::class.java)
                .invoke(
                    null,
                    candidates.map { it.word.word }.toTypedArray(),
                    candidates.map { it.score }.toDoubleArray(),
                ) as Array<*>
        }.getOrNull() ?: return candidates
        val byWord = candidates.groupBy { it.word.word }.mapValues { it.value.toMutableList() }.toMutableMap()
        return buildList {
            orderedWords.forEach { word -> byWord[word]?.removeFirstOrNull()?.let(::add) }
            candidates.forEach { candidate ->
                if (candidate in this) return@forEach
                add(candidate)
            }
        }
    }
}
