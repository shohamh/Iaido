package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoomPersonalDictionaryRepositoryTest {
    private class FakeDao : PersonalOverrideDao {
        val rows = mutableMapOf<String, PersonalOverrideEntity>()
        override fun all() = rows.values.toList()
        override fun save(override: PersonalOverrideEntity) { rows[override.word] = override }
        override fun forget(word: String) { rows.remove(word) }
        override fun reset() { rows.clear() }
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
}
