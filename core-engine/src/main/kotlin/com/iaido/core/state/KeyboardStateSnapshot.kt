package com.iaido.core.state

import com.iaido.core.commands.CommandBinding
import com.iaido.core.dictionary.PersonalDictionarySnapshot
import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.typing.SpacingMode

data class KeyboardStateSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val profile: KeyboardProfileSnapshot,
    val session: TypingSessionSnapshot? = null,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported keyboard snapshot version: $schemaVersion" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class KeyboardProfileSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val spacingMode: SpacingMode,
    val flowCorrectionDepth: Int,
    val splitGraceWindowMs: Long,
    val commandBindings: List<CommandBinding>,
    val preferredLanguage: Language,
    val dictionary: PersonalDictionarySnapshot,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported profile snapshot version: $schemaVersion" }
        require(flowCorrectionDepth in 0..8) { "Flow-correction depth is outside the supported range" }
        require(splitGraceWindowMs in 0..5_000L) { "Split grace window is outside the supported range" }
        require(commandBindings.map { it.slot }.distinct().size == commandBindings.size) {
            "Profile contains duplicate command slots"
        }
        require(commandBindings.map { it.trigger }.distinct().size == commandBindings.size) {
            "Profile contains duplicate command triggers"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class TypingSessionSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val language: Language,
    val correctionHistory: SessionCorrectionHistorySnapshot,
    val cursorPosition: Int,
    val pendingCandidates: List<String>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported typing-session snapshot version: $schemaVersion" }
        require(cursorPosition >= 0) { "Cursor position must not be negative" }
        require(pendingCandidates.all { it.isNotBlank() }) { "Pending candidates must not be blank" }
        require(pendingCandidates.distinct().size == pendingCandidates.size) {
            "Pending candidates must not be duplicated"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
