package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.dictionary.LearningSignal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomPersonalDictionaryRepositoryTest {
    private class FakeDao : PersonalOverrideDao {
        val rows = mutableMapOf<String, PersonalOverrideEntity>()
        val ngrams = mutableMapOf<Pair<String, String>, PersonalNgramOverrideEntity>()
        override fun all() = rows.values.toList()
        override fun save(override: PersonalOverrideEntity) { rows[override.word] = override }
        override fun forget(word: String) { rows.remove(word) }
        override fun reset() { rows.clear() }
        override fun allNgrams() = ngrams.values.toList()
        override fun saveNgram(override: PersonalNgramOverrideEntity) { ngrams[override.previousWord to override.nextWord] = override }
        override fun forgetNgrams(word: String) { ngrams.keys.removeAll { it.first == word || it.second == word } }
        override fun resetNgrams() { ngrams.clear() }
    }

    @Test
    fun `reinforcement persists and changes effective frequency`() {
        val dao = FakeDao()
        val repository = RoomPersonalDictionaryRepository(dao, listOf(WordEntry("hello", 1.0)))

        repository.reinforce("hello")

        assertEquals(2.0, repository.entries().single().frequency)
        assertEquals(1, dao.rows["hello"]?.uses)
    }

    @Test
    fun `forget and reset remove persisted overrides`() {
        val dao = FakeDao()
        val repository = RoomPersonalDictionaryRepository(dao, listOf(WordEntry("hello", 1.0)))
        repository.reinforce("hello")

        repository.forget("hello")
        assertEquals(emptyList<PersonalOverrideEntity>(), dao.all())
        repository.reinforce("hello")
        repository.reset()
        assertEquals(emptyList<PersonalOverrideEntity>(), dao.all())
    }

    @Test
    fun `record persists custom words and surrounding ngram overrides`() {
        val dao = FakeDao()
        val repository = RoomPersonalDictionaryRepository(dao, listOf(WordEntry("hello", 1.0)))

        repository.record(
            signal = LearningSignal.DELETE_RETYPE,
            original = "old",
            replacement = "ninjacode",
            previousWord = "write",
            nextWord = "today",
        )

        assertTrue(repository.entries().single { it.word == "ninjacode" }.frequency > 0.01)
        assertTrue(repository.ngramBoost("write", "ninjacode") > 1.0)
        assertTrue(repository.ngramBoost("ninjacode", "today") > 1.0)
    }
}
