package com.ninjakeys.core.dictionary

class PersonalDictionary(
    private val base: List<WordEntry>,
    private val maxBoost: Double = 32.0,
) {
    private val boosts = mutableMapOf<String, Double>()

    fun reinforce(word: String, amount: Double = 1.0) {
        require(amount > 0.0)
        boosts[word] = ((boosts[word] ?: 1.0) + amount).coerceAtMost(maxBoost)
    }

    fun forget(word: String) { boosts.remove(word) }

    fun reset() { boosts.clear() }

    fun overrides(): Set<String> = boosts.keys.toSet()

    fun entries(): List<WordEntry> = base.map { entry ->
        entry.copy(frequency = entry.frequency * (boosts[entry.word] ?: 1.0))
    }
}
