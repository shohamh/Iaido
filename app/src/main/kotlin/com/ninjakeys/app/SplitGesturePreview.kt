package com.ninjakeys.app

import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout

/** Returns the best-effort word currently represented by split gesture paths. */
fun splitGesturePreview(paths: List<List<GesturePoint>>, layout: KeyboardLayout): String =
    paths.asSequence()
        .flatMap { path ->
            path.asSequence().mapNotNull { point ->
                layout.keys.minByOrNull { key ->
                    val dx = point.x - key.x
                    val dy = point.y - key.y
                    dx * dx + dy * dy
                }?.letter
            }
        }
        .fold(StringBuilder()) { result, letter ->
            if (result.lastOrNull() != letter) result.append(letter)
            result
        }
        .toString()
