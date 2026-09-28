package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SessionWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HistorySplitCandidatesTest {
    @Test
    fun `typed compound offers dictionary-backed split and preserves capitalization`() {
        val word = SessionWord(
            id = 1,
            start = 0,
            end = 4,
            original = "alot",
            current = "Alot",
            candidates = emptyList(),
            corrected = false,
        )

        assertEquals(
            listOf(ReplacementOption(listOf("Alot"), listOf("A", "lot"), 1_000.0)),
            historySplitCandidates(listOf(word), listOf(WordEntry("a", 1_000.0), WordEntry("lot", 1_000.0))),
        )
    }

    @Test
    fun `split candidate requires both parts in the dictionary`() {
        val word = SessionWord(1, 0, 4, "alot", "alot", emptyList(), false)

        assertEquals(
            emptyList<ReplacementOption>(),
            historySplitCandidates(listOf(word), listOf(WordEntry("a", 1_000.0))),
        )
    }
}
