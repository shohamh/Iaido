package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.language.Language
import com.ninjakeys.core.recognition.CompactNgramScoreStore
import com.ninjakeys.core.recognition.EmptyNgramScoreStore
import com.ninjakeys.core.recognition.NgramScoreStore
import java.io.IOException

/** Lazily maps the dictionary asset's stable order to a compact n-gram asset. */
class AssetNgramScoreStoreRepository(
    private val loadAsset: (String) -> ByteArray,
    private val dictionary: (Language) -> List<WordEntry>,
) {
    private val cached = mutableMapOf<Language, NgramScoreStore>()

    fun store(language: Language): NgramScoreStore = synchronized(cached) {
        cached.getOrPut(language) {
            try {
                val entries = dictionary(language)
                CompactNgramScoreStore.fromBytes(
                    loadAsset("context-ngrams/${language.assetName}.ngram.bin"),
                    entries.mapIndexed { index, entry -> entry.word to index }.toMap(),
                )
            } catch (_: IOException) {
                EmptyNgramScoreStore
            }
        }
    }

    private val Language.assetName: String
        get() = when (this) {
            Language.ENGLISH -> "en"
            Language.HEBREW -> "he"
        }
}
