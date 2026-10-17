package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.dictionary.LearningSignal
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

        repository.record(LearningSignal.EXPLICIT_ADD, replacement = "ninjacode")
        repository.record(
            signal = LearningSignal.DELETE_RETYPE,
            original = "old",
            replacement = "ninjacode",
            previousWord = "write",
            nextWord = "today",
        )

        // Updated: old expectation (> 0.01) encoded the absurd custom-word base frequency.
        val frequency = repository.entries().single { it.word == "ninjacode" }.frequency
        assertTrue(frequency > 1e-6 && frequency < 1e-4, "frequency $frequency")
        assertTrue(repository.ngramBoost("write", "ninjacode") > 1.0)
        assertTrue(repository.ngramBoost("ninjacode", "today") > 1.0)
    }

    private val realBase = listOf(
        WordEntry("are", 0.0055), WordEntry("you", 0.01), WordEntry("happens", 9.1e-5),
    )

    @Test
    fun `correcting a custom word removes it and automatic commits do not relearn it`() {
        val dao = FakeDao()
        val repository = RoomPersonalDictionaryRepository(dao, realBase)
        repository.record(LearningSignal.EXPLICIT_ADD, replacement = "areyou")
        repository.record(LearningSignal.MANUAL_EDIT, original = "areyou", replacement = "are")

        assertTrue(repository.entries().none { it.word == "areyou" })
        assertTrue(dao.rows.keys.none { it == "areyou" })

        repository.record(LearningSignal.DELETE_RETYPE, original = "are", replacement = "areyou")
        repository.record(LearningSignal.FLOW_CORRECTION, original = "are", replacement = "areyou")
        repository.record(LearningSignal.SUGGESTION_PICK, original = "are", replacement = "areyou")
        assertTrue(repository.entries().none { it.word == "areyou" })
        assertTrue(dao.rows.keys.none { it == "areyou" })
    }

    @Test
    fun `custom words stay below happens and multiword replacements are not learned`() {
        val dao = FakeDao()
        val repository = RoomPersonalDictionaryRepository(dao, realBase)
        repeat(30) { repository.record(LearningSignal.MANUAL_EDIT, replacement = "hawthorne") }
        repository.record(LearningSignal.MANUAL_EDIT, original = "x", replacement = "are you")

        val entries = repository.entries().associate { it.word to it.frequency }
        assertTrue(entries.getValue("hawthorne") < 1e-4)
        assertTrue(entries.getValue("happens") > entries.getValue("hawthorne"))
        assertTrue(entries.keys.none { it.contains(' ') })
    }

    @Test
    fun `forget removes a custom word and only its own ngrams`() {
        val dao = FakeDao()
        val repository = RoomPersonalDictionaryRepository(dao, realBase)
        repository.record(LearningSignal.EXPLICIT_ADD, replacement = "ninjacode")
        repository.record(LearningSignal.DELETE_RETYPE, replacement = "ninjacode", previousWord = "write", nextWord = "today")
        repository.record(LearningSignal.DELETE_RETYPE, replacement = "happens", previousWord = "write", nextWord = "now")

        repository.forget("Ninjacode")

        assertTrue(repository.entries().none { it.word == "ninjacode" })
        assertEquals(1.0, repository.ngramBoost("write", "ninjacode"))
        assertTrue(repository.ngramBoost("write", "happens") > 1.0)
    }

    @Test
    fun `persisted over-cap boosts are clamped`() {
        val dao = FakeDao().apply { rows["happens"] = PersonalOverrideEntity("happens", 32.0, 5) }
        val repository = RoomPersonalDictionaryRepository(dao, realBase)
        assertEquals(9.1e-5 * 6.0, repository.entries().single { it.word == "happens" }.frequency, 1e-12)
    }

    @Test
    fun `persisted sentence capitalization is folded into the lowercase dictionary word`() {
        val dao = FakeDao().apply {
            rows["There"] = PersonalOverrideEntity("There", 2.0, 1)
        }
        val repository = RoomPersonalDictionaryRepository(dao, listOf(WordEntry("there", 1.0)))

        assertEquals(listOf("there"), repository.entries().map { it.word })
    }
}
