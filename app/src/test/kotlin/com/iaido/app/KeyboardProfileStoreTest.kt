package com.iaido.app

import com.iaido.core.commands.CommandBinding
import com.iaido.core.commands.GestureAction
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.dictionary.WordOverrideSnapshot
import com.iaido.core.language.Language
import com.iaido.core.state.KeyboardProfileSnapshot
import com.iaido.core.typing.SpacingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KeyboardProfileStoreTest {
    @Test
    fun `read combines authoritative settings and exact dictionary state`() {
        val settings = FakeSettingsStore(
            KeyboardSettings(
                spacingMode = SpacingMode.INFER_SPACES,
                flowCorrectionDepth = 3,
                splitGraceWindowMs = 375L,
                commandBindings = listOf(CommandBinding("language", "two-finger-horizontal", GestureAction.SWITCH_LANGUAGE)),
                preferredLanguage = Language.HEBREW,
            ),
        )
        val dictionary = FakeDictionaryStore(
            PersonalDictionarySnapshot(
                wordOverrides = listOf(WordOverrideSnapshot("ninjacode", 2.0, 4)),
                customWords = listOf("ninjacode"),
            ),
        )
        val store = KeyboardProfileStore(settings, dictionary)

        assertEquals(
            KeyboardProfileSnapshot(
                spacingMode = SpacingMode.INFER_SPACES,
                flowCorrectionDepth = 3,
                splitGraceWindowMs = 375L,
                commandBindings = settings.value.commandBindings,
                preferredLanguage = Language.HEBREW,
                dictionary = dictionary.value,
            ),
            store.read(),
        )
    }

    @Test
    fun `replace rolls back settings when dictionary persistence fails`() {
        val originalSettings = KeyboardSettings()
        val originalDictionary = PersonalDictionarySnapshot()
        val settings = FakeSettingsStore(originalSettings)
        val dictionary = FakeDictionaryStore(originalDictionary, failNextReplace = true)
        val store = KeyboardProfileStore(settings, dictionary)
        val replacement = KeyboardProfileSnapshot(
            spacingMode = SpacingMode.MANUAL,
            flowCorrectionDepth = 1,
            splitGraceWindowMs = 320L,
            commandBindings = originalSettings.commandBindings,
            preferredLanguage = Language.ENGLISH,
            dictionary = PersonalDictionarySnapshot(
                wordOverrides = listOf(WordOverrideSnapshot("hello", 2.0, 1)),
            ),
        )

        assertThrows(IllegalStateException::class.java) { store.replace(replacement) }
        assertEquals(originalSettings, settings.value)
        assertEquals(originalDictionary, dictionary.value)
    }

    private class FakeSettingsStore(var value: KeyboardSettings) : KeyboardSettingsDataSource {
        override fun read() = value
        override fun replace(settings: KeyboardSettings) { value = settings }
    }

    private class FakeDictionaryStore(
        var value: PersonalDictionarySnapshot,
        private var failNextReplace: Boolean = false,
    ) : PersonalDictionarySnapshotDataSource {
        override fun read() = value
        override fun replace(snapshot: PersonalDictionarySnapshot) {
            if (failNextReplace) {
                failNextReplace = false
                throw IllegalStateException("simulated dictionary write failure")
            }
            value = snapshot
        }
    }
}
