package com.iaido.app

/** Small fallback used when Compose layout coordinates are not attached yet. */
internal object SentenceStripCoordinateMath {
    internal data class AxisTransform(
        val contentLeft: Float,
        val viewportLeft: Float,
        val viewportPerContent: Float,
    ) {
        fun viewportXFromContent(contentX: Float): Float =
            viewportLeft + (contentX - contentLeft) * viewportPerContent

        fun contentXFromViewport(viewportX: Float): Float =
            contentLeft + (viewportX - viewportLeft) / viewportPerContent
    }

    fun fromBounds(
        contentLeft: Float,
        contentRight: Float,
        viewportLeft: Float,
        viewportRight: Float,
    ): AxisTransform {
        val contentWidth = contentRight - contentLeft
        val viewportWidth = viewportRight - viewportLeft
        require(contentWidth != 0f)
        return AxisTransform(
            contentLeft = contentLeft,
            viewportLeft = viewportLeft,
            viewportPerContent = viewportWidth / contentWidth,
        )
    }

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
