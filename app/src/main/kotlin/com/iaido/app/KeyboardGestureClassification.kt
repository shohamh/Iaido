package com.iaido.app

import com.iaido.core.gesture.GesturePoint
import kotlin.math.abs
import kotlin.math.hypot

/** Treats a short down/up movement inside one key as a tap. */
internal fun isTapGesture(points: List<GesturePoint>, keySizePx: Float): Boolean {
    if (points.size < 2) return true
    val first = points.first()
    val last = points.last()
    return hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble()) <= keySizePx * TAP_MOVEMENT_FRACTION
}

/** A number flick stays near its source key instead of traversing the board. */
internal fun isUpwardFlickGesture(points: List<GesturePoint>, keySizePx: Float): Boolean {
    if (points.size < 2) return false
    val first = points.first()
    val last = points.last()
    val horizontalTravel = abs(last.x - first.x)
    val upwardTravel = first.y - last.y
    return upwardTravel > keySizePx * FLICK_VERTICAL_FRACTION &&
        horizontalTravel <= keySizePx * FLICK_HORIZONTAL_FRACTION
}

private const val TAP_MOVEMENT_FRACTION = 0.25f
private const val FLICK_VERTICAL_FRACTION = 0.5f
private const val FLICK_HORIZONTAL_FRACTION = 0.5f
