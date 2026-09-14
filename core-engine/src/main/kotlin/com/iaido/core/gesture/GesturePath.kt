package com.iaido.core.gesture

import kotlin.math.sqrt

data class GesturePath(val points: List<GesturePoint>) {

    init {
        require(points.isNotEmpty()) { "GesturePath must have at least one point" }
    }

    fun resample(targetPointCount: Int): GesturePath {
        require(targetPointCount >= 2) { "targetPointCount must be at least 2" }
        if (points.size == 1) {
            return GesturePath(List(targetPointCount) { points[0] })
        }

        val segmentLengths = points.zipWithNext { a, b -> distance(a, b) }
        val totalLength = segmentLengths.sum()
        if (totalLength == 0f) {
            return GesturePath(List(targetPointCount) { points[0] })
        }

        val step = totalLength / (targetPointCount - 1)
        val cumulativeLengths = segmentLengths.runningFold(0f) { total, segment -> total + segment }
        val result = (0 until targetPointCount).map { sampleIndex ->
            val targetDistance = step * sampleIndex
            if (sampleIndex == targetPointCount - 1) {
                points.last()
            } else {
                val segmentIndex = cumulativeLengths.indexOfFirst { it > targetDistance }
                    .coerceAtLeast(1) - 1
                val segmentStart = cumulativeLengths[segmentIndex]
                val segmentLength = segmentLengths[segmentIndex]
                val fraction = ((targetDistance - segmentStart) / segmentLength).coerceIn(0f, 1f)
                interpolate(points[segmentIndex], points[segmentIndex + 1], fraction)
            }
        }
        return GesturePath(result)
    }

    private fun distance(a: GesturePoint, b: GesturePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun interpolate(a: GesturePoint, b: GesturePoint, t: Float): GesturePoint {
        return GesturePoint(
            x = a.x + (b.x - a.x) * t,
            y = a.y + (b.y - a.y) * t,
            timestampMs = a.timestampMs + ((b.timestampMs - a.timestampMs) * t).toLong(),
        )
    }
}
