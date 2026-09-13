package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry

/** Loads the generated dictionary once and exposes an immutable snapshot. */
class EnglishDictionaryRepository(
    private val loadCsv: () -> String,
) {
    private var cached: List<WordEntry>? = null

    fun words(): List<WordEntry> {
        return cached ?: parse(loadCsv()).also { cached = it }
    }

    private fun parse(csv: String): List<WordEntry> = csv.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val columns = line.split(',', limit = 2)
            if (columns.size != 2) return@mapNotNull null
            val word = columns[0].trim()
            val frequency = columns[1].trim().toDoubleOrNull()
            if (word.isEmpty() || frequency == null || frequency <= 0.0) null
            else WordEntry(word, frequency)
        }
        .toList()
}
