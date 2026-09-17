package com.iaido.app

import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iaido.core.commands.GestureTrigger
import com.iaido.core.commands.MultiFingerGestureDetector
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.language.Language
import com.iaido.core.layout.KeyPosition
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.SuggestionChip
import com.iaido.core.recognition.ReplacementOption
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val GLOBE_KEY = "\uD83C\uDF10"
private const val BACKSPACE_KEY = "\u232B"
private const val MAX_TRAIL_POINTS = 80
private const val KEYBOARD_ROOT_DESCRIPTION = "Iaido keyboard root"
private const val SWIPE_SURFACE_DESCRIPTION = "Iaido swipe surface"
private val punctuationKeys = setOf("'", "?", ",", ".", "\u00B3", "\u00B4")

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
    replacementOptions: List<ReplacementOption> = emptyList(),
    liveReplacementOptionIds: Set<String> = emptySet(),
    onSuggestionRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onSuggestionUndo: (chipIndex: Int) -> Unit = {},
    onReplacementPreview: (ReplacementOption) -> Unit = {},
    onReplacementRelease: (ReplacementOption) -> Unit = {},
    onReplacementCancel: () -> Unit = {},
    onBackspaceRepeat: () -> Unit = {},
    onBackspacePressStart: () -> Unit = {},
    onBackspaceSwipeStart: () -> Unit = {},
    onBackspaceSwipeDistance: (requestedCharacters: Int) -> Unit = {},
    onBackspaceSwipeEnd: () -> Unit = {},
    onBackspaceSwipeCancel: () -> Unit = {},
    onBackspaceUndo: () -> Unit = {},
    onBackspaceRedo: () -> Unit = {},
    onSplitBegin: (pointerId: Int, point: GesturePoint, atMs: Long) -> Unit = { _, _, _ -> },
    onSplitMove: (pointerId: Int, point: GesturePoint) -> Unit = { _, _ -> },
    onSplitEnd: (pointerId: Int, path: GesturePath, layout: KeyboardLayout, atMs: Long) -> Unit = { _, _, _, _ -> },
    onSplitCancel: () -> Unit = {},
    splitPreview: String? = null,
    onSplitPreview: (String) -> Unit = {},
    manualEditCandidate: ManualEditCandidate? = null,
    onConfirmManualEdit: () -> Unit = {},
    onDismissManualEdit: () -> Unit = {},
) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val columnCount = if (language == Language.HEBREW) 11 else KEYBOARD_COLUMN_COUNT
        val keySizePx = widthPx / columnCount
        val keySize = with(density) { keySizePx.toDp() }
        val bottomInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val surfaceHeightPx = keyboardSurfaceHeightPx(keySizePx)
        val contentHeightPx = imeContentHeightPx(keySizePx, bottomInsetPx)
        val bottomInset = with(density) {
            (contentHeightPx - surfaceHeightPx).toDp() + BOTTOM_KEYBOARD_CLEARANCE_DP
        }
        val backspaceSwipeStepPx = with(density) { BACKSPACE_SWIPE_STEP_DP.dp.toPx() }
        val backspaceGestureThresholdPx = with(density) { BACKSPACE_GESTURE_THRESHOLD_DP.dp.toPx() }
        val layout = remember(widthPx, language) { keyboardLayoutFor(keySizePx, language) }
        var points by remember(sessionId) { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var trailPoints by remember(sessionId) { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var pointerId by remember(sessionId) { mutableStateOf(MotionEvent.INVALID_POINTER_ID) }
        var startKey by remember(sessionId) { mutableStateOf<String?>(null) }
        var multiFingerHandled by remember(sessionId) { mutableStateOf(false) }
        var multiStartX by remember(sessionId) { mutableStateOf(0f) }
        var multiStartY by remember(sessionId) { mutableStateOf(0f) }
        var startTime by remember(sessionId) { mutableStateOf(0L) }
        var splitMode by remember(sessionId) { mutableStateOf(false) }
        var backspaceMode by remember(sessionId) { mutableStateOf(BackspaceMode.NONE) }
        var backspaceStartX by remember(sessionId) { mutableFloatStateOf(0f) }
        var backspaceStartY by remember(sessionId) { mutableFloatStateOf(0f) }
        var backspaceDx by remember(sessionId) { mutableFloatStateOf(0f) }
        var backspaceDy by remember(sessionId) { mutableFloatStateOf(0f) }
        val splitPoints = remember(sessionId) { mutableMapOf<Int, MutableList<GesturePoint>>() }
        val splitEnded = remember(sessionId) { mutableSetOf<Int>() }
        val multiFingerDetector = remember { MultiFingerGestureDetector(keySizePx / 2f) }

        LaunchedEffect(trailPoints) {
            if (trailPoints.isNotEmpty()) {
                delay(2000L)
                trailPoints = emptyList()
            }
        }

        LaunchedEffect(backspaceMode) {
            if (backspaceMode != BackspaceMode.PRESS) return@LaunchedEffect
            delay(BACKSPACE_HOLD_DELAY_MS)
            if (backspaceMode == BackspaceMode.PRESS) backspaceMode = BackspaceMode.HOLD
        }

        LaunchedEffect(backspaceMode) {
            if (backspaceMode != BackspaceMode.HOLD) return@LaunchedEffect
            var repeats = 0
            while (backspaceMode == BackspaceMode.HOLD) {
                onBackspaceRepeat()
                repeats += 1
                delay(backspaceRepeatIntervalMs(repeats))
            }
        }

        fun resetBackspaceGestureState() {
            backspaceMode = BackspaceMode.NONE
            backspaceDx = 0f
            backspaceDy = 0f
        }

        fun cancelBackspaceGesture() {
            if (backspaceMode != BackspaceMode.NONE) onBackspaceSwipeCancel()
            resetBackspaceGestureState()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = KEYBOARD_ROOT_DESCRIPTION }
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                modifier = Modifier
                    .size(1.dp)
                    .semantics { contentDescription = "Iaido language ${language.name}" },
            )
            SuggestionStrip(
                chips = suggestionChips,
                rtl = language == Language.HEBREW,
                replacementOptions = replacementOptions,
                liveReplacementOptionIds = liveReplacementOptionIds,
                onRelease = onSuggestionRelease,
                onUndo = onSuggestionUndo,
                onReplacementPreview = onReplacementPreview,
                onReplacementRelease = onReplacementRelease,
                onReplacementCancel = onReplacementCancel,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
            ) {
                splitPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                    Text(
                        text = preview,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            manualEditCandidate?.let { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Learn ${candidate.original} → ${candidate.replacement}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    TextButton(onClick = onConfirmManualEdit) { Text("Learn") }
                    TextButton(onClick = onDismissManualEdit) { Text("Dismiss") }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(keySize * KEYBOARD_ROW_COUNT)
                    .semantics { contentDescription = SWIPE_SURFACE_DESCRIPTION }
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                pointerId = event.getPointerId(0)
                                val point = event.toGesturePoint(0)
                                points = listOf(point)
                                trailPoints = listOf(point)
                                startKey = keyAt(event.x, event.y, keySizePx, layout, language)
                                multiFingerHandled = false
                                splitMode = false
                                splitPoints.clear()
                                splitEnded.clear()
                                startTime = event.eventTime
                                backspaceMode = if (startKey == BACKSPACE_KEY) BackspaceMode.PRESS else BackspaceMode.NONE
                                backspaceStartX = event.x
                                backspaceStartY = event.y
                                backspaceDx = 0f
                                backspaceDy = 0f
                                if (startKey == BACKSPACE_KEY) onBackspacePressStart()
                                true
                            }
                            MotionEvent.ACTION_POINTER_DOWN -> {
                                if (startKey != " ") {
                                    splitMode = true
                                    if (splitPoints.isEmpty()) {
                                        splitPoints[pointerId] = points.toMutableList()
                                        points.firstOrNull()?.let { onSplitBegin(pointerId, it, startTime) }
                                    }
                                    val newIndex = event.actionIndex
                                    val newId = event.getPointerId(newIndex)
                                    val newPoint = event.toGesturePoint(newIndex)
                                    splitPoints[newId] = mutableListOf(newPoint)
                                    splitEnded.remove(newId)
                                    onSplitBegin(newId, newPoint, event.eventTime)
                                    onSplitPreview(splitGesturePreview(splitPoints.values.map { it.toList() }, layout))
                                } else {
                                    multiStartX = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
                                    multiStartY = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                                }
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val primaryIndex = event.findPointerIndex(pointerId)
                                if (primaryIndex >= 0) {
                                    val point = event.toGesturePoint(primaryIndex)
                                    trailPoints = (trailPoints + point).takeLast(MAX_TRAIL_POINTS)
                                }
                                if (!splitMode && startKey == BACKSPACE_KEY && primaryIndex >= 0) {
                                    backspaceDx = event.getX(primaryIndex) - backspaceStartX
                                    backspaceDy = event.getY(primaryIndex) - backspaceStartY
                                    val action = classifyBackspaceGesture(
                                        backspaceDx,
                                        backspaceDy,
                                        backspaceGestureThresholdPx,
                                    )
                                    when (backspaceMode) {
                                        BackspaceMode.PRESS,
                                        BackspaceMode.HOLD -> when (action) {
                                            BackspaceGestureAction.DELETE -> {
                                                backspaceMode = BackspaceMode.DELETE
                                                onBackspaceSwipeStart()
                                                onBackspaceSwipeDistance(
                                                    (-backspaceDx / backspaceSwipeStepPx).roundToInt(),
                                                )
                                            }
                                            BackspaceGestureAction.UNDO -> {
                                                backspaceMode = BackspaceMode.UNDO
                                                onBackspaceUndo()
                                            }
                                            BackspaceGestureAction.REDO -> {
                                                backspaceMode = BackspaceMode.REDO
                                                onBackspaceRedo()
                                            }
                                            else -> Unit
                                        }
                                        BackspaceMode.DELETE -> onBackspaceSwipeDistance(
                                            (-backspaceDx / backspaceSwipeStepPx).roundToInt(),
                                        )
                                        else -> Unit
                                    }
                                }
                                if (splitMode) {
                                    for (index in 0 until event.pointerCount) {
                                        val id = event.getPointerId(index)
                                        val point = event.toGesturePoint(index)
                                        splitPoints[id]?.add(point)
                                        onSplitMove(id, point)
                                    }
                                    onSplitPreview(splitGesturePreview(splitPoints.values.map { it.toList() }, layout))
                                } else if (event.pointerCount >= 2 && !multiFingerHandled) {
                                    val x = (0 until event.pointerCount).map { event.getX(it) }.average().toFloat()
                                    val y = (0 until event.pointerCount).map { event.getY(it) }.average().toFloat()
                                    val trigger = multiFingerDetector.detect(multiStartX, multiStartY, x, y, event.pointerCount)
                                    if (trigger != GestureTrigger.NONE) {
                                        multiFingerHandled = true
                                        onCommand(trigger)
                                    }
                                }
                                if (!splitMode && primaryIndex >= 0) points += event.toGesturePoint(primaryIndex)
                                true
                            }
                            MotionEvent.ACTION_POINTER_UP -> {
                                if (splitMode) {
                                    val index = event.actionIndex
                                    val id = event.getPointerId(index)
                                    if (splitEnded.add(id)) {
                                        val path = splitPoints[id]?.toList().orEmpty()
                                        if (path.isNotEmpty()) onSplitEnd(id, GesturePath(path), layout, event.eventTime)
                                    }
                                    onSplitPreview(splitGesturePreview(splitPoints.values.map { it.toList() }, layout))
                                    true
                                } else {
                                    true
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (splitMode) {
                                    cancelBackspaceGesture()
                                    if (splitEnded.add(pointerId)) {
                                        val path = splitPoints[pointerId]?.toList().orEmpty()
                                        if (path.isNotEmpty()) onSplitEnd(pointerId, GesturePath(path), layout, event.eventTime)
                                    }
                                    onSplitPreview(splitGesturePreview(splitPoints.values.map { it.toList() }, layout))
                                    splitMode = false
                                    splitPoints.clear()
                                    splitEnded.clear()
                                    points = emptyList()
                                    pointerId = MotionEvent.INVALID_POINTER_ID
                                    startKey = null
                                    true
                                } else if (startKey == BACKSPACE_KEY) {
                                    when (backspaceMode) {
                                        BackspaceMode.PRESS -> {
                                            onTap(BACKSPACE_KEY)
                                            cancelBackspaceGesture()
                                        }
                                        BackspaceMode.DELETE -> {
                                            val releaseIndex = event.findPointerIndex(pointerId)
                                            if (releaseIndex >= 0) {
                                                val releaseDx = event.getX(releaseIndex) - backspaceStartX
                                                onBackspaceSwipeDistance(
                                                    (-releaseDx / backspaceSwipeStepPx).roundToInt(),
                                                )
                                            }
                                            onBackspaceSwipeEnd()
                                            resetBackspaceGestureState()
                                        }
                                        else -> cancelBackspaceGesture()
                                    }
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
                                            isTapGesture(completed, keySizePx) -> onTap(startKey!!)
                                            startKey in punctuationKeys && end != null && end.y >= keySizePx * 3 ->
                                                onPunctuationToSpace(startKey!!)
                                            isUpwardFlickGesture(completed, keySizePx) ->
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
                                cancelBackspaceGesture()
                                splitMode = false
                                splitPoints.clear()
                                splitEnded.clear()
                                points = emptyList()
                                pointerId = MotionEvent.INVALID_POINTER_ID
                                startKey = null
                                true
                            }
                            else -> true
                        }
                    },
            ) {
                val rows = if (language == Language.ENGLISH) {
                    listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
                } else {
                    listOf("\u05e7\u05e8\u05d0\u05d8\u05d5\u05df\u05dd\u05e4", "\u05e9\u05d3\u05d2\u05db\u05e2\u05d9\u05d7\u05dc\u05da\u05e3", "\u05d6\u05e1\u05d1\u05d4\u05e0\u05de\u05e6\u05ea\u05e5")
                }
                rows.forEachIndexed { index, row ->
                    KeyboardRow(
                        letters = row,
                        offset = keySize * keyboardRowOffsetUnits(row.length, columnCount),
                        y = keySize * index,
                        keySize = keySize,
                        pressedKey = startKey,
                    )
                }
                KeyboardBottomRow(keySize, language, pressedKey = startKey)
                val trailColor = MaterialTheme.colorScheme.primary
                Canvas(modifier = Modifier.fillMaxWidth().height(keySize * KEYBOARD_ROW_COUNT)) {
                    drawSwipeTrail(trailPoints, trailColor)
                }
            }
            Spacer(Modifier.height(bottomInset))
        }
    }
}

@Composable
private fun KeyboardRow(
    letters: String,
    offset: Dp,
    y: Dp,
    keySize: Dp,
    pressedKey: String?,
) {
    val gap = 3.dp
    Row(
        modifier = Modifier.offset(x = offset + gap / 2, y = y),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        letters.forEach { letter ->
            KeyboardKey(
                label = letter.toString().uppercase(),
                width = keySize - gap,
                height = keySize - gap,
                pressed = pressedKey == letter.toString(),
                number = numberFor(letter),
                testKey = letter.toString(),
            )
        }
    }
}

@Composable
private fun KeyboardBottomRow(keySize: Dp, language: Language, pressedKey: String?) {
    val gap = 3.dp
    val punctuation = if (language == Language.ENGLISH) listOf("'", "?", ",", ".") else listOf("\u00B3", "\u00B4")
    val keys = listOf(GLOBE_KEY to 1f) + punctuation.map { it to 1f } + listOf("space" to 4f, BACKSPACE_KEY to 1f)
    Row(
        modifier = Modifier.offset(y = keySize * 3),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        keys.forEach { (label, widthWeight) ->
            KeyboardKey(
                label = if (label == "space") language.localeTag.replace('-', ' ').uppercase() else label,
                modifier = Modifier.weight(widthWeight),
                height = keySize,
                pressed = pressedKey == label || (label == "space" && pressedKey == " "),
                testKey = when (label) {
                    GLOBE_KEY -> "globe"
                    BACKSPACE_KEY -> "backspace"
                    else -> label
                },
            )
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp,
    pressed: Boolean,
    number: String? = null,
    testKey: String? = null,
) {
    val keyModifier = if (width != null) modifier.size(width = width, height = height) else modifier
    Box(
        modifier = keyModifier
            .semantics {
                testKey?.let { contentDescription = "Iaido key $it" }
            }
            .background(
                color = if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (pressed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        number?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.TopEnd),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun DrawScope.drawSwipeTrail(points: List<GesturePoint>, color: androidx.compose.ui.graphics.Color) {
    if (points.isEmpty()) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = path,
        color = color.copy(alpha = 0.78f),
        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    points.takeLast(4).forEach { point ->
        drawCircle(color = color.copy(alpha = 0.9f), radius = 4.dp.toPx(), center = Offset(point.x, point.y))
    }
}

private fun numberFor(letter: Char): String? = "qwertyuiop".indexOf(letter).takeIf { it >= 0 }?.let { if (it == 9) "0" else (it + 1).toString() }

internal fun keyAt(x: Float, y: Float, size: Float, layout: KeyboardLayout, language: Language): String? {
    if (y >= size * 3) {
        val index = (x / size).toInt()
        val punctuation = if (language == Language.ENGLISH) listOf("'", "?", ",", ".") else listOf("\u00B3", "\u00B4")
        return when {
            index == 0 -> GLOBE_KEY
            index in 1..punctuation.size -> punctuation[index - 1]
            index in (punctuation.size + 1)..(punctuation.size + 4) -> " "
            index == punctuation.size + 5 -> BACKSPACE_KEY
            else -> null
        }
    }
    return layout.keys.minByOrNull { (x - it.x) * (x - it.x) + (y - it.y) * (y - it.y) }?.letter?.toString()
}

private fun keyboardLayoutFor(size: Float, language: Language): KeyboardLayout {
    val rows = if (language == Language.ENGLISH) {
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    } else {
        listOf("\u05e7\u05e8\u05d0\u05d8\u05d5\u05df\u05dd\u05e4", "\u05e9\u05d3\u05d2\u05db\u05e2\u05d9\u05d7\u05dc\u05da\u05e3", "\u05d6\u05e1\u05d1\u05d4\u05e0\u05de\u05e6\u05ea\u05e5")
    }
    val columnCount = if (language == Language.HEBREW) 11 else KEYBOARD_COLUMN_COUNT
    val keys = buildList {
        rows.forEachIndexed { row, letters ->
            addRow(letters, keyboardRowOffsetUnits(letters.length, columnCount), row, size)
        }
    }
    return KeyboardLayout(keys)
}

private fun MutableList<KeyPosition>.addRow(letters: String, offset: Float, row: Int, size: Float) {
    letters.forEachIndexed { index, letter -> add(KeyPosition(letter, (index + 0.5f + offset) * size, (row + 0.5f) * size)) }
}

private fun MotionEvent.toGesturePoint(index: Int) = GesturePoint(getX(index), getY(index), eventTime)

private enum class BackspaceMode {
    NONE,
    PRESS,
    HOLD,
    DELETE,
    UNDO,
    REDO,
}

private const val BACKSPACE_HOLD_DELAY_MS = 350L
private const val BACKSPACE_SWIPE_STEP_DP = 14f
private const val BACKSPACE_GESTURE_THRESHOLD_DP = 18f
private val BOTTOM_KEYBOARD_CLEARANCE_DP = 12.dp
