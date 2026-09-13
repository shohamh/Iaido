package com.ninjakeys.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class EnglishDictionaryRepositoryTest {
    @Test
    fun `loads valid csv rows and skips malformed rows`() {
        val repository = EnglishDictionaryRepository {
            """
            word,frequency
            the,0.5
            malformed
            ,1.0
            nope,not-a-number
            and,0.2
            """.trimIndent()
        }

        assertEquals(
            listOf("the" to 0.5, "and" to 0.2),
            repository.words().map { it.word to it.frequency },
        )
    }

    @Test
    fun `caches immutable dictionary after first load`() {
        var loads = 0
        val repository = EnglishDictionaryRepository {
            loads += 1
            "word,frequency\nthe,0.5"
        }

        val first = repository.words()
        val second = repository.words()

        assertEquals(1, loads)
        assertSame(first, second)
    }
}
