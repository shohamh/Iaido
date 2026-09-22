package com.iaido.app

import androidx.compose.ui.unit.LayoutDirection

internal object SentenceStripLayoutMath {
    fun textDirection(isRtl: Boolean): LayoutDirection =
        if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    /** The history controls remain undo-then-redo in physical left-to-right order. */
    fun historyIsUndoOrder(isRtl: Boolean): List<Boolean> = listOf(true, false)
}
