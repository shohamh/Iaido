package com.iaido.app

/** Converts physical content coordinates to ScrollState's logical offset in either direction. */
internal object SentenceStripScrollMath {
    fun viewportXFromContent(
        contentX: Float,
        scrollValuePx: Float,
        maxScrollPx: Float,
        isRtl: Boolean,
    ): Float = contentX - contentOffsetForValue(scrollValuePx, maxScrollPx, isRtl)

    fun contentXFromViewport(
        viewportX: Float,
        scrollValuePx: Float,
        maxScrollPx: Float,
        isRtl: Boolean,
    ): Float = viewportX + contentOffsetForValue(scrollValuePx, maxScrollPx, isRtl)

    fun centeredContentOffset(contentX: Float, viewportWidthPx: Float, maxScrollPx: Float): Float =
        (contentX - viewportWidthPx / 2f).coerceIn(0f, maxScrollPx.coerceAtLeast(0f))

    fun cursorNeedsFollow(viewportX: Float, viewportWidthPx: Float, comfortMarginPx: Float): Boolean =
        viewportX < comfortMarginPx || viewportX > viewportWidthPx - comfortMarginPx

    /** Return a logical scroll value that centers a measured viewport cursor when needed. */
    fun targetValueForCursorViewport(
        cursorViewportX: Float,
        viewportWidthPx: Float,
        currentScrollValuePx: Float,
        maxScrollPx: Float,
        isRtl: Boolean,
        comfortMarginPx: Float,
    ): Float? {
        if (!cursorNeedsFollow(cursorViewportX, viewportWidthPx, comfortMarginPx)) return null
        val currentContentOffset = contentOffsetForValue(currentScrollValuePx, maxScrollPx, isRtl)
        val cursorContentX = cursorViewportX + currentContentOffset
        val centeredOffset = centeredContentOffset(cursorContentX, viewportWidthPx, maxScrollPx)
        return valueForContentOffset(centeredOffset, maxScrollPx, isRtl)
    }

    fun valueForContentOffset(contentOffsetPx: Float, maxScrollPx: Float, isRtl: Boolean): Float {
        val maxScroll = maxScrollPx.coerceAtLeast(0f)
        val boundedOffset = contentOffsetPx.coerceIn(0f, maxScroll)
        return if (isRtl) maxScroll - boundedOffset else boundedOffset
    }

    fun contentOffsetForValue(scrollValuePx: Float, maxScrollPx: Float, isRtl: Boolean): Float {
        val maxScroll = maxScrollPx.coerceAtLeast(0f)
        val boundedValue = scrollValuePx.coerceIn(0f, maxScroll)
        return if (isRtl) maxScroll - boundedValue else boundedValue
    }
}
