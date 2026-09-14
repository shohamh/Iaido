package com.ninjakeys.core.recognition

data class SuggestionChip(
    val word: String,
    val alternatives: List<String>,
    val selectedIndex: Int = 0,
    val corrected: Boolean = false,
)

class SuggestionStripState(private val rtl: Boolean) {
    private val selected = mutableMapOf<String, Int>()
    private var chips: List<SuggestionChip> = emptyList()

    fun update(words: List<SuggestionChip>) {
        chips = words.map { it.copy(selectedIndex = selected[it.word] ?: it.selectedIndex) }
    }

    fun chips(): List<SuggestionChip> = if (rtl) chips.asReversed() else chips

    fun tap(index: Int): String? = null

    fun release(index: Int, candidateIndex: Int): String? {
        val chip = chips().getOrNull(index) ?: return null
        val bounded = candidateIndex.coerceIn(0, chip.alternatives.lastIndex)
        selected[chip.word] = bounded
        val updated = chip.copy(selectedIndex = bounded)
        chips = chips().map { if (it.word == chip.word) updated else it }
        return chip.alternatives.getOrNull(bounded)
    }
}
