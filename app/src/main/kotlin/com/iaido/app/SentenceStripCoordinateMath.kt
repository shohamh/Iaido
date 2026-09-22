package com.iaido.app

/** Small fallback used when Compose layout coordinates are not attached yet. */
internal object SentenceStripCoordinateMath {
    fun contentXFromViewport(
        viewportX: Float,
        viewportLeftInRoot: Float,
        rowLeftInRoot: Float,
    ): Float = viewportX + viewportLeftInRoot - rowLeftInRoot

    fun viewportXFromContent(
        contentX: Float,
        viewportLeftInRoot: Float,
        rowLeftInRoot: Float,
    ): Float = contentX + rowLeftInRoot - viewportLeftInRoot
}
