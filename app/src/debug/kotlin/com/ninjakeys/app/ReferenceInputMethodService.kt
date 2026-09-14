package com.ninjakeys.app

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
        contentDescription = "NinjaKeys reference keyboard"
        addView(TextView(context).apply {
            text = "Reference keyboard"
            contentDescription = "NinjaKeys reference keyboard label"
            setTextColor(Color.WHITE)
        })
        addView(Button(context).apply {
            text = "Commit reference"
            contentDescription = "NinjaKeys reference commit"
            setOnClickListener { currentInputConnection?.commitText("reference", 1) }
        })
    }
}
