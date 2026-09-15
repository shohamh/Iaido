package com.iaido.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Debug-only host editor used to verify real InputConnection commits. */
class ImeTestHostActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = intent.getStringExtra(DebugAutoSpaceFixtures.EXTRA_FIXTURE)
        getSharedPreferences(DebugAutoSpaceFixtures.PREFERENCES, MODE_PRIVATE).edit().apply {
            if (fixture.isNullOrBlank()) remove(DebugAutoSpaceFixtures.FIXTURE_KEY)
            else putString(DebugAutoSpaceFixtures.FIXTURE_KEY, fixture)
        }.commit()
        setContentView(R.layout.ime_test_host)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editor = findViewById(R.id.ime_test_editor)
        status = findViewById(R.id.ime_test_status)
        findViewById<Button>(R.id.ime_test_clear).setOnClickListener {
            editor.setText("")
            editor.setSelection(0)
        }
        findViewById<Button>(R.id.ime_test_move_cursor_left).setOnClickListener {
            editor.setSelection((editor.selectionStart - 1).coerceAtLeast(0))
            updateStatus()
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                saveEditorState()
                updateStatus()
            }
            override fun afterTextChanged(text: Editable?) = updateStatus()
        })
        editor.setOnFocusChangeListener { _, _ -> updateStatus() }
        restoreEditorState()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        editor.post {
            editor.requestFocus()
            editor.postDelayed({
                val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }, 500L)
            updateStatus()
        }
    }

    override fun onPause() {
        saveEditorState()
        super.onPause()
    }

    private fun updateStatus() {
        if (!::editor.isInitialized || !::status.isInitialized) return
        status.text = "length=${editor.text.length} selection=${editor.selectionStart}:${editor.selectionEnd}"
    }

    private fun saveEditorState() {
        getPreferences(MODE_PRIVATE).edit()
            .putString(EDITOR_TEXT, editor.text.toString())
            .putInt(EDITOR_SELECTION, editor.selectionStart.coerceAtLeast(0))
            .apply()
    }

    private fun restoreEditorState() {
        val preferences = getPreferences(MODE_PRIVATE)
        val text = preferences.getString(EDITOR_TEXT, "").orEmpty()
        editor.setText(text)
        val savedSelection = preferences.getInt(EDITOR_SELECTION, text.length)
        val selection = if (text.isNotEmpty() && savedSelection == 0) text.length else savedSelection
        editor.setSelection(selection.coerceIn(0, text.length))
    }

    private companion object {
        const val EDITOR_TEXT = "editor_text"
        const val EDITOR_SELECTION = "editor_selection"
    }
}
