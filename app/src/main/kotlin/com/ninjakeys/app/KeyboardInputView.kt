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
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyPosition
import com.ninjakeys.core.layout.KeyboardLayout

@Composable
fun KeyboardInputView(onSwipe: (GesturePath, KeyboardLayout) -> Unit) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val keySizePx = widthPx / 10f
        val keySize = with(density) { keySizePx.toDp() }
        val layout = remember(widthPx) { keyboardLayoutFor(keySizePx) }
        var gesturePoints by remember { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var activePointerId by remember { mutableStateOf(MotionEvent.INVALID_POINTER_ID) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keySize * 3)
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activePointerId = event.getPointerId(0)
                            gesturePoints = listOf(event.toGesturePoint(0))
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
                                onSwipe(GesturePath(completed), layout)
                            }
                            gesturePoints = emptyList()
                            activePointerId = MotionEvent.INVALID_POINTER_ID
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
            }
        }
    }
}

private fun keyboardLayoutFor(keySizePx: Float): KeyboardLayout {
    val keys = buildList {
        addRow("qwertyuiop", 0f, 0)
        addRow("asdfghjkl", 0.5f, 1)
        addRow("zxcvbnm", 1f, 2)
    }
    return KeyboardLayout(keys)
}

private fun MutableList<KeyPosition>.addRow(letters: String, xOffset: Float, row: Int) {
    letters.forEachIndexed { index, letter ->
        add(KeyPosition(letter, (index + 0.5f + xOffset) * keySizePx, (row + 0.5f) * keySizePx))
    }
}

private fun MotionEvent.toGesturePoint(index: Int): GesturePoint =
    GesturePoint(getX(index), getY(index), eventTime)
