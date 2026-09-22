package com.iaido.app

import androidx.compose.ui.unit.LayoutDirection

internal object SentenceStripLayoutMath {
    fun textDirection(isRtl: Boolean): LayoutDirection =
        if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
}
