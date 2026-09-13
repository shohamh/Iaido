package com.ninjakeys.core.gesture

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
        val result = mutableListOf<GesturePoint>()
        result.add(points.first())

        var segmentIndex = 0
        var distanceIntoSegment = 0f
        var accumulatedTarget = step

        while (result.size < targetPointCount - 1) {
            val segLen = segmentLengths[segmentIndex]
            val remainingInSegment = segLen - distanceIntoSegment
            if (remainingInSegment >= step) {
                distanceIntoSegment += step
                val t = distanceIntoSegment / segLen
                result.add(interpolate(points[segmentIndex], points[segmentIndex + 1], t))
            } else {
                accumulatedTarget = step - remainingInSegment
                segmentIndex++
                distanceIntoSegment = accumulatedTarget
                if (segmentIndex >= segmentLengths.size) break
                val segLen2 = segmentLengths[segmentIndex]
                val t = (distanceIntoSegment / segLen2).coerceIn(0f, 1f)
                result.add(interpolate(points[segmentIndex], points[segmentIndex + 1], t))
            }
        }

        result.add(points.last())
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
