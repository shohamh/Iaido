package com.iaido.app

import com.iaido.core.recognition.SessionCorrectionHistorySnapshot

internal enum class SentenceEditKind {
    TYPING,
    BACKSPACE,
    CORRECTION,
    SPLIT,
    JOIN,
    DELETION,
}

internal data class SentenceEdit(
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val replacedText: String,
    val replacementText: String,
    val selectionBeforeStart: Int,
    val selectionBeforeEnd: Int,
    val selectionAfterStart: Int,
    val selectionAfterEnd: Int,
    val kind: SentenceEditKind,
    val coalescingKey: String? = null,
    val correctionHistoryBefore: SessionCorrectionHistorySnapshot? = null,
    val correctionHistoryAfter: SessionCorrectionHistorySnapshot? = null,
) {
    init {
        require(sourceStart >= 0) { "Edit start must not be negative" }
        require(sourceEndExclusive >= sourceStart) { "Edit end must not precede start" }
        require(sourceEndExclusive - sourceStart == replacedText.length) {
            "The source span must match the replaced UTF-16 text length"
        }
    }
}

internal enum class HistoryApplyResult {
    APPLIED,
    FAILED,
    STALE,
}

/** Service-owned bounded undo/redo history for logical editor edits. */
internal class SentenceEditHistory {
    private val undoStack = mutableListOf<SentenceEdit>()
    private val redoStack = mutableListOf<SentenceEdit>()
    private var openGroupKey: String? = null
    private var redoBeforeOpenGroup: List<SentenceEdit>? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    fun undoCandidate(): SentenceEdit? = undoStack.lastOrNull()

    fun redoCandidate(): SentenceEdit? = redoStack.lastOrNull()

    fun undoPreview(): SentenceHistoryPreview? = undoCandidate()?.toPreview(undo = true)

    fun redoPreview(): SentenceHistoryPreview? = redoCandidate()?.toPreview(undo = false)

    /** Record only after the corresponding InputConnection edit has succeeded. */
    fun recordAppliedEdit(edit: SentenceEdit) {
        if (edit.replacedText == edit.replacementText) return
        val key = edit.coalescingKey
        if (key != null && key == openGroupKey) {
            val previous = undoStack.lastOrNull()
            val combined = previous?.let { merge(it, edit) }
            if (combined != null) {
                undoStack[undoStack.lastIndex] = combined
                if (combined.replacedText == combined.replacementText) {
                    undoStack.removeAt(undoStack.lastIndex)
                    redoStack.clear()
                    redoBeforeOpenGroup.orEmpty().forEach(redoStack::add)
                    closeGroup()
                }
                return
            }
        }

        closeGroup()
        if (key != null) {
            openGroupKey = key
            redoBeforeOpenGroup = redoStack.toList()
        }
        undoStack += edit
        if (undoStack.size > MAX_TRANSACTIONS) undoStack.removeAt(0)
        redoStack.clear()
    }

    /** Attach service-owned word metadata after a text edit updates correction history. */
    fun attachCorrectionHistorySnapshots(
        before: SessionCorrectionHistorySnapshot,
        after: SessionCorrectionHistorySnapshot,
    ): Boolean {
        val edit = undoStack.lastOrNull()?.takeIf { it.kind == SentenceEditKind.DELETION } ?: return false
        undoStack[undoStack.lastIndex] = edit.copy(
            correctionHistoryBefore = before,
            correctionHistoryAfter = after,
        )
        return true
    }

    /** Apply and move the current undo entry only when [apply] reports success. */
    fun undo(apply: (SentenceEdit) -> HistoryApplyResult): Boolean =
        moveCandidate(undoStack, redoStack, apply)

    /** Apply and move the current redo entry only when [apply] reports success. */
    fun redo(apply: (SentenceEdit) -> HistoryApplyResult): Boolean =
        moveCandidate(redoStack, undoStack, apply)

    /** Close the current typing/backspace/inference group without changing either stack. */
    fun closeGroup() {
        openGroupKey = null
        redoBeforeOpenGroup = null
    }

    /** An external editor change ends coalescing; stale entries are discarded when applied. */
    fun markExternalEdit() = closeGroup()

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        closeGroup()
    }

    private fun moveCandidate(
        from: MutableList<SentenceEdit>,
        to: MutableList<SentenceEdit>,
        apply: (SentenceEdit) -> HistoryApplyResult,
    ): Boolean {
        closeGroup()
        val candidate = from.lastOrNull() ?: return false
        return when (apply(candidate)) {
            HistoryApplyResult.APPLIED -> {
                from.removeAt(from.lastIndex)
                to += candidate
                true
            }
            HistoryApplyResult.FAILED -> false
            HistoryApplyResult.STALE -> {
                from.removeAt(from.lastIndex)
                false
            }
        }
    }

    private fun merge(previous: SentenceEdit, next: SentenceEdit): SentenceEdit? {
        if (previous.kind != next.kind || previous.coalescingKey != next.coalescingKey) return null

        if (previous.sourceStart == next.sourceStart && previous.replacementText == next.replacedText) {
            return previous.copy(
                sourceEndExclusive = previous.sourceStart + previous.replacedText.length,
                replacementText = next.replacementText,
                selectionAfterStart = next.selectionAfterStart,
                selectionAfterEnd = next.selectionAfterEnd,
            )
        }

        if (
            previous.kind == SentenceEditKind.TYPING &&
            next.sourceStart == previous.sourceStart + previous.replacementText.length &&
            next.replacedText.isEmpty()
        ) {
            return previous.copy(
                replacementText = previous.replacementText + next.replacementText,
                selectionAfterStart = next.selectionAfterStart,
                selectionAfterEnd = next.selectionAfterEnd,
            )
        }

        if (
            previous.kind == SentenceEditKind.BACKSPACE &&
            next.replacementText.isEmpty() && previous.replacementText.isEmpty() &&
            next.sourceEndExclusive == previous.sourceStart
        ) {
            return next.copy(
                sourceEndExclusive = next.sourceStart + next.replacedText.length + previous.replacedText.length,
                replacedText = next.replacedText + previous.replacedText,
                selectionBeforeStart = previous.selectionBeforeStart,
                selectionBeforeEnd = previous.selectionBeforeEnd,
            )
        }
        return null
    }

    private fun SentenceEdit.toPreview(undo: Boolean): SentenceHistoryPreview {
        val action = if (undo) "Undo" else "Redo"
        val description = when (kind) {
            SentenceEditKind.TYPING -> "typing"
            SentenceEditKind.BACKSPACE -> "backspace"
            SentenceEditKind.CORRECTION -> "correction"
            SentenceEditKind.SPLIT -> "split"
            SentenceEditKind.JOIN -> "join"
            SentenceEditKind.DELETION -> "deletion"
        }
        return if (undo) {
            SentenceHistoryPreview("$action $description", replacementText, replacedText)
        } else {
            SentenceHistoryPreview("$action $description", replacedText, replacementText)
        }
    }

    companion object {
        const val MAX_TRANSACTIONS = 40
    }
}
