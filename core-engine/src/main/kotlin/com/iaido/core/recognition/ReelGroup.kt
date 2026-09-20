package com.iaido.core.recognition

/** Session-only relationship for a replacement that currently represents multiple output words. */
data class ReelGroup(
    val id: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val wordIds: List<Int>,
    val sourceWords: List<String>,
    val replacementWords: List<String>,
) {
    init {
        require(sourceStart >= 0 && sourceEnd >= sourceStart)
        require(wordIds.isNotEmpty())
        require(replacementWords.size == wordIds.size)
    }
}
