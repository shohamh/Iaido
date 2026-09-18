package com.iaido.core.dictionary

data class WordOverrideSnapshot(
    val word: String,
    val boost: Double,
    val uses: Int,
) {
    init {
        require(word.isNotBlank()) { "Override word must not be blank" }
        require(word == word.lowercase()) { "Override word must be normalized" }
        require(boost.isFinite() && boost > 0.0) { "Override boost must be finite and positive" }
        require(uses >= 0) { "Override uses must not be negative" }
    }
}

data class NgramOverrideSnapshot(
    val previousWord: String,
    val nextWord: String,
    val boost: Double,
    val uses: Int,
) {
    init {
        require(previousWord.isNotBlank()) { "Previous n-gram word must not be blank" }
        require(nextWord.isNotBlank()) { "Next n-gram word must not be blank" }
        require(previousWord == previousWord.lowercase()) { "Previous n-gram word must be normalized" }
        require(nextWord == nextWord.lowercase()) { "Next n-gram word must be normalized" }
        require(boost.isFinite() && boost > 0.0) { "N-gram boost must be finite and positive" }
        require(uses >= 0) { "N-gram uses must not be negative" }
    }
}

data class PersonalDictionarySnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val wordOverrides: List<WordOverrideSnapshot> = emptyList(),
    val ngramOverrides: List<NgramOverrideSnapshot> = emptyList(),
    val customWords: List<String> = emptyList(),
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported dictionary snapshot version: $schemaVersion" }
        require(wordOverrides.map { it.word }.distinct().size == wordOverrides.size) {
            "Dictionary snapshot contains duplicate word overrides"
        }
        require(ngramOverrides.map { it.previousWord to it.nextWord }.distinct().size == ngramOverrides.size) {
            "Dictionary snapshot contains duplicate n-gram overrides"
        }
        require(customWords.all { it.isNotBlank() && it == it.lowercase() }) {
            "Custom words must be normalized and non-blank"
        }
        require(customWords.distinct().size == customWords.size) {
            "Dictionary snapshot contains duplicate custom words"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
