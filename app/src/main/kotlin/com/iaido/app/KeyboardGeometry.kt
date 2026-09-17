package com.iaido.app

internal const val KEYBOARD_COLUMN_COUNT = 10
internal const val KEYBOARD_ROW_COUNT = 4

internal fun keyboardRowOffsetUnits(rowLength: Int, columnCount: Int = KEYBOARD_COLUMN_COUNT): Float =
    ((columnCount - rowLength).coerceAtLeast(0)) / 2f

internal fun keyboardSurfaceHeightPx(keySizePx: Float, rowCount: Int = KEYBOARD_ROW_COUNT): Float =
    keySizePx * rowCount

internal fun keyboardKeyHeightPx(keySizePx: Float): Float = keySizePx

internal fun imeContentHeightPx(keySizePx: Float, bottomInsetPx: Float): Float =
    keyboardSurfaceHeightPx(keySizePx) + bottomInsetPx.coerceAtLeast(0f)
