package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.recognition.ReplacementOption

internal data class SentenceStripWord(
    val id: String,
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val above: String?,
    val below: String?,
)

internal data class SentenceStripReplacement(
    val option: ReplacementOption,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val sourceWordIds: List<String>,
)

internal data class SentenceStripState(
    val sentenceText: String,
    val sentenceStart: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val words: List<SentenceStripWord>,
    val replacementOptions: List<SentenceStripReplacement>,
    val language: Language,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoPreview: SentenceHistoryPreview? = null,
    val redoPreview: SentenceHistoryPreview? = null,
)

internal data class SentenceHistoryPreview(
    val actionLabel: String,
    val beforeText: String,
    val afterText: String,
)

internal interface SentenceStripActions {
    fun setSelection(start: Int, endExclusive: Int = start): Boolean
    fun commitWordReplacement(wordId: String, expectedCurrent: String, replacement: String): Boolean
    fun commitReplacement(replacement: SentenceStripReplacement): Boolean
    fun commitDeletion(preview: SentenceDeletionPreview): Boolean
    fun undo(): Boolean
    fun redo(): Boolean
}
