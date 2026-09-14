package com.ninjakeys.core.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalDictionaryTest {
    @Test
    fun `reinforcing a word raises its effective frequency`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0), WordEntry("help", 2.0)))

        dictionary.reinforce("hello")

        assertEquals(2.0, dictionary.entries().first { it.word == "hello" }.frequency)
    }

    @Test
    fun `forget removes an override but keeps the immutable base word`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)))
        dictionary.reinforce("hello")

        dictionary.forget("hello")

        assertEquals(1.0, dictionary.entries().single().frequency)
        assertEquals(emptySet<String>(), dictionary.overrides())
    }

    @Test
    fun `reset clears all learning`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)))
        dictionary.reinforce("hello")

        dictionary.reset()

        assertEquals(emptySet<String>(), dictionary.overrides())
    }

    @Test
    fun `explicitly added words are available even when absent from the base`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)))

        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "ninjacode")

        assertEquals("ninjacode", dictionary.entries().single { it.word == "ninjacode" }.word)
    }

    @Test
    fun `activity decay uses other typed words and never wall clock`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0), WordEntry("other", 1.0)))
        dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "hello")
        val before = dictionary.entries().single { it.word == "hello" }.frequency

        dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "other")
        val after = dictionary.entries().single { it.word == "hello" }.frequency

        assertEquals(true, after < before)
    }

    @Test
    fun `repeated reinforcement has diminishing returns and a hard cap`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)), maxBoost = 4.0)

        dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "hello")
        val first = dictionary.entries().single().frequency
        dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "hello")
        val second = dictionary.entries().single().frequency
        repeat(50) { dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "hello") }

        assertEquals(2.0, first)
        assertEquals(2.5, second)
        assertEquals(4.0, dictionary.entries().single().frequency)
    }

    @Test
    fun `delete and retype reinforces replacement and surrounding ngrams`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("new", 1.0)))

        dictionary.record(
            signal = LearningSignal.DELETE_RETYPE,
            original = "old",
            replacement = "new",
            previousWord = "a",
            nextWord = "day",
        )

        assertTrue(dictionary.entries().single().frequency > 1.0)
        assertTrue(dictionary.ngramBoost("a", "new") > 1.0)
        assertTrue(dictionary.ngramBoost("new", "day") > 1.0)
    }

    @Test
    fun `every learning signal records a usable replacement`() {
        val dictionary = PersonalDictionary(emptyList())

        LearningSignal.values().forEach { signal ->
            dictionary.record(signal, original = "before", replacement = signal.name.lowercase())
        }

        assertEquals(LearningSignal.values().size + 1, dictionary.overrides().size)
    }

    @Test
    fun `restoring persisted entries preserves custom words and boosts`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)))

        dictionary.restore(listOf(WordEntry("hello", 2.5), WordEntry("ninjacode", 0.02)))

        assertEquals(2.5, dictionary.entries().single { it.word == "hello" }.frequency)
        assertEquals(0.02, dictionary.entries().single { it.word == "ninjacode" }.frequency)
    }
}
