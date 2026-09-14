package com.ninjakeys.core.dictionary

enum class LearningSignal {
    EXPLICIT_ADD, SUGGESTION_PICK, MANUAL_EDIT, FLOW_CORRECTION, FLOW_UNDO, DELETE_RETYPE,
}

class PersonalDictionary(
    private val base: List<WordEntry>,
    private val maxBoost: Double = 32.0,
) {
    private val boosts = mutableMapOf<String, Double>()
    private val customWords = mutableSetOf<String>()

    fun reinforce(word: String, amount: Double = 1.0) {
        require(amount > 0.0)
        decayExcept(word)
        boosts[word] = ((boosts[word] ?: 1.0) + amount).coerceAtMost(maxBoost)
    }

    fun record(signal: LearningSignal, original: String? = null, replacement: String) {
        val amount = if (signal == LearningSignal.DELETE_RETYPE) 3.0 else 1.0
        reinforce(replacement, amount)
        if (signal != LearningSignal.EXPLICIT_ADD && original != null && original != replacement) {
            boosts[original] = ((boosts[original] ?: 1.0) * 0.75).coerceAtLeast(0.1)
        }
        if (signal == LearningSignal.EXPLICIT_ADD) customWords += replacement
    }

    fun forget(word: String) { boosts.remove(word) }

    fun reset() {
        boosts.clear()
        customWords.clear()
    }

    fun overrides(): Set<String> = boosts.keys.toSet()

    fun entries(): List<WordEntry> = (base.map { it.word } + customWords).distinct().map { word ->
        val baseFrequency = base.firstOrNull { it.word == word }?.frequency ?: ScoringConstants.PERSONAL_WORD_BASE_FREQUENCY
        WordEntry(word, baseFrequency * (boosts[word] ?: 1.0))
    }

    private fun decayExcept(reinforcedWord: String) {
        boosts.keys.filter { it != reinforcedWord }.forEach { word ->
            boosts[word] = (boosts[word]!! * 0.98).coerceAtLeast(0.1)
        }
    }

    private object ScoringConstants {
        const val PERSONAL_WORD_BASE_FREQUENCY = 0.01
    }
}
