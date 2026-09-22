package com.iaido.app

import kotlin.math.max
import kotlin.math.min

internal enum class EdgeScrollDirection { LEFT, RIGHT }

internal data class EdgeScrollTarget(
    val direction: EdgeScrollDirection,
    val penetration: Float,
)

/** Pure edge-zone math shared by the held-gesture scroller and focused JVM tests. */
internal object SentenceStripEdgeScrollMath {
    const val MIN_SPEED_CSS_PX_PER_SECOND = 24f
    const val MAX_ADDED_SPEED_CSS_PX_PER_SECOND = 780f
    const val MAX_AFFORDANCE_ALPHA = 0.32f

    fun targetAt(x: Float, viewportWidth: Float, zoneWidth: Float): EdgeScrollTarget? {
        if (viewportWidth <= 0f || zoneWidth <= 0f) return null
        val boundedZone = min(zoneWidth, viewportWidth / 2f)
        val boundedX = x.coerceIn(0f, viewportWidth)
        return when {
            boundedX <= boundedZone -> EdgeScrollTarget(
                EdgeScrollDirection.LEFT,
                (1f - boundedX / boundedZone).coerceIn(0f, 1f),
            )
            boundedX >= viewportWidth - boundedZone -> EdgeScrollTarget(
                EdgeScrollDirection.RIGHT,
                ((boundedX - (viewportWidth - boundedZone)) / boundedZone).coerceIn(0f, 1f),
            )
            else -> null
        }
    }

    fun speedCssPxPerSecond(penetration: Float): Float {
        val p = penetration.coerceIn(0f, 1f)
        return MIN_SPEED_CSS_PX_PER_SECOND + MAX_ADDED_SPEED_CSS_PX_PER_SECOND * p * p
    }

    fun speedPxPerSecond(penetration: Float, density: Float): Float =
        speedCssPxPerSecond(penetration) * max(0f, density)

    fun frameDeltaSeconds(previousFrameNanos: Long, frameNanos: Long): Float =
        ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)

    fun scrollSign(direction: EdgeScrollDirection, isRtl: Boolean): Float {
        // dispatchRawDelta operates on the physical scroll axis. RTL changes
        // word order, but it must not make a left edge scroll rightward.
        return if (direction == EdgeScrollDirection.LEFT) -1f else 1f
    }

    fun affordanceAlpha(penetration: Float): Float =
        MAX_AFFORDANCE_ALPHA * penetration.coerceIn(0f, 1f)
}
