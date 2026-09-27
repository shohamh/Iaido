package com.iaido.app

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/** Debug-host editor that publishes cursor moves made through the IME InputConnection. */
class ImeTestEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle,
) : EditText(context, attrs, defStyleAttr) {
    var onSelectionChanged: (() -> Unit)? = null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        onSelectionChanged?.invoke()
    }
}
