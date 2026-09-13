package com.ninjakeys.app

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyPosition
import com.ninjakeys.core.layout.KeyboardLayout

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardInputView(
    sessionId: Int,
    onSwipe: (GesturePath, KeyboardLayout) -> Unit,
    onTap: (String) -> Unit = {},
    onFlick: (String, FlickDirection) -> Unit = { _, _ -> },
    onPunctuationToSpace: (String) -> Unit = {},
) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val keySizePx = widthPx / 10f
        val keySize = with(density) { keySizePx.toDp() }
        val layout = remember(widthPx) { keyboardLayoutFor(keySizePx) }
        var gesturePoints by remember(sessionId) { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var activePointerId by remember(sessionId) { mutableStateOf(MotionEvent.INVALID_POINTER_ID) }
        var startKey by remember(sessionId) { mutableStateOf<String?>(null) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keySize * 4)
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointerId = event.getPointerId(0)
                            gesturePoints = listOf(event.toGesturePoint(0))
                            startKey = keyAt(event.x, event.y, keySizePx, layout)
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val index = event.findPointerIndex(activePointerId)
                            if (index >= 0) {
                                gesturePoints = gesturePoints + event.toGesturePoint(index)
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            val index = event.findPointerIndex(activePointerId)
                            val completed = if (index >= 0) {
                                gesturePoints + event.toGesturePoint(index)
                            } else {
                                gesturePoints
                            }
                            if (completed.size >= 2) {
                                val key = startKey
                                val end = completed.last()
                                when {
                                    key in punctuationKeys && end.y >= keySizePx * 3f ->
                                        onPunctuationToSpace(key!!)
                                    key != null && end.y < completed.first().y - keySizePx / 2f ->
                                        onFlick(key, FlickDirection.UP)
                                    else -> onSwipe(GesturePath(completed), layout)
                                }
                            } else if (startKey != null) {
                                onTap(startKey!!)
                            }
                            gesturePoints = emptyList()
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            startKey = null
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            gesturePoints = emptyList()
                            activePointerId = MotionEvent.INVALID_POINTER_ID
                            true
                        }

                        else -> true
                    }
                }
        ) {
            KeyboardRow("qwertyuiop", 0.dp, keySize)
            KeyboardRow("asdfghjkl", keySize / 2, keySize)
            KeyboardRow("zxcvbnm", keySize, keySize)
            KeyboardBottomRow(keySize)
        }
    }
}

@Composable
private fun BoxScope.KeyboardRow(letters: String, offset: Dp, keySize: Dp) {
    Row(modifier = Modifier.offset(x = offset)) {
        letters.forEach { letter ->
            Box(
                modifier = Modifier
                    .size(keySize)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter.uppercase())
                numberFor(letter)?.let { number ->
                    Text(number, modifier = Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun BoxScope.KeyboardBottomRow(keySize: Dp) {
    Row(modifier = Modifier.offset(y = keySize * 3)) {
        listOf("'", "?", ",", ".", "space", "⌫").forEach { label ->
            Box(
                modifier = Modifier
                    .size(if (label == "space") keySize * 4 else keySize)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(label) }
        }
    }
}

private val punctuationKeys = setOf("'", "?", ",", ".")

private fun numberFor(letter: Char): String? =
    "qwertyuiop".indexOf(letter).takeIf { it >= 0 }?.let { index ->
        if (index == 9) "0" else (index + 1).toString()
    }

private fun keyAt(x: Float, y: Float, keySizePx: Float, layout: KeyboardLayout): String? {
    if (y >= keySizePx * 3f) {
        val index = (x / keySizePx).toInt()
        return when {
            index == 0 -> "'"
            index == 1 -> "?"
            index == 2 -> ","
            index == 3 -> "."
            index in 4..7 -> " "
            index == 8 -> "⌫"
            else -> null
        }
    }
    return layout.keys.minByOrNull { key ->
        val dx = x - key.x * keySizePx / 1f
        val dy = y - key.y * keySizePx / 1f
        dx * dx + dy * dy
    }?.letter?.toString()
}

private fun keyboardLayoutFor(keySizePx: Float): KeyboardLayout {
    val keys = buildList {
        addRow("qwertyuiop", 0f, 0, keySizePx)
        addRow("asdfghjkl", 0.5f, 1, keySizePx)
        addRow("zxcvbnm", 1f, 2, keySizePx)
    }
    return KeyboardLayout(keys)
}

private fun MutableList<KeyPosition>.addRow(
    letters: String,
    xOffset: Float,
    row: Int,
    keySizePx: Float,
) {
    letters.forEachIndexed { index, letter ->
        add(KeyPosition(letter, (index + 0.5f + xOffset) * keySizePx, (row + 0.5f) * keySizePx))
    }
}

private fun MotionEvent.toGesturePoint(index: Int): GesturePoint =
    GesturePoint(getX(index), getY(index), eventTime)
