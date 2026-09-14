package com.ninjakeys.app

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.language.Language
import com.ninjakeys.core.commands.GestureTrigger
import com.ninjakeys.core.commands.MultiFingerGestureDetector
import com.ninjakeys.core.layout.KeyPosition
import com.ninjakeys.core.layout.KeyboardLayout
import com.ninjakeys.core.recognition.SuggestionChip

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun KeyboardInputView(
    sessionId: Int,
    onSwipe: (GesturePath, KeyboardLayout) -> Unit,
    onTap: (String) -> Unit = {},
    onFlick: (String, FlickDirection) -> Unit = { _, _ -> },
    onPunctuationToSpace: (String) -> Unit = {},
    language: Language = Language.ENGLISH,
    onLanguageSwitch: () -> Unit = {},
    onCommand: (GestureTrigger) -> Unit = {},
    suggestionChips: List<SuggestionChip> = emptyList(),
    onSuggestionRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onSuggestionUndo: (chipIndex: Int) -> Unit = {},
    onSplitBegin: (pointerId: Int, point: GesturePoint, atMs: Long) -> Unit = { _, _, _ -> },
    onSplitMove: (pointerId: Int, point: GesturePoint) -> Unit = { _, _ -> },
    onSplitEnd: (pointerId: Int, path: GesturePath, layout: KeyboardLayout, atMs: Long) -> Unit = { _, _, _, _ -> },
    onSplitCancel: () -> Unit = {},
) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val keySizePx = widthPx / if (language == Language.HEBREW) 11f else 10f
        val keySize = with(density) { keySizePx.toDp() }
        val layout = remember(widthPx, language) { keyboardLayoutFor(keySizePx, language) }
        var points by remember(sessionId) { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var pointerId by remember(sessionId) { mutableStateOf(MotionEvent.INVALID_POINTER_ID) }
        var startKey by remember(sessionId) { mutableStateOf<String?>(null) }
        var multiFingerHandled by remember(sessionId) { mutableStateOf(false) }
        var multiStartX by remember(sessionId) { mutableStateOf(0f) }
        var multiStartY by remember(sessionId) { mutableStateOf(0f) }
        var startTime by remember(sessionId) { mutableStateOf(0L) }
        var splitMode by remember(sessionId) { mutableStateOf(false) }
        val splitPoints = remember(sessionId) { mutableMapOf<Int, MutableList<GesturePoint>>() }
        val multiFingerDetector = remember { MultiFingerGestureDetector(keySizePx / 2f) }

        Column(modifier = Modifier.fillMaxWidth()) {
            SuggestionStrip(
                chips = suggestionChips,
                rtl = language == Language.HEBREW,
                onRelease = onSuggestionRelease,
                onUndo = onSuggestionUndo,
            )
            Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keySize * 4)
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pointerId = event.getPointerId(0)
                            points = listOf(event.toGesturePoint(0))
                            startKey = keyAt(event.x, event.y, keySizePx, layout, language)
                            multiFingerHandled = false
                            splitMode = false
                            splitPoints.clear()
                            startTime = event.eventTime
                            true
                        }
                        MotionEvent.ACTION_POINTER_DOWN -> {
                            if (startKey != " ") {
                                splitMode = true
                                if (splitPoints.isEmpty()) {
                                    splitPoints[pointerId] = points.toMutableList()
                                    onSplitBegin(pointerId, points.first(), startTime)
                                }
                                val newIndex = event.actionIndex
                                val newId = event.getPointerId(newIndex)
                                val newPoint = event.toGesturePoint(newIndex)
                                splitPoints[newId] = mutableListOf(newPoint)
                                onSplitBegin(newId, newPoint, event.eventTime)
                            } else {
                                multiStartX = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
                                multiStartY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                            }
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (splitMode) {
                                for (index in 0 until event.pointerCount) {
                                    val id = event.getPointerId(index)
                                    val point = event.toGesturePoint(index)
                                    splitPoints[id]?.add(point)
                                    onSplitMove(id, point)
                                }
                            } else if (event.pointerCount >= 2 && !multiFingerHandled) {
                                val x = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
                                val y = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                                val trigger = multiFingerDetector.detect(multiStartX, multiStartY, x, y, event.pointerCount)
                                if (trigger != GestureTrigger.NONE) {
                                    multiFingerHandled = true
                                    onCommand(trigger)
                                }
                            }
                            val index = event.findPointerIndex(pointerId)
                            if (!splitMode && index >= 0) points += event.toGesturePoint(index)
                            true
                        }
                        MotionEvent.ACTION_POINTER_UP -> {
                            if (splitMode) {
                                val index = event.actionIndex
                                val id = event.getPointerId(index)
                                val path = splitPoints.remove(id)?.toList().orEmpty()
                                if (path.isNotEmpty()) onSplitEnd(id, GesturePath(path), layout, event.eventTime)
                                true
                            } else {
                                true
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (splitMode) {
                                val path = splitPoints.remove(pointerId)?.toList().orEmpty()
                                if (path.isNotEmpty()) onSplitEnd(pointerId, GesturePath(path), layout, event.eventTime)
                                splitMode = false
                                splitPoints.clear()
                                points = emptyList()
                                pointerId = MotionEvent.INVALID_POINTER_ID
                                startKey = null
                                true
                            } else {
                            val index = event.findPointerIndex(pointerId)
                            val completed = if (index >= 0) points + event.toGesturePoint(index) else points
                            if (multiFingerHandled) {
                                // The command was already emitted once when its threshold was crossed.
                            } else if (startKey == " " && event.eventTime - startTime >= 500L) {
                                onCommand(GestureTrigger.LONG_PRESS_SPACE)
                            } else if (startKey != null) {
                                val end = completed.lastOrNull()
                                when {
                                    completed.size < 2 -> onTap(startKey!!)
                                    startKey in punctuationKeys && end != null && end.y >= keySizePx * 3 ->
                                        onPunctuationToSpace(startKey!!)
                                    end != null && end.y < completed.first().y - keySizePx / 2 ->
                                        onFlick(startKey!!, FlickDirection.UP)
                                    else -> onSwipe(GesturePath(completed), layout)
                                }
                            }
                            points = emptyList()
                            pointerId = MotionEvent.INVALID_POINTER_ID
                            startKey = null
                            true
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            if (splitMode) onSplitCancel()
                            splitMode = false
                            splitPoints.clear()
                            points = emptyList()
                            pointerId = MotionEvent.INVALID_POINTER_ID
                            startKey = null
                            true
                        }
                        else -> true
                    }
                },
            ) {
            val rows = if (language == Language.ENGLISH) listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            else listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ")
            rows.forEachIndexed { index, row -> KeyboardRow(row, keySize * index / 2, keySize * index, keySize) }
            KeyboardBottomRow(keySize, language)
            }
        }
    }
}

@Composable
private fun BoxScope.KeyboardRow(letters: String, offset: Dp, y: Dp, keySize: Dp) {
    Row(modifier = Modifier.offset(x = offset, y = y)) {
        letters.forEach { letter ->
            Box(Modifier.size(keySize).border(1.dp, MaterialTheme.colorScheme.outline).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(letter.toString().uppercase())
                numberFor(letter)?.let { Text(it, modifier = Modifier.align(Alignment.TopEnd)) }
            }
        }
    }
}

@Composable
private fun BoxScope.KeyboardBottomRow(keySize: Dp, language: Language) {
    val punctuation = if (language == Language.ENGLISH) listOf("'", "?", ",", ".") else listOf("׳", "״")
    Row(modifier = Modifier.offset(y = keySize * 3)) {
        (listOf("🌐") + punctuation + listOf("space", "⌫")).forEach { label ->
            Box(Modifier.size(if (label == "space") keySize * 4 else keySize).border(1.dp, MaterialTheme.colorScheme.outline).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(label) }
        }
    }
}

private val punctuationKeys = setOf("'", "?", ",", ".", "׳", "״")

private fun numberFor(letter: Char): String? = "qwertyuiop".indexOf(letter).takeIf { it >= 0 }?.let { if (it == 9) "0" else (it + 1).toString() }

private fun keyAt(x: Float, y: Float, size: Float, layout: KeyboardLayout, language: Language): String? {
    if (y >= size * 3) {
        val index = (x / size).toInt()
        val punctuation = if (language == Language.ENGLISH) listOf("'", "?", ",", ".") else listOf("׳", "״")
        return when {
            index == 0 -> "🌐"
            index in 1..punctuation.size -> punctuation[index - 1]
            index in (punctuation.size + 1)..(punctuation.size + 4) -> " "
            index == punctuation.size + 5 -> "⌫"
            else -> null
        }
    }
    return layout.keys.minByOrNull { (x - it.x * size) * (x - it.x * size) + (y - it.y * size) * (y - it.y * size) }?.letter?.toString()
}

private fun keyboardLayoutFor(size: Float, language: Language): KeyboardLayout {
    val rows = if (language == Language.ENGLISH) listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    else listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ")
    val keys = buildList { rows.forEachIndexed { row, letters -> addRow(letters, row * 0.5f, row, size) } }
    return KeyboardLayout(keys)
}

private fun MutableList<KeyPosition>.addRow(letters: String, offset: Float, row: Int, size: Float) {
    letters.forEachIndexed { index, letter -> add(KeyPosition(letter, (index + 0.5f + offset) * size, (row + 0.5f) * size)) }
}

private fun MotionEvent.toGesturePoint(index: Int) = GesturePoint(getX(index), getY(index), eventTime)
