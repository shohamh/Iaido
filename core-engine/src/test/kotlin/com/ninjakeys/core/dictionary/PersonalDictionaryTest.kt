package com.ninjakeys.core.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
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
}
