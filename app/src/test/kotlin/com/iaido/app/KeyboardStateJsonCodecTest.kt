package com.iaido.app

import com.iaido.core.commands.CommandBinding
import com.iaido.core.commands.GestureAction
import com.iaido.core.dictionary.NgramOverrideSnapshot
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.dictionary.WordOverrideSnapshot
import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.recognition.SessionWord
import com.iaido.core.state.KeyboardProfileSnapshot
import com.iaido.core.state.KeyboardStateSnapshot
import com.iaido.core.state.TypingSessionSnapshot
import com.iaido.core.typing.SpacingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KeyboardStateJsonCodecTest {
    @Test
    fun `encodes and decodes the complete profile and session`() {
        val snapshot = KeyboardStateSnapshot(
            profile = KeyboardProfileSnapshot(
                spacingMode = SpacingMode.INFER_SPACES,
                flowCorrectionDepth = 2,
                splitGraceWindowMs = 350L,
                commandBindings = listOf(
                    CommandBinding("language", "two-finger-horizontal", GestureAction.SWITCH_LANGUAGE),
                ),
                preferredLanguage = Language.ENGLISH,
                dictionary = PersonalDictionarySnapshot(
                    wordOverrides = listOf(WordOverrideSnapshot("ninjacode", 2.5, 3)),
                    ngramOverrides = listOf(NgramOverrideSnapshot("write", "today", 1.5, 2)),
                    customWords = listOf("ninjacode"),
                ),
            ),
            session = TypingSessionSnapshot(
                language = Language.ENGLISH,
                correctionHistory = SessionCorrectionHistorySnapshot(
                    nextId = 1,
                    entries = listOf(SessionWord(0, 0, 3, "teh", "the", listOf("teh", "the"), true)),
                ),
                cursorPosition = 3,
                pendingCandidates = listOf("the"),
            ),
        )

        assertEquals(snapshot, KeyboardStateJsonCodec.decode(KeyboardStateJsonCodec.encode(snapshot)))
    }

    @Test
    fun `rejects unsupported schema versions before creating a snapshot`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardStateJsonCodec.decode("""{"schemaVersion":99}""")
        }
    }

    @Test
    fun `rejects missing required fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            KeyboardStateJsonCodec.decode("""{"schemaVersion":1}""")
        }
    }
}
