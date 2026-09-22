package com.iaido.app

internal const val KEYBOARD_COLUMN_COUNT = 10
internal const val KEYBOARD_ROW_COUNT = 5
internal const val KEYBOARD_ROW_COUNT_WITHOUT_NUMBER_ROW = 4

internal fun keyboardRowCount(showNumberRow: Boolean): Int =
    if (showNumberRow) KEYBOARD_ROW_COUNT else KEYBOARD_ROW_COUNT_WITHOUT_NUMBER_ROW

internal fun keyboardRowOffsetUnits(rowLength: Int, columnCount: Int = KEYBOARD_COLUMN_COUNT): Float =
    ((columnCount - rowLength).coerceAtLeast(0)) / 2f

internal fun keyboardSurfaceHeightPx(keySizePx: Float, rowCount: Int = KEYBOARD_ROW_COUNT): Float =
    keyboardKeyHeightPx(keySizePx) * rowCount

internal fun keyboardKeyHeightPx(keySizePx: Float): Float = keySizePx

internal fun keyboardBottomRowTopPx(
    keySizePx: Float,
    rowCount: Int = KEYBOARD_ROW_COUNT,
): Float = keyboardKeyHeightPx(keySizePx) * (rowCount - 1)

internal fun keyboardBottomRowHeightPx(keySizePx: Float): Float = keyboardKeyHeightPx(keySizePx)

internal fun imeContentHeightPx(
    keySizePx: Float,
    bottomInsetPx: Float,
    rowCount: Int = KEYBOARD_ROW_COUNT,
): Float = keyboardSurfaceHeightPx(keySizePx, rowCount) + bottomInsetPx.coerceAtLeast(0f)
