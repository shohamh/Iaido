package com.ninjakeys.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Debug-only host editor used to verify real InputConnection commits. */
class ImeTestHostActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = updateStatus()
            override fun afterTextChanged(text: Editable?) = updateStatus()
        })
        editor.setOnFocusChangeListener { _, _ -> updateStatus() }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        editor.post {
            editor.requestFocus()
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            updateStatus()
        }
    }

    private fun updateStatus() {
        if (!::editor.isInitialized || !::status.isInitialized) return
        status.text = "length=${editor.text.length} selection=${editor.selectionStart}:${editor.selectionEnd}"
    }
}
