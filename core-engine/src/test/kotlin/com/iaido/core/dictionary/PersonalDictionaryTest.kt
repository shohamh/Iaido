package com.iaido.core.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PersonalDictionaryTest {
    @Test
    fun `reuses the immutable entry snapshot until learning changes`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)))

        val first = dictionary.entries()
        val second = dictionary.entries()
        assertSame(first, second)

        dictionary.reinforce("hello")
        assertEquals(2.0, dictionary.entries().single().frequency)
    }

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
    fun `explicit suggestion and manual corrections reinforce surrounding ngrams only`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("today", 1.0), WordEntry("there", 1.0)))

        dictionary.record(
            signal = LearningSignal.SUGGESTION_PICK,
            original = "tomorrow",
            replacement = "today",
            previousWord = "see",
            nextWord = "there",
        )
        dictionary.record(
            signal = LearningSignal.MANUAL_EDIT,
            original = "thier",
            replacement = "there",
            previousWord = "see",
            nextWord = "now",
        )
        dictionary.record(
            signal = LearningSignal.FLOW_CORRECTION,
            original = "there",
            replacement = "today",
            previousWord = "see",
            nextWord = "now",
        )

        assertTrue(dictionary.ngramBoost("see", "today") > 1.0)
        assertTrue(dictionary.ngramBoost("today", "there") > 1.0)
        assertTrue(dictionary.ngramBoost("see", "there") > 1.0)
        assertTrue(dictionary.ngramBoost("there", "now") > 1.0)
        assertEquals(1.0, dictionary.ngramBoost("today", "now"))
    }

    @Test
    fun `restores persisted contextual pair boosts`() {
        val source = PersonalDictionary(listOf(WordEntry("today", 1.0)))
        source.record(
            LearningSignal.SUGGESTION_PICK,
            original = "tomorrow",
            replacement = "today",
            previousWord = "see",
        )
        val restored = PersonalDictionary(listOf(WordEntry("today", 1.0)))

        restored.restoreNgramOverrides(source.snapshot().ngramOverrides)

        assertEquals(source.ngramBoost("see", "today"), restored.ngramBoost("see", "today"))
    }

    @Test
    fun `every learning signal records a usable replacement`() {
        val dictionary = PersonalDictionary(LearningSignal.values().map { WordEntry(it.name.lowercase(), 1e-3) })

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
        // 0.02 is far above the custom ceiling, so it is clamped to base * maxBoost.
        assertEquals(
            PersonalLearningPolicy.CUSTOM_WORD_BASE_FREQUENCY * PersonalLearningPolicy.DEFAULT_MAX_BOOST,
            dictionary.entries().single { it.word == "ninjacode" }.frequency, 1e-12,
        )
    }

    @Test
    fun `learning keeps sentence capitalization out of recognition words`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("there", 1.0)))

        dictionary.record(LearningSignal.DELETE_RETYPE, replacement = "There")
        dictionary.restore(listOf(WordEntry("There", 2.0)))

        assertEquals(listOf("there"), dictionary.entries().map { it.word })
    }

    @Test
    fun `snapshot preserves exact word and ngram learning state`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)))
        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "ninjacode")
        dictionary.record(
            signal = LearningSignal.DELETE_RETYPE,
            original = "old",
            replacement = "ninjacode",
            previousWord = "write",
            nextWord = "today",
        )

        val snapshot = dictionary.snapshot()
        val restored = PersonalDictionary(listOf(WordEntry("hello", 1.0)))
        restored.restore(snapshot)

        assertEquals(snapshot, restored.snapshot())
        assertEquals(dictionary.entries(), restored.entries())
        assertEquals(dictionary.ngramBoost("write", "ninjacode"), restored.ngramBoost("write", "ninjacode"))
        assertEquals(dictionary.ngramBoost("ninjacode", "today"), restored.ngramBoost("ninjacode", "today"))
    }

    @Test
    fun `rejecting an over-cap snapshot leaves existing learning unchanged`() {
        val dictionary = PersonalDictionary(listOf(WordEntry("hello", 1.0)), maxBoost = 2.0)
        dictionary.reinforce("hello")
        val before = dictionary.snapshot()
        val invalid = PersonalDictionarySnapshot(
            wordOverrides = listOf(WordOverrideSnapshot("hello", 3.0, 1)),
        )

        assertThrows(IllegalArgumentException::class.java) { dictionary.restore(invalid) }
        assertEquals(before, dictionary.snapshot())
    }

    private fun realisticBase() = listOf(
        WordEntry("are", 0.0055), WordEntry("you", 0.01), WordEntry("what", 0.003),
        WordEntry("happens", 9.1e-5), WordEntry("still", 0.001),
    )

    @Test
    fun `custom word frequency stays near the rare end after many reinforcements`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "ninjacode")
        repeat(50) { dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "ninjacode") }

        val frequency = dictionary.entries().single { it.word == "ninjacode" }.frequency
        assertTrue(frequency < 1e-4, "custom frequency $frequency")
        assertTrue(frequency > 1e-6)
    }

    @Test
    fun `a real word outranks a freshly custom word`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "hawthorne")
        val entries = dictionary.entries().associate { it.word to it.frequency }
        assertTrue(entries.getValue("happens") > entries.getValue("hawthorne"))
    }

    @Test
    fun `correcting a custom word removes it and it is not relearned by automatic commits`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "areyou")
        dictionary.record(LearningSignal.MANUAL_EDIT, original = "areyou", replacement = "are")

        assertTrue(dictionary.entries().none { it.word == "areyou" })
        assertFalse(dictionary.overrides().contains("areyou"))

        dictionary.record(LearningSignal.DELETE_RETYPE, original = "are", replacement = "areyou")
        dictionary.record(LearningSignal.FLOW_CORRECTION, original = "are", replacement = "areyou")
        assertTrue(dictionary.entries().none { it.word == "areyou" })
        assertFalse(dictionary.overrides().contains("areyou"))
    }

    @Test
    fun `inferred joins of two dictionary words are only learned when explicitly added`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.SUGGESTION_PICK, replacement = "areyou")
        dictionary.record(LearningSignal.MANUAL_EDIT, replacement = "areyou")
        assertTrue(dictionary.entries().none { it.word == "areyou" })
        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "areyou")
        assertTrue(dictionary.entries().any { it.word == "areyou" })
    }

    @Test
    fun `multi word replacements are never learned as one word`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.MANUAL_EDIT, original = "areyou", replacement = "are you")
        assertTrue(dictionary.entries().none { it.word.contains(' ') })
        assertFalse(dictionary.overrides().any { it.contains(' ') })
    }

    @Test
    fun `automatic signals never create custom words`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.FLOW_CORRECTION, original = "what", replacement = "wyatt")
        dictionary.record(LearningSignal.DELETE_RETYPE, original = "what", replacement = "wyatt")
        assertTrue(dictionary.entries().none { it.word == "wyatt" })
    }

    @Test
    fun `corrections penalize the replaced known word strongly`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.MANUAL_EDIT, original = "still", replacement = "what")
        val still = dictionary.entries().single { it.word == "still" }.frequency
        assertTrue(still <= 0.001 / 4.0 + 1e-12, "still=$still")
    }

    @Test
    fun `boosted known word stays under a sane ceiling`() {
        val dictionary = PersonalDictionary(realisticBase())
        repeat(100) { dictionary.record(LearningSignal.DELETE_RETYPE, replacement = "happens") }
        assertTrue(dictionary.entries().single { it.word == "happens" }.frequency <= 9.1e-5 * 8.0)
    }

    @Test
    fun `forget removes a custom word completely`() {
        val dictionary = PersonalDictionary(realisticBase())
        dictionary.record(LearningSignal.EXPLICIT_ADD, replacement = "ninjacode")
        dictionary.forget("ninjacode")
        assertTrue(dictionary.entries().none { it.word == "ninjacode" })
        assertEquals(emptyList<String>(), dictionary.snapshot().customWords)
    }
}
