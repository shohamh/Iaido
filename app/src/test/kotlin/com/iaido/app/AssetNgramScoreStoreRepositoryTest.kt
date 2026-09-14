package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.language.Language
import java.io.FileNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
}
