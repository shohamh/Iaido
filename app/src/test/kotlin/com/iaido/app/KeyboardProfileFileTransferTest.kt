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

class KeyboardProfileFileTransferTest {
    @Test
    fun `round trips profile with checksum and version metadata`() {
        val profile = KeyboardProfileSnapshot(
            spacingMode = SpacingMode.AFTER_SWIPE,
            flowCorrectionDepth = 3,
            splitGraceWindowMs = 375,
            commandBindings = listOf(CommandBinding("language", "two-finger-horizontal", GestureAction.SWITCH_LANGUAGE)),
            preferredLanguage = Language.HEBREW,
            dictionary = PersonalDictionarySnapshot(
                wordOverrides = listOf(WordOverrideSnapshot("shalom", 2.0, 4)),
                customWords = listOf("shalom"),
            ),
        )

        assertEquals(profile, KeyboardProfileFileTransfer.decode(KeyboardProfileFileTransfer.encode(profile, "0.1.3")))
    }

    @Test
    fun `rejects tampered profile before returning it`() {
        val profile = KeyboardProfileSnapshot(
            spacingMode = SpacingMode.MANUAL,
            flowCorrectionDepth = 2,
            splitGraceWindowMs = 350,
            commandBindings = emptyList(),
            preferredLanguage = Language.ENGLISH,
            dictionary = PersonalDictionarySnapshot(),
        )
        val contents = KeyboardProfileFileTransfer.encode(profile, "0.1.3")
            .replace("\"spacingMode\":\"MANUAL\"", "\"spacingMode\":\"AFTER_SWIPE\"")

        assertThrows(IllegalArgumentException::class.java) {
            KeyboardProfileFileTransfer.decode(contents)
        }
    }
}
