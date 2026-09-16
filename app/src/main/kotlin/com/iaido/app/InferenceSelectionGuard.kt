package com.iaido.app

/**
 * Tracks a self-inflicted selection change made while committing an inference replacement, so that
 * [IaidoInputMethodService.onUpdateSelection] can tell it apart from a genuine external cursor move
 * (e.g. the user tapping elsewhere in the text).
 *
 * `replaceInferenceHostSpan`'s `setSelection`/`commitText` calls on the [android.view.inputmethod.InputConnection]
 * trigger one or more asynchronous `onUpdateSelection` callbacks from the framework. Those callbacks can
 * arrive on the main looper *after* the synchronous call that produced the edit has already returned, so a
 * plain "in progress" boolean that is cleared immediately once the edit call returns is racy: it can flip
 * back to `false` before the corresponding callback(s) for that same edit are delivered, causing them to be
 * misclassified as an external cursor move and incorrectly finalizing/clearing the in-flight inference
 * transaction (`SwipeTypingCoordinator.onCursorMoved()`).
 *
 * This guard instead stays armed — suppressing every selection update it is asked about — until the
 * framework actually reports the exact selection the edit produced (condition-based, not time-based), at
 * which point it disarms itself. [shouldSuppress] also accepts a bounded [timeoutMs] fallback in case the
 * expected callback never arrives, so a missed/reordered callback cannot permanently swallow subsequent
 * genuine cursor-move detection.
 */
class InferenceSelectionGuard(private val nowMs: () -> Long = System::currentTimeMillis) {
    private var expected: Pair<Int, Int>? = null
    private var armedAtMs: Long = 0L

    /** Arms the guard: the next selection update matching [selectionStart]/[selectionEnd] disarms it. */
    fun arm(selectionStart: Int, selectionEnd: Int) {
        expected = selectionStart to selectionEnd
        armedAtMs = nowMs()
    }

    /**
     * Call from `onUpdateSelection` with the reported selection. Returns `true` if this update should be
     * treated as self-inflicted (suppressed) rather than a genuine external cursor move.
     *
     * Every update observed while armed is suppressed, since intermediate framework callbacks for the same
     * edit can report transient selection values that don't exactly match the final expected one. The guard
     * disarms itself once it sees the exact expected selection, or once [timeoutMs] has elapsed since [arm]
     * was called, whichever comes first.
     */
    fun shouldSuppress(selectionStart: Int, selectionEnd: Int, timeoutMs: Long): Boolean {
        val current = expected ?: return false
        if (nowMs() - armedAtMs > timeoutMs) {
            expected = null
            return false
        }
        if (current.first == selectionStart && current.second == selectionEnd) {
            expected = null
        }
        return true
    }

    /** Disarms the guard unconditionally (e.g. on lifecycle boundaries like `onFinishInput`). */
    fun clear() {
        expected = null
    }
}
