package com.iaido.app

import android.inputmethodservice.InputMethodService
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
            setOnClickListener { currentInputConnection?.commitText("reference", 1) }
        })
    }
}
