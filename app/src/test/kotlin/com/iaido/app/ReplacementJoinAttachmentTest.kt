package com.iaido.app

import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SuggestionChip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplacementJoinAttachmentTest {
    private fun chip(word: String) = SuggestionChip(word = word, alternatives = listOf(word))

    @Test
    fun `a join option matching adjacent chips attaches to their contiguous run`() {
        val chips = listOf(chip("wh"), chip("at"))
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        val attachments = attachReplacementCandidates(chips, listOf(option))

        assertEquals(1, attachments.size)
        assertEquals(option, attachments.first().option)
        assertEquals(0, attachments.first().firstChipId)
        assertEquals(1, attachments.first().lastChipId)
    }

    @Test
    fun `a join option is found at either end of a longer chip list`() {
        val chips = listOf(chip("hello"), chip("wh"), chip("at"))
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        val attachments = attachReplacementCandidates(chips, listOf(option))

        // Chips here have no explicit id, so it falls back to position within the matched run.
        assertEquals(1, attachments.first().firstChipId)
        assertEquals(2, attachments.first().lastChipId)
    }

    @Test
    fun `a chip's stable id is used over its position when both are available`() {
        val chips = listOf(
            SuggestionChip(word = "hello", alternatives = listOf("hello"), id = 10),
            SuggestionChip(word = "wh", alternatives = listOf("wh"), id = 11),
            SuggestionChip(word = "at", alternatives = listOf("at"), id = 12),
        )
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        val attachments = attachReplacementCandidates(chips, listOf(option))

        assertEquals(11, attachments.first().firstChipId)
        assertEquals(12, attachments.first().lastChipId)
    }

    @Test
    fun `a stale join option with no matching contiguous run is dropped`() {
        val chips = listOf(chip("hello"), chip("world"))
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        assertTrue(attachReplacementCandidates(chips, listOf(option)).isEmpty())
    }

    @Test
    fun `a single-word split option attaches to its source chip`() {
        val chips = listOf(chip("inthe"))
        val split = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.9)

        val attachments = attachReplacementCandidates(chips, listOf(split))

        assertEquals(1, attachments.size)
        assertEquals(split, attachments.single().option)
        assertEquals(0, attachments.single().firstChipId)
        assertEquals(0, attachments.single().lastChipId)
    }
}
