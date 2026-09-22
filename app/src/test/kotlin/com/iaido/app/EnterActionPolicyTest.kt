package com.iaido.app

import android.view.inputmethod.EditorInfo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnterActionPolicyTest {
    @Test
    fun `multiline editors receive literal enter instead of send or done action`() {
        assertFalse(
            EnterActionPolicy.shouldPerformEditorAction(
                EditorInfo.IME_ACTION_SEND,
                isMultiline = true,
            ),
        )
        assertFalse(
            EnterActionPolicy.shouldPerformEditorAction(
                EditorInfo.IME_ACTION_DONE,
                isMultiline = true,
            ),
        )
    }

    @Test
    fun `single line editors still receive their configured action`() {
        assertTrue(
            EnterActionPolicy.shouldPerformEditorAction(
                EditorInfo.IME_ACTION_SEND,
                isMultiline = false,
            ),
        )
    }
}
