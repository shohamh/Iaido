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
    private var replacementCandidates: List<ReplacementOption> = emptyList()
    private var replacementPreview: ReplacementOption? = null
    private var selectedReplacementOption: ReplacementOption? = null

    fun update(words: List<SuggestionChip>) {
        chips = words.mapIndexed { index, chip ->
            val identity = chip.id ?: index
            chip.copy(id = identity, selectedIndex = selected[identity] ?: chip.selectedIndex)
        }
    }

    fun chips(): List<SuggestionChip> = if (rtl) chips.asReversed() else chips

    fun updateReplacementOptions(options: List<ReplacementOption>) {
        replacementCandidates = options.distinctBy(ReplacementOption::id)
        replacementPreview = null
        selectedReplacementOption = selectedReplacementOption?.let { selectedOption ->
            replacementCandidates.firstOrNull { it.id == selectedOption.id }
        }
    }

    /** Returns each complete candidate group in logical word order, even for RTL UI. */
    fun replacementOptions(): List<ReplacementOption> = replacementCandidates

    fun displayedReplacement(): ReplacementOption? =
        replacementPreview ?: selectedReplacementOption ?: replacementCandidates.firstOrNull()

    fun previewReplacement(candidateIndex: Int): ReplacementOption? =
        replacementCandidates.getOrNull(candidateIndex)?.also { replacementPreview = it }

    fun releaseReplacement(candidateIndex: Int): ReplacementOption? =
        replacementCandidates.getOrNull(candidateIndex)?.also {
            selectedReplacementOption = it
            replacementPreview = null
        }

    fun selectedReplacement(): ReplacementOption? = selectedReplacementOption

    fun cancelReplacement() {
        replacementPreview = null
    }

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
