package com.iaido.core.recognition

/** A structured reel candidate that can replace one or more source words. */
data class ReplacementOption(
    val sourceWords: List<String>,
    val replacementWords: List<String>,
    val score: Double,
    val id: String = stableReplacementId(sourceWords, replacementWords),
) {
    init {
        require(sourceWords.isNotEmpty()) { "A replacement needs source words" }
        require(replacementWords.isNotEmpty()) { "A replacement needs replacement words" }
    }
}

private fun stableReplacementId(sourceWords: List<String>, replacementWords: List<String>): String =
    "${encodeWords(sourceWords)}→${encodeWords(replacementWords)}"

private fun encodeWords(words: List<String>): String =
    words.joinToString(separator = "\u001f") { word -> "${word.length}:$word" }
