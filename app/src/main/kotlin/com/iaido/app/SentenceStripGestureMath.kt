package com.iaido.app

import kotlin.math.abs

/** Separates intentional horizontal scrolling from a held word cursor gesture. */
internal object SentenceStripGestureMath {
    const val STANDARD_HORIZONTAL_SLOP_DP = 7f
    const val WORD_HOLD_HORIZONTAL_SLOP_DP = 20f
    const val CURSOR_SCRUB_ARM_MS = 160L

    fun hasStartedHorizontalScroll(deltaPx: Float, density: Float, hasWordOrigin: Boolean): Boolean {
        val slopDp = if (hasWordOrigin) WORD_HOLD_HORIZONTAL_SLOP_DP else STANDARD_HORIZONTAL_SLOP_DP
        return abs(deltaPx) > slopDp * density.coerceAtLeast(0f)
    }

    fun shouldStartCursorScrub(heldForMs: Long, hasWordOrigin: Boolean): Boolean =
        hasWordOrigin && heldForMs >= CURSOR_SCRUB_ARM_MS

    fun shouldPromoteEdgeScrollToCursor(
        heldForMs: Long,
        hasWordOrigin: Boolean,
        isInEdgeZone: Boolean,
    ): Boolean = shouldStartCursorScrub(heldForMs, hasWordOrigin) && isInEdgeZone
}
