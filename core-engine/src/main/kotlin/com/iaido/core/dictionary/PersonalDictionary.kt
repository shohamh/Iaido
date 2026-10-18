package com.iaido.core.dictionary

enum class LearningSignal {
    EXPLICIT_ADD, SUGGESTION_PICK, MANUAL_EDIT, FLOW_CORRECTION, FLOW_UNDO, DELETE_RETYPE,
}

/** Shared learning constants and policy, used by both the in-memory and the Room-backed dictionary. */
object PersonalLearningPolicy {
    /**
     * Base frequency of a word that is not in the base dictionary. en.csv has median 3.5e-6 and
     * 75th percentile 1e-5, so 3e-6 sits mid-pack among rare real words.
     */
    const val CUSTOM_WORD_BASE_FREQUENCY = 3e-6

    /** Max boost; custom words top out at 1.8e-5 (still < 1e-4), known words at 6x their base. */
    const val DEFAULT_MAX_BOOST = 6.0

    /** A correction multiplies the replaced word's boost by this (a 5x penalty). */
    const val CORRECTION_DECAY = 0.2
    const val MIN_BOOST = 0.01

    private val AUTOMATIC_SIGNALS = setOf(
        LearningSignal.FLOW_CORRECTION, LearningSignal.FLOW_UNDO, LearningSignal.DELETE_RETYPE,
    )

    /** True when [replacement] may be reinforced / created as a learned word. */
    fun mayLearnReplacement(
        signal: LearningSignal,
        replacement: String,
        isKnown: (String) -> Boolean,
        isBaseWord: (String) -> Boolean,
    ): Boolean {
        if (replacement.isBlank() || replacement.any { it.isWhitespace() }) return false
        if (signal == LearningSignal.EXPLICIT_ADD) return true
        if (isKnown(replacement)) return true
        if (signal in AUTOMATIC_SIGNALS) return false
        return !isInferredJoin(replacement, isBaseWord)
    }

    /** An unknown word that is just two dictionary words glued together, e.g. "areyou". */
    fun isInferredJoin(word: String, isBaseWord: (String) -> Boolean): Boolean {
        if (isBaseWord(word)) return false
        for (i in 1 until word.length) {
            val head = word.substring(0, i)
            val tail = word.substring(i)
            if (isBaseWord(head) && isBaseWord(tail) && (head.length > 1 || tail.length > 1)) return true
        }
        return false
    }
}

class PersonalDictionary(
    private val base: List<WordEntry>,
    private val maxBoost: Double = PersonalLearningPolicy.DEFAULT_MAX_BOOST,
) {
    private val baseByWord = base.associateBy { normalizeWord(it.word) }
    private data class OverrideState(var boost: Double = 1.0, var uses: Int = 0)

    private val wordOverrides = mutableMapOf<String, OverrideState>()
    private val ngramOverrides = mutableMapOf<Pair<String, String>, OverrideState>()
    private val customWords = mutableSetOf<String>()
    private var cachedEntries: List<WordEntry>? = null

    fun reinforce(word: String, amount: Double = 1.0) {
        require(amount > 0.0)
        cachedEntries = null
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
        val learnable = PersonalLearningPolicy.mayLearnReplacement(
            signal, normalizedReplacement,
            isKnown = { it in baseByWord || it in customWords },
            isBaseWord = { it in baseByWord },
        )
        val amount = if (signal == LearningSignal.DELETE_RETYPE) 3.0 else 1.0
        if (learnable) {
            if (normalizedReplacement !in baseByWord) customWords += normalizedReplacement
            reinforce(normalizedReplacement, amount)
        }
        if (signal != LearningSignal.EXPLICIT_ADD &&
            normalizedOriginal != null && normalizedOriginal != normalizedReplacement
        ) {
            penalize(normalizedOriginal)
        }
        if (learnable && signal in CONTEXTUAL_LEARNING_SIGNALS) {
            previousWord?.let { reinforceNgram(normalizeWord(it), normalizedReplacement) }
            nextWord?.let { reinforceNgram(normalizedReplacement, normalizeWord(it)) }
        }
    }

    fun forget(word: String) {
        cachedEntries = null
        val normalizedWord = normalizeWord(word)
        wordOverrides.remove(normalizedWord)
        customWords.remove(normalizedWord)
        ngramOverrides.keys.removeAll { it.first == normalizedWord || it.second == normalizedWord }
    }

    fun reset() {
        cachedEntries = null
        wordOverrides.clear()
        ngramOverrides.clear()
        customWords.clear()
    }

    fun overrides(): Set<String> = wordOverrides.keys.toSet()

    fun ngramBoost(previousWord: String, nextWord: String): Double =
        ngramOverrides[previousWord to nextWord]?.boost ?: 1.0

    fun ngramOverrides(): Map<Pair<String, String>, Double> =
        ngramOverrides.mapValues { (_, state) -> state.boost }

    fun snapshot(): PersonalDictionarySnapshot = PersonalDictionarySnapshot(
        wordOverrides = wordOverrides
            .entries
            .sortedBy { it.key }
            .map { (word, state) -> WordOverrideSnapshot(word, state.boost, state.uses) },
        ngramOverrides = ngramOverrides
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, state) -> NgramOverrideSnapshot(key.first, key.second, state.boost, state.uses) },
        customWords = customWords.sorted(),
    )

    fun restore(snapshot: PersonalDictionarySnapshot) {
        require(snapshot.wordOverrides.all { it.boost <= maxBoost }) {
            "Dictionary snapshot contains a word boost above this dictionary's maximum"
        }
        require(snapshot.ngramOverrides.all { it.boost <= maxBoost }) {
            "Dictionary snapshot contains an n-gram boost above this dictionary's maximum"
        }
        wordOverrides.clear()
        ngramOverrides.clear()
        customWords.clear()
        snapshot.wordOverrides.forEach { override ->
            wordOverrides[override.word] = OverrideState(override.boost, override.uses)
        }
        snapshot.ngramOverrides.forEach { override ->
            ngramOverrides[override.previousWord to override.nextWord] =
                OverrideState(override.boost, override.uses)
        }
        customWords += snapshot.customWords
        cachedEntries = null
    }

    fun restoreNgramOverrides(overrides: List<NgramOverrideSnapshot>) {
        require(overrides.all { it.boost <= maxBoost }) {
            "Dictionary snapshot contains an n-gram boost above this dictionary's maximum"
        }
        ngramOverrides.clear()
        overrides.forEach { override ->
            ngramOverrides[override.previousWord to override.nextWord] =
                OverrideState(override.boost, override.uses)
        }
    }

    fun restore(entries: List<WordEntry>) {
        cachedEntries = null
        entries.forEach { entry ->
            val word = normalizeWord(entry.word)
            val baseFrequency = baseByWord[word]?.frequency
            val boost = entry.frequency /
                (baseFrequency ?: PersonalLearningPolicy.CUSTOM_WORD_BASE_FREQUENCY)
            if (baseFrequency == null) {
                // A custom word persisted below the custom base was corrected away.
                if (boost < 1.0 || word.any { it.isWhitespace() }) return@forEach
                customWords += word
            }
            wordOverrides[word] = OverrideState(boost.coerceIn(PersonalLearningPolicy.MIN_BOOST, maxBoost), uses = 1)
        }
    }

    fun entries(): List<WordEntry> {
        return cachedEntries ?: (base.map { normalizeWord(it.word) } + customWords).distinct().map { word ->
            val baseFrequency = baseByWord[word]?.frequency ?: PersonalLearningPolicy.CUSTOM_WORD_BASE_FREQUENCY
            WordEntry(word, baseFrequency * (wordOverrides[word]?.boost ?: 1.0))
        }.also { cachedEntries = it }
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

    private fun penalize(word: String) {
        cachedEntries = null
        val state = wordOverrides.getOrPut(word) { OverrideState() }
        state.boost = (state.boost * PersonalLearningPolicy.CORRECTION_DECAY)
            .coerceAtLeast(PersonalLearningPolicy.MIN_BOOST)
        if (word in customWords && state.boost < 1.0) {
            // Its frequency would fall below the custom base: drop it outright.
            customWords.remove(word)
            wordOverrides.remove(word)
            ngramOverrides.keys.removeAll { it.first == word || it.second == word }
        }
    }

    private companion object {
        val CONTEXTUAL_LEARNING_SIGNALS = setOf(
            LearningSignal.DELETE_RETYPE,
            LearningSignal.SUGGESTION_PICK,
            LearningSignal.MANUAL_EDIT,
        )
    }
}
