package com.iaido.app

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object ReferenceKeyboardTestState {
    const val PREFERENCES_NAME = "ime_test_reference_keyboard"
    const val COMMIT_REVISION = "commit_revision"
    const val COMMIT_SUCCEEDED = "commit_succeeded"
    const val EDITOR_TEXT_BEFORE_CURSOR = "editor_text_before_cursor"
}

/** Debug-only second keyboard that makes IME switching deterministic. */
class ReferenceInputMethodService : InputMethodService() {
    override fun onCreateInputView(): android.view.View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.DKGRAY)
        contentDescription = "Iaido reference keyboard"
        addView(TextView(context).apply {
            text = "Reference keyboard"
            contentDescription = "Iaido reference keyboard label"
            setTextColor(Color.WHITE)
        })
        addView(Button(context).apply {
            text = "Commit reference"
            contentDescription = "Iaido reference commit"
            setOnClickListener {
                val inputConnection = currentInputConnection
                val commitAccepted = inputConnection?.commitText("reference", 1) == true
                var editorText = ""
                val deadline = SystemClock.elapsedRealtime() + EDITOR_COMMIT_TIMEOUT_MS
                while (commitAccepted && SystemClock.elapsedRealtime() < deadline) {
                    editorText = inputConnection?.getTextBeforeCursor(MAX_EDITOR_CONTEXT, 0)?.toString().orEmpty()
                    if (editorText.endsWith("reference")) break
                    SystemClock.sleep(25L)
                }
                val committed = commitAccepted && editorText.endsWith("reference")
                val state = getSharedPreferences(ReferenceKeyboardTestState.PREFERENCES_NAME, MODE_PRIVATE)
                state.edit()
                    .putLong(
                        ReferenceKeyboardTestState.COMMIT_REVISION,
                        state.getLong(ReferenceKeyboardTestState.COMMIT_REVISION, 0L) + 1L,
                    )
                    .putBoolean(ReferenceKeyboardTestState.COMMIT_SUCCEEDED, committed)
                    .putString(ReferenceKeyboardTestState.EDITOR_TEXT_BEFORE_CURSOR, editorText)
                    .commit()
            }
        })
    }

    private companion object {
        const val EDITOR_COMMIT_TIMEOUT_MS = 5_000L
        const val MAX_EDITOR_CONTEXT = 64
    }
}
