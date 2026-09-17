package com.iaido.core.dictionary

enum class LearningSignal {
    EXPLICIT_ADD, SUGGESTION_PICK, MANUAL_EDIT, FLOW_CORRECTION, FLOW_UNDO, DELETE_RETYPE,
}

class PersonalDictionary(
    private val base: List<WordEntry>,
    private val maxBoost: Double = 32.0,
) {
    private val baseByWord = base.associateBy { normalizeWord(it.word) }
    private data class OverrideState(var boost: Double = 1.0, var uses: Int = 0)

    private val wordOverrides = mutableMapOf<String, OverrideState>()
    private val ngramOverrides = mutableMapOf<Pair<String, String>, OverrideState>()
    private val customWords = mutableSetOf<String>()

    fun reinforce(word: String, amount: Double = 1.0) {
        require(amount > 0.0)
        val normalizedWord = normalizeWord(word)
        decayWordsExcept(normalizedWord)
        increase(wordOverrides, normalizedWord, amount)
    }

    fun record(
        signal: LearningSignal,
        original: String? = null,
        replacement: String,
        previousWord: String? = null,
        nextWord: String? = null,
    ) {
        val normalizedOriginal = original?.let(::normalizeWord)
        val normalizedReplacement = normalizeWord(replacement)
        val amount = if (signal == LearningSignal.DELETE_RETYPE) 3.0 else 1.0
        reinforce(normalizedReplacement, amount)
        if (signal != LearningSignal.EXPLICIT_ADD &&
            normalizedOriginal != null && normalizedOriginal != normalizedReplacement
        ) {
            decrease(wordOverrides, normalizedOriginal)
        }
        if (signal == LearningSignal.EXPLICIT_ADD) customWords += normalizedReplacement
        if (signal == LearningSignal.DELETE_RETYPE) {
            previousWord?.let { reinforceNgram(normalizeWord(it), normalizedReplacement) }
            nextWord?.let { reinforceNgram(normalizedReplacement, normalizeWord(it)) }
        }
    }

    fun forget(word: String) {
        val normalizedWord = normalizeWord(word)
        wordOverrides.remove(normalizedWord)
        customWords.remove(normalizedWord)
        ngramOverrides.keys.removeAll { it.first == normalizedWord || it.second == normalizedWord }
    }

    fun reset() {
        wordOverrides.clear()
        ngramOverrides.clear()
        customWords.clear()
    }

    fun overrides(): Set<String> = wordOverrides.keys.toSet()

    fun ngramBoost(previousWord: String, nextWord: String): Double =
        ngramOverrides[previousWord to nextWord]?.boost ?: 1.0

    fun ngramOverrides(): Map<Pair<String, String>, Double> =
        ngramOverrides.mapValues { (_, state) -> state.boost }

    fun restore(entries: List<WordEntry>) {
        entries.forEach { entry ->
            val word = normalizeWord(entry.word)
            val baseFrequency = baseByWord[word]?.frequency
            if (baseFrequency == null) customWords += word
            val boost = if (baseFrequency == null) {
                entry.frequency / ScoringConstants.PERSONAL_WORD_BASE_FREQUENCY
            } else {
                entry.frequency / baseFrequency
            }
            wordOverrides[word] = OverrideState(boost.coerceIn(0.1, maxBoost), uses = 1)
        }
    }

    fun entries(): List<WordEntry> = (base.map { normalizeWord(it.word) } + customWords).distinct().map { word ->
        val baseFrequency = baseByWord[word]?.frequency ?: ScoringConstants.PERSONAL_WORD_BASE_FREQUENCY
        WordEntry(word, baseFrequency * (wordOverrides[word]?.boost ?: 1.0))
    }

    private fun reinforceNgram(previousWord: String, nextWord: String) {
        val key = previousWord to nextWord
        ngramOverrides.keys.filter { it != key }.forEach { other ->
            ngramOverrides[other]?.let { state -> state.boost = (state.boost * 0.98).coerceAtLeast(0.1) }
        }
        increase(ngramOverrides, key, 1.0)
    }

    private fun decayWordsExcept(reinforcedWord: String) {
        wordOverrides.keys.filter { it != reinforcedWord }.forEach { word ->
            wordOverrides[word]?.let { state -> state.boost = (state.boost * 0.98).coerceAtLeast(0.1) }
        }
    }

    private fun <K> increase(overrides: MutableMap<K, OverrideState>, key: K, amount: Double) {
        val state = overrides.getOrPut(key) { OverrideState() }
        state.boost = (state.boost + amount / (state.uses + 1)).coerceAtMost(maxBoost)
        state.uses += 1
    }

    private fun normalizeWord(word: String): String = word.lowercase()

    private fun <K> decrease(overrides: MutableMap<K, OverrideState>, key: K) {
        val state = overrides.getOrPut(key) { OverrideState() }
        state.boost = (state.boost * 0.75).coerceAtLeast(0.1)
    }

    private object ScoringConstants {
        const val PERSONAL_WORD_BASE_FREQUENCY = 0.01
    }
}
