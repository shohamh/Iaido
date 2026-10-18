package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.language.Language
import com.iaido.core.recognition.CompactNgramScoreStore
import java.io.File
import java.io.FileNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssetNgramScoreStoreRepositoryTest {
    @Test
    fun `missing optional asset falls back and is cached`() {
        var loads = 0
        val repository = AssetNgramScoreStoreRepository(
            loadAsset = {
                loads += 1
                throw FileNotFoundException(it)
            },
            dictionary = { listOf(WordEntry("hello", 1.0)) },
        )

        val first = repository.store(Language.ENGLISH)
        val second = repository.store(Language.ENGLISH)

        assertSame(first, second)
        assertEquals(1, loads)
    }

    private val assets = File("src/main/assets")

    private fun dictionary(name: String): List<WordEntry> =
        EnglishDictionaryRepository { File(assets, "dictionary/$name.csv").readText() }.words()

    private fun loader(name: String): ByteArray = File(assets, name).readBytes()

    @Test
    fun `both shipped languages load as real stores`() {
        val repository = AssetNgramScoreStoreRepository(
            loadAsset = ::loader,
            dictionary = { if (it == Language.ENGLISH) dictionary("en") else dictionary("he") },
        )

        assertTrue(repository.store(Language.ENGLISH).bigram("of", "course") > 0.0)
        assertTrue(repository.store(Language.HEBREW) !== com.iaido.core.recognition.EmptyNgramScoreStore)
    }

    @Test
    fun `dictionary frequencies are supplied as the unigram prior`() {
        val entries = dictionary("en")
        val ids = entries.mapIndexed { index, entry -> entry.word to index }.toMap()
        val bytes = loader("context-ngrams/en.ngram.bin")
        val uniform = CompactNgramScoreStore.fromBytes(bytes, ids, null)
        val repository = AssetNgramScoreStoreRepository(::loader) { entries }

        val actual = repository.store(Language.ENGLISH).bigram("of", "course")

        assertNotEquals(uniform.bigram("of", "course"), actual, 1e-9)
        val frequencies = entries.associate { it.word to it.frequency }
        val expected = CompactNgramScoreStore.fromBytes(bytes, ids) { frequencies[it] ?: 0.0 }
        assertEquals(expected.bigram("of", "course"), actual, 1e-12)
    }
}
