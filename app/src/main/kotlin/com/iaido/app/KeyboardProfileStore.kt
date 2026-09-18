package com.iaido.app

import androidx.room.RoomDatabase
import com.iaido.core.dictionary.NgramOverrideSnapshot
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.dictionary.WordOverrideSnapshot
import com.iaido.core.dictionary.WordEntry
import com.iaido.core.state.KeyboardProfileSnapshot

internal interface PersonalDictionarySnapshotDataSource {
    fun read(): PersonalDictionarySnapshot
    fun replace(snapshot: PersonalDictionarySnapshot)
}

internal class KeyboardProfileStore(
    private val settings: KeyboardSettingsDataSource,
    private val dictionary: PersonalDictionarySnapshotDataSource,
) {
    fun read(): KeyboardProfileSnapshot {
        val resolvedSettings = settings.read()
        return KeyboardProfileSnapshot(
            spacingMode = resolvedSettings.spacingMode,
            flowCorrectionDepth = resolvedSettings.flowCorrectionDepth,
            splitGraceWindowMs = resolvedSettings.splitGraceWindowMs,
            commandBindings = resolvedSettings.commandBindings,
            preferredLanguage = resolvedSettings.preferredLanguage,
            dictionary = dictionary.read(),
        )
    }

    fun replace(snapshot: KeyboardProfileSnapshot) {
        val previousSettings = settings.read()
        val previousDictionary = dictionary.read()
        val replacementSettings = KeyboardSettings(
            spacingMode = snapshot.spacingMode,
            flowCorrectionDepth = snapshot.flowCorrectionDepth,
            splitGraceWindowMs = snapshot.splitGraceWindowMs,
            commandBindings = snapshot.commandBindings,
            preferredLanguage = snapshot.preferredLanguage,
        )
        try {
            settings.replace(replacementSettings)
            dictionary.replace(snapshot.dictionary)
        } catch (error: Throwable) {
            runCatching { settings.replace(previousSettings) }
            runCatching { dictionary.replace(previousDictionary) }
            throw error
        }
    }
}

internal class RoomPersonalDictionarySnapshotDataSource(
    private val database: PersonalDictionaryDatabase,
    base: List<WordEntry>,
) : PersonalDictionarySnapshotDataSource {
    private val baseWords = base.map { it.word.lowercase() }.toSet()
    private val dao = database.overrides()

    override fun read(): PersonalDictionarySnapshot {
        val words = dao.all()
            .map { row -> WordOverrideSnapshot(row.word.lowercase(), row.boost, row.uses) }
            .distinctBy { it.word }
            .sortedBy { it.word }
        val ngrams = dao.allNgrams()
            .map { row -> NgramOverrideSnapshot(row.previousWord.lowercase(), row.nextWord.lowercase(), row.boost, row.uses) }
            .distinctBy { it.previousWord to it.nextWord }
            .sortedWith(compareBy({ it.previousWord }, { it.nextWord }))
        return PersonalDictionarySnapshot(
            wordOverrides = words,
            ngramOverrides = ngrams,
            customWords = words.map { it.word }.filterNot(baseWords::contains),
        )
    }

    override fun replace(snapshot: PersonalDictionarySnapshot) {
        database.runInTransaction {
            dao.reset()
            dao.resetNgrams()
            snapshot.wordOverrides.forEach { override ->
                dao.save(PersonalOverrideEntity(override.word, override.boost, override.uses))
            }
            snapshot.ngramOverrides.forEach { override ->
                dao.saveNgram(
                    PersonalNgramOverrideEntity(
                        override.previousWord,
                        override.nextWord,
                        override.boost,
                        override.uses,
                    ),
                )
            }
        }
    }
}
