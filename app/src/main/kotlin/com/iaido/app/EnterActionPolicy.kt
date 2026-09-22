package com.iaido.app

/** Selects between an editor action and a literal Enter key event. */
internal object EnterActionPolicy {
    fun shouldPerformEditorAction(action: Int, isMultiline: Boolean): Boolean =
        !isMultiline &&
            action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
            action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
}
