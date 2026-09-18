package com.iaido.core.state

import com.iaido.core.commands.CommandBinding
import com.iaido.core.commands.GestureAction
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.typing.SpacingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyboardStateSnapshotTest {
    @Test
    fun `profile and session snapshot retain their versioned structure`() {
        val profile = KeyboardProfileSnapshot(
            spacingMode = SpacingMode.INFER_SPACES,
            flowCorrectionDepth = 2,
            splitGraceWindowMs = 350L,
            commandBindings = listOf(CommandBinding("language", "two-finger-horizontal", GestureAction.SWITCH_LANGUAGE)),
            preferredLanguage = Language.ENGLISH,
            dictionary = PersonalDictionarySnapshot(),
        )
        val history = SessionCorrectionHistorySnapshot(
            nextId = 1,
            entries = listOf(
                com.iaido.core.recognition.SessionWord(
                    id = 0,
                    start = 0,
                    end = 3,
                    original = "teh",
                    current = "the",
                    candidates = listOf("teh", "the"),
                    corrected = true,
                ),
            ),
        )

        val snapshot = KeyboardStateSnapshot(
            profile = profile,
            session = TypingSessionSnapshot(
                language = Language.ENGLISH,
                correctionHistory = history,
                cursorPosition = 3,
                pendingCandidates = listOf("the"),
            ),
        )

        assertEquals(1, snapshot.schemaVersion)
        assertEquals(profile, snapshot.profile)
        assertEquals(history, snapshot.session?.correctionHistory)
    }
}
