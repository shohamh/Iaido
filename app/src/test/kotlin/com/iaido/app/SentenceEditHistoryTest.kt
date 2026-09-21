package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceEditHistoryTest {
    @Test
    fun historyPreviewsDescribeTheEditDirectionForUndoAndRedo() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(3, "put", "set", SentenceEditKind.CORRECTION))

        assertEquals(
            SentenceHistoryPreview("Undo correction", "set", "put"),
            history.undoPreview(),
        )
        assertTrue(history.undo { HistoryApplyResult.APPLIED })
        assertEquals(
            SentenceHistoryPreview("Redo correction", "put", "set"),
            history.redoPreview(),
        )
    }

    @Test
    fun supportsMultipleUndoRedoStepsAndRestoresSelections() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(0, "", "we", SentenceEditKind.TYPING, before = 0, after = 2))
        history.recordAppliedEdit(edit(2, "", " ", SentenceEditKind.TYPING, before = 2, after = 3))
        history.recordAppliedEdit(edit(3, "", "put", SentenceEditKind.TYPING, before = 3, after = 6))

        val undone = mutableListOf<SentenceEdit>()
        assertTrue(history.undo { candidate -> undone += candidate; HistoryApplyResult.APPLIED })
        assertEquals(6, history.redoCandidate()?.selectionAfterStart)
        assertTrue(history.undo { candidate -> undone += candidate; HistoryApplyResult.APPLIED })
        assertTrue(history.redo { HistoryApplyResult.APPLIED })
        assertTrue(history.redo { HistoryApplyResult.APPLIED })
        assertEquals(2, undone.size)
        assertEquals(6, history.undoCandidate()?.selectionAfterStart)
    }

    @Test
    fun coalescesAdjacentTypedCharactersUntilTheGroupIsClosed() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(0, "", "w", SentenceEditKind.TYPING, before = 0, after = 1, key = "typing"))
        history.recordAppliedEdit(edit(1, "", "e", SentenceEditKind.TYPING, before = 1, after = 2, key = "typing"))
        history.closeGroup()
        history.recordAppliedEdit(edit(2, "", " ", SentenceEditKind.TYPING, before = 2, after = 3))

        assertEquals(2, history.undoCount)
        assertEquals(" ", history.undoCandidate()?.replacementText)
        assertTrue(history.undo { HistoryApplyResult.APPLIED })
        assertEquals("we", history.undoCandidate()?.replacementText)
        assertEquals(0, history.undoCandidate()?.selectionBeforeStart)
        assertEquals(2, history.undoCandidate()?.selectionAfterStart)
    }

    @Test
    fun coalescesRepeatedBackspaceFromRightToLeftIntoOneEdit() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(4, "e", "", SentenceEditKind.BACKSPACE, before = 5, after = 4, key = "backspace"))
        history.recordAppliedEdit(edit(3, "w", "", SentenceEditKind.BACKSPACE, before = 4, after = 3, key = "backspace"))

        assertEquals(1, history.undoCount)
        val deletion = history.undoCandidate()!!
        assertEquals(3, deletion.sourceStart)
        assertEquals("we", deletion.replacedText)
        assertEquals("", deletion.replacementText)
        assertEquals(5, deletion.selectionBeforeStart)
        assertEquals(3, deletion.selectionAfterStart)
    }

    @Test
    fun splitJoinAndMultiWordDeletionCanEachBeOneLogicalEdit() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(2, "alot", "a lot", SentenceEditKind.SPLIT))
        history.recordAppliedEdit(edit(0, "in to", "into", SentenceEditKind.JOIN))
        history.recordAppliedEdit(edit(0, "two three", "", SentenceEditKind.DELETION))

        assertEquals(SentenceEditKind.DELETION, history.undoCandidate()?.kind)
        assertEquals("two three", history.undoCandidate()?.replacedText)
        assertTrue(history.undo { HistoryApplyResult.APPLIED })
        assertEquals(SentenceEditKind.JOIN, history.undoCandidate()?.kind)
        assertTrue(history.undo { HistoryApplyResult.APPLIED })
        assertEquals(SentenceEditKind.SPLIT, history.undoCandidate()?.kind)
    }

    @Test
    fun aNewEditAfterUndoClearsRedoAndHistoryIsBoundedToFortyEntries() {
        val history = SentenceEditHistory()
        repeat(3) { index -> history.recordAppliedEdit(edit(index, "", "$index", SentenceEditKind.TYPING)) }
        assertTrue(history.undo { HistoryApplyResult.APPLIED })
        assertTrue(history.canRedo)
        history.recordAppliedEdit(edit(2, "", "x", SentenceEditKind.TYPING))
        assertFalse(history.canRedo)

        repeat(45) { index -> history.recordAppliedEdit(edit(index, "", "x", SentenceEditKind.CORRECTION)) }
        assertEquals(SentenceEditHistory.MAX_TRANSACTIONS, history.undoCount)
        assertEquals(44, history.undoCandidate()?.sourceStart)
    }

    @Test
    fun failedEditorApplicationLeavesBothStacksUnchanged() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(0, "", "x", SentenceEditKind.TYPING))
        val candidate = history.undoCandidate()

        assertFalse(history.undo { HistoryApplyResult.FAILED })
        assertEquals(1, history.undoCount)
        assertEquals(0, history.redoCount)
        assertEquals(candidate, history.undoCandidate())
    }

    @Test
    fun staleExternalTextDropsOnlyTheInvalidTopAction() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(0, "", "a", SentenceEditKind.TYPING))
        history.recordAppliedEdit(edit(1, "", "b", SentenceEditKind.TYPING))
        history.markExternalEdit()

        assertFalse(history.undo { HistoryApplyResult.STALE })
        assertEquals(1, history.undoCount)
        assertNull(history.redoCandidate())
        assertEquals("a", history.undoCandidate()?.replacementText)
    }

    @Test
    fun cancelsARevertedCoalescedPreviewWithoutLeavingAnUndoEntry() {
        val history = SentenceEditHistory()
        history.recordAppliedEdit(edit(0, "in to", "into", SentenceEditKind.TYPING, key = "inference:0"))
        history.recordAppliedEdit(edit(0, "into", "in to", SentenceEditKind.TYPING, key = "inference:0"))

        assertEquals(0, history.undoCount)
        assertNull(history.undoCandidate())
    }

    private fun edit(
        start: Int,
        beforeText: String,
        afterText: String,
        kind: SentenceEditKind,
        before: Int = start,
        after: Int = start + afterText.length,
        key: String? = null,
    ) = SentenceEdit(
        sourceStart = start,
        sourceEndExclusive = start + beforeText.length,
        replacedText = beforeText,
        replacementText = afterText,
        selectionBeforeStart = before,
        selectionBeforeEnd = before,
        selectionAfterStart = after,
        selectionAfterEnd = after,
        kind = kind,
        coalescingKey = key,
    )
}
