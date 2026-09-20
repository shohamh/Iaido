package com.iaido.app

import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SuggestionChip

/**
 * A replacement [option]'s source words matched against a contiguous run of chips, identified by stable
 * [firstChipId]/[lastChipId] (a chip's `id`, or its position within the matched run when `id` is
 * null) rather than by index -- so callers can look these up safely against a rendering order
 * that may differ from the canonical order this was computed from (e.g. RTL-reversed).
 */
internal data class JoinAttachment(
    val option: ReplacementOption,
    val firstChipId: Int,
    val lastChipId: Int,
)

/**
 * Finds, for each join-shaped or single-source split entry in [options], the contiguous run of
 * [chips] -- in canonical, non-RTL-reversed order, matching the logical order `sourceWords` is in
 * -- whose words equal its `sourceWords` in order. An option with no matching run (stale relative
 * to the current chip list) is omitted; it will attach again once a fresh, matching option arrives.
 */
internal fun attachReplacementCandidates(
    chips: List<SuggestionChip>,
    options: List<ReplacementOption>,
): List<JoinAttachment> =
    options
        .filter { option ->
            option.sourceWords.size > 1 && option.replacementWords.size == 1 ||
                option.sourceWords.size == 1 && option.replacementWords.size > 1
        }
        .mapNotNull { option -> findContiguousRun(chips, option) }

private fun findContiguousRun(chips: List<SuggestionChip>, option: ReplacementOption): JoinAttachment? {
    val sourceWords = option.sourceWords
    if (sourceWords.isEmpty() || sourceWords.size > chips.size) return null
    for (start in 0..chips.size - sourceWords.size) {
        val window = chips.subList(start, start + sourceWords.size)
        if (window.map { it.word } == sourceWords) {
            return JoinAttachment(
                option = option,
                firstChipId = window.first().id ?: start,
                lastChipId = window.last().id ?: (start + sourceWords.size - 1),
            )
        }
    }
    return null
}
