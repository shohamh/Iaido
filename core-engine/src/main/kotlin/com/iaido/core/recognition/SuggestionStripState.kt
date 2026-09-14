package com.iaido.core.recognition

data class SuggestionChip(
    val word: String,
    val alternatives: List<String>,
    val selectedIndex: Int = 0,
    val corrected: Boolean = false,
    val id: Int? = null,
)

class SuggestionStripState(private val rtl: Boolean) {
    private val selected = mutableMapOf<Int, Int>()
    private var chips: List<SuggestionChip> = emptyList()

    fun update(words: List<SuggestionChip>) {
        chips = words.mapIndexed { index, chip ->
            val identity = chip.id ?: index
            chip.copy(id = identity, selectedIndex = selected[identity] ?: chip.selectedIndex)
        }
    }

    fun chips(): List<SuggestionChip> = if (rtl) chips.asReversed() else chips

    fun tap(index: Int): String? = null

    fun release(index: Int, candidateIndex: Int): String? {
        val canonicalIndex = if (rtl) chips.lastIndex - index else index
        val chip = chips.getOrNull(canonicalIndex) ?: return null
        if (chip.alternatives.isEmpty()) return null
        val bounded = candidateIndex.coerceIn(0, chip.alternatives.lastIndex)
        selected[chip.id ?: canonicalIndex] = bounded
        val updated = chip.copy(selectedIndex = bounded)
        chips = chips.mapIndexed { itemIndex, item -> if (itemIndex == canonicalIndex) updated else item }
        return chip.alternatives.getOrNull(bounded)
    }
}
