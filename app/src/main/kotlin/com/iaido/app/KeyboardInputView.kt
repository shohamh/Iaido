package com.iaido.app

import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
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

private const val GLOBE_KEY = "\uD83C\uDF10"
internal const val SETTINGS_KEY = "\u2699"
internal const val BACKSPACE_KEY = "\u232B"
internal const val SHIFT_KEY = "shift"
internal const val ENTER_KEY = "enter"
private const val MAX_TRAIL_POINTS = 80
private const val KEYBOARD_ROOT_DESCRIPTION = "Iaido keyboard root"
private const val SWIPE_SURFACE_DESCRIPTION = "Iaido swipe surface"
private val punctuationKeys = setOf(",", ".")

private enum class KeyboardShiftState {
    LOWERCASE,
    ONE_SHOT,
    CAPS_LOCK;

    fun next(): KeyboardShiftState = when (this) {
        LOWERCASE -> ONE_SHOT
        ONE_SHOT -> CAPS_LOCK
        CAPS_LOCK -> LOWERCASE
    }
}

private enum class KeyboardKeyIcon {
    BACKSPACE,
    ENTER,
    GLOBE,
    SHIFT,
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun KeyboardInputView(
    sessionId: Int,
    onSwipe: (GesturePath, KeyboardLayout) -> Unit,
    onTap: (String) -> Unit = {},
    onFlick: (String, FlickDirection) -> Unit = { _, _ -> },
    onPunctuationToSpace: (String) -> Unit = {},
    language: Language = Language.ENGLISH,
    showNumberRow: Boolean = SettingsDefaults.SHOW_NUMBER_ROW,
    onLanguageSwitch: () -> Unit = {},
    onCommand: (GestureTrigger) -> Unit = {},
    suggestionChips: List<SuggestionChip> = emptyList(),
    focusedChipId: Int? = null,
    replacementOptions: List<ReplacementOption> = emptyList(),
    sentenceStripState: SentenceStripState = SentenceTextModel.emptyState(language),
    sentenceStripActions: SentenceStripActions? = null,
    liveReplacementOptionIds: Set<String> = emptySet(),
    showCandidateScores: Boolean = false,
    onSuggestionRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onSuggestionUndo: (chipIndex: Int) -> Unit = {},
    onReplacementPreview: (ReplacementOption) -> Unit = {},
    onReplacementRelease: (ReplacementOption) -> Unit = {},
    onReplacementCancel: () -> Unit = {},
    onBackspaceRepeat: (deleteWord: Boolean) -> Unit = {},
    onBackspacePressStart: () -> Unit = {},
    onBackspaceSwipeStart: () -> Unit = {},
    onBackspaceSwipeDistance: (requestedCharacters: Int) -> Unit = {},
    onBackspaceSwipeEnd: () -> Unit = {},
    onBackspaceSwipeCancel: () -> Unit = {},
    onBackspaceUndo: () -> Unit = {},
    onBackspaceRedo: () -> Unit = {},
    onSplitGestureStart: () -> Unit = {},
    onSplitBegin: (pointerId: Int, point: GesturePoint, atMs: Long) -> Unit = { _, _, _ -> },
    onSplitMove: (pointerId: Int, point: GesturePoint) -> Unit = { _, _ -> },
    onSplitEnd: (pointerId: Int, path: GesturePath, layout: KeyboardLayout, atMs: Long) -> Unit = { _, _, _, _ -> },
    onSplitCancel: () -> Unit = {},
    splitPreview: String? = null,
    onSplitPreview: (String) -> Unit = {},
    manualEditCandidate: ManualEditCandidate? = null,
    onConfirmManualEdit: () -> Unit = {},
    onDismissManualEdit: () -> Unit = {},
    researchTraceRecorder: ResearchTraceRecorder? = null,
    onResearchTraceCaptured: (ResearchTrace) -> Unit = {},
) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val keyboardSideInsetPx = with(density) { KEYBOARD_SIDE_INSET_DP.dp.toPx() }
        val keyboardWidthPx = (widthPx - keyboardSideInsetPx * 2f).coerceAtLeast(0f)
        val columnCount = KEYBOARD_LETTER_ROW_COLUMN_COUNT
        val rowCount = keyboardRowCount(showNumberRow)
        val keySizePx = keyboardWidthPx / columnCount
        val keySize = with(density) { keySizePx.toDp() }
        val keyHeightPx = keyboardKeyHeightPx(keySizePx)
        val keyHeight = with(density) { keyHeightPx.toDp() }
        val bottomInsetPx = WindowInsets.navigationBars.getBottom(density).toFloat()
        val surfaceHeightPx = keyboardSurfaceHeightPx(keySizePx, rowCount)
        val contentHeightPx = imeContentHeightPx(keySizePx, bottomInsetPx, rowCount)
        val keyboardSurfaceHeight = with(density) { surfaceHeightPx.toDp() }
        val bottomInset = with(density) {
            (contentHeightPx - surfaceHeightPx).toDp() + BOTTOM_KEYBOARD_CLEARANCE_DP
        }
        val backspaceSwipeStepPx = with(density) { BACKSPACE_SWIPE_STEP_DP.dp.toPx() }
        val backspaceGestureThresholdPx = with(density) { BACKSPACE_GESTURE_THRESHOLD_DP.dp.toPx() }
        val layout = remember(keyboardWidthPx, language, showNumberRow) {
            keyboardLayoutFor(keySizePx, language, showNumberRow)
        }
        val researchLayoutId = if (language == Language.HEBREW) "hebrew" else "qwerty"
        fun finishResearchTrace(classification: String) {
            researchTraceRecorder
                ?.finish(classification, language, researchLayoutId)
                ?.let(onResearchTraceCaptured)
        }
        var points by remember(sessionId) { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var trailPoints by remember(sessionId) { mutableStateOf<List<GesturePoint>>(emptyList()) }
        var trailFading by remember(sessionId) { mutableStateOf(false) }
        var pointerId by remember(sessionId) { mutableStateOf(MotionEvent.INVALID_POINTER_ID) }
        var startKey by remember(sessionId) { mutableStateOf<String?>(null) }
        var shiftState by remember(sessionId) { mutableStateOf(KeyboardShiftState.LOWERCASE) }
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
        val visibleShiftState = if (language == Language.ENGLISH) shiftState else KeyboardShiftState.LOWERCASE

        LaunchedEffect(language) {
            if (language != Language.ENGLISH) shiftState = KeyboardShiftState.LOWERCASE
        }

        fun dispatchTap(key: String) {
            if (key == SHIFT_KEY) {
                if (language == Language.ENGLISH) shiftState = shiftState.next()
                return
            }
            val isEnglishLetter = language == Language.ENGLISH && key.singleOrNull()?.isLetter() == true
            onTap(if (isEnglishLetter && shiftState != KeyboardShiftState.LOWERCASE) key.uppercase() else key)
            if (isEnglishLetter && shiftState == KeyboardShiftState.ONE_SHOT) {
                shiftState = KeyboardShiftState.LOWERCASE
            }
        }

        val trailOpacity by animateFloatAsState(
            targetValue = if (trailFading) 0f else 1f,
            animationSpec = tween(durationMillis = 210),
            label = "swipeTrailFade",
            finishedListener = { value ->
                if (value == 0f && trailFading) {
                    trailPoints = emptyList()
                    trailFading = false
                }
            },
        )

        LaunchedEffect(backspaceMode) {
            if (backspaceMode != BackspaceMode.PRESS) return@LaunchedEffect
            delay(BACKSPACE_HOLD_DELAY_MS)
            if (backspaceMode == BackspaceMode.PRESS) backspaceMode = BackspaceMode.HOLD
        }

        LaunchedEffect(backspaceMode) {
            if (backspaceMode != BackspaceMode.HOLD) return@LaunchedEffect
            var repeats = 0
            while (backspaceMode == BackspaceMode.HOLD) {
                repeats += 1
                onBackspaceRepeat(backspaceRepeatDeletesWord(repeats))
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

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = KeyboardPalette.Page,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = KEYBOARD_ROOT_DESCRIPTION },
            ) {
            Text(
                text = language.name,
                modifier = Modifier
                    .size(1.dp)
                    .semantics { contentDescription = "Iaido language ${language.name}" },
                color = androidx.compose.ui.graphics.Color.Transparent,
            )
            SentenceStrip(
                state = sentenceStripState,
                rtl = language == Language.HEBREW,
                actions = sentenceStripActions,
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
                        .height(keyboardSurfaceHeight)
                    .padding(horizontal = KEYBOARD_SIDE_INSET_DP.dp)
                    .background(KeyboardPalette.Deck)
                    .semantics { contentDescription = SWIPE_SURFACE_DESCRIPTION }
                    .pointerInteropFilter { event ->
                        // Consent is checked before building a TouchFrame: converting every raw
                        // pointer event allocates a frame plus one object per pointer, and research
                        // capture is off by default, so the default path must not allocate at all.
                        val traceRecorder = researchTraceRecorder
                        if (traceRecorder != null && traceRecorder.isEnabled) {
                            traceRecorder.consume(event.toTouchFrame(keyboardWidthPx, surfaceHeightPx))
                        }
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                trailFading = false
                                onSplitGestureStart()
                                pointerId = event.getPointerId(0)
                                val point = event.toGesturePoint(0)
                                points = listOf(point)
                                trailPoints = listOf(point)
                                startKey = keyAt(event.x, event.y, keySizePx, layout, language, showNumberRow)
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
                                        isRtl = language == Language.HEBREW,
                                    )
                                    when (backspaceMode) {
                                        BackspaceMode.PRESS,
                                        BackspaceMode.HOLD -> when (action) {
                                            BackspaceGestureAction.DELETE -> {
                                                backspaceMode = BackspaceMode.DELETE
                                                onBackspaceSwipeStart()
                                                onBackspaceSwipeDistance(
                                                    backspaceSwipeRequestedCharacters(
                                                        backspaceDx,
                                                        backspaceSwipeStepPx,
                                                        isRtl = language == Language.HEBREW,
                                                    ),
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
                                            backspaceSwipeRequestedCharacters(
                                                backspaceDx,
                                                backspaceSwipeStepPx,
                                                isRtl = language == Language.HEBREW,
                                            ),
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
                                trailFading = trailPoints.isNotEmpty()
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
                                    finishResearchTrace(ResearchTraceClassification.SPLIT)
                                    true
                                } else if (startKey == BACKSPACE_KEY) {
                                    when (backspaceMode) {
                                        BackspaceMode.PRESS -> {
                                            dispatchTap(BACKSPACE_KEY)
                                            cancelBackspaceGesture()
                                        }
                                        BackspaceMode.DELETE -> {
                                            val releaseIndex = event.findPointerIndex(pointerId)
                                            if (releaseIndex >= 0) {
                                                val releaseDx = event.getX(releaseIndex) - backspaceStartX
                                                onBackspaceSwipeDistance(
                                                    backspaceSwipeRequestedCharacters(
                                                        releaseDx,
                                                        backspaceSwipeStepPx,
                                                        isRtl = language == Language.HEBREW,
                                                    ),
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
                                    finishResearchTrace(ResearchTraceClassification.BACKSPACE)
                                    true
                                } else {
                                    val index = event.findPointerIndex(pointerId)
                                    val completed = if (index >= 0) points + event.toGesturePoint(index) else points
                                    if (multiFingerHandled) {
                                        // The command was already emitted once when its threshold was crossed.
                                        finishResearchTrace(ResearchTraceClassification.COMMAND)
                                    } else if (startKey == " " && event.eventTime - startTime >= 500L) {
                                        onCommand(GestureTrigger.LONG_PRESS_SPACE)
                                        finishResearchTrace(ResearchTraceClassification.COMMAND)
                                    } else if (startKey?.singleOrNull()?.isDigit() == true) {
                                        if (isTapGesture(completed, keySizePx)) {
                                            dispatchTap(startKey!!)
                                            finishResearchTrace(ResearchTraceClassification.TAP)
                                        } else {
                                            finishResearchTrace(ResearchTraceClassification.FAILED)
                                        }
                                    } else if (startKey != null) {
                                        val end = completed.lastOrNull()
                                        when {
                                            isTapGesture(completed, keySizePx) -> {
                                                dispatchTap(startKey!!)
                                                finishResearchTrace(ResearchTraceClassification.TAP)
                                            }
                                            startKey in punctuationKeys && end != null &&
                                                end.y >= keyboardBottomRowTopPx(keySizePx, rowCount) -> {
                                                onPunctuationToSpace(startKey!!)
                                                finishResearchTrace(ResearchTraceClassification.PUNCTUATION)
                                            }
                                            isUpwardFlickGesture(completed, keySizePx) -> {
                                                onFlick(startKey!!, FlickDirection.UP)
                                                finishResearchTrace(ResearchTraceClassification.FLICK)
                                            }
                                            else -> {
                                                onSwipe(GesturePath(completed), layout)
                                                finishResearchTrace(ResearchTraceClassification.SWIPE)
                                            }
                                        }
                                    } else {
                                        finishResearchTrace(ResearchTraceClassification.FAILED)
                                    }
                                    points = emptyList()
                                    pointerId = MotionEvent.INVALID_POINTER_ID
                                    startKey = null
                                    true
                                }
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                trailFading = trailPoints.isNotEmpty()
                                if (splitMode) onSplitCancel()
                                cancelBackspaceGesture()
                                splitMode = false
                                splitPoints.clear()
                                splitEnded.clear()
                                points = emptyList()
                                pointerId = MotionEvent.INVALID_POINTER_ID
                                startKey = null
                                finishResearchTrace(ResearchTraceClassification.CANCELLED)
                                true
                            }
                            else -> true
                        }
                    },
            ) {
                val rows = keyboardLetterRowsFor(language)
                if (showNumberRow) KeyboardNumberRow(keyHeight, pressedKey = startKey)
                rows.take(2).forEachIndexed { index, row ->
                    KeyboardRow(
                        letters = row,
                        offset = keySize * keyboardRowOffsetUnits(row.length, columnCount),
                        y = keyHeight * (index + if (showNumberRow) 1 else 0),
                        keySize = keyHeight,
                        pressedKey = startKey,
                        shiftState = visibleShiftState,
                    )
                }
                KeyboardModifierRow(
                    letters = rows[2],
                    y = keyHeight * (rowCount - 2),
                    keySize = keyHeight,
                    pressedKey = startKey,
                    shiftState = visibleShiftState,
                    showShift = language == Language.ENGLISH,
                )
                KeyboardBottomRow(keyHeight, rowCount, language, pressedKey = startKey)
                Canvas(modifier = Modifier.fillMaxWidth().height(keyboardSurfaceHeight)) {
                    drawSwipeTrail(trailPoints, KeyboardPalette.Accent, trailOpacity)
                }
            }
            Spacer(Modifier.height(bottomInset))
            }
            }
        }
    }
}

@Composable
private fun KeyboardNumberRow(keyHeight: Dp, pressedKey: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        "1234567890".forEach { digit ->
            KeyboardKey(
                label = digit.toString(),
                modifier = Modifier.weight(1f),
                height = keyHeight,
                pressed = pressedKey == digit.toString(),
                testKey = "digit-$digit",
            )
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
    shiftState: KeyboardShiftState,
) {
    val gap = 3.dp
    Row(
        modifier = Modifier.offset(x = offset + gap / 2, y = y),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        letters.forEach { letter ->
            KeyboardKey(
                label = letter.toString().let { key ->
                    if (shiftState == KeyboardShiftState.LOWERCASE) key.lowercase() else key.uppercase()
                },
                width = keySize - gap,
                height = keySize,
                pressed = pressedKey == letter.toString(),
                testKey = letter.toString(),
            )
        }
    }
}

@Composable
private fun KeyboardModifierRow(
    letters: String,
    y: Dp,
    keySize: Dp,
    pressedKey: String?,
    shiftState: KeyboardShiftState,
    showShift: Boolean,
) {
    val gap = 3.dp
    val modifierWeight = modifierKeyWeight(letters.length)
    val letterWeight = modifierLetterKeyWeight(letters.length)
    Row(
        modifier = Modifier.offset(y = y),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        if (showShift) {
            KeyboardKey(
                label = "",
                modifier = Modifier.weight(modifierWeight),
                height = keySize,
                pressed = shiftState != KeyboardShiftState.LOWERCASE || pressedKey == SHIFT_KEY,
                testKey = SHIFT_KEY,
                icon = KeyboardKeyIcon.SHIFT,
                shiftState = shiftState,
            )
        } else {
            Spacer(Modifier.weight(modifierWeight).height(keySize))
        }
        letters.forEach { letter ->
            KeyboardKey(
                label = letter.toString().let { key ->
                    if (shiftState == KeyboardShiftState.LOWERCASE) key.lowercase() else key.uppercase()
                },
                modifier = Modifier.weight(letterWeight),
                height = keySize,
                pressed = pressedKey == letter.toString(),
                testKey = letter.toString(),
            )
        }
        KeyboardKey(
            label = "",
            modifier = Modifier.weight(modifierWeight),
            height = keySize,
            pressed = pressedKey == BACKSPACE_KEY,
            testKey = "backspace",
            icon = KeyboardKeyIcon.BACKSPACE,
        )
    }
}

@Composable
private fun KeyboardBottomRow(rowHeight: Dp, rowCount: Int, language: Language, pressedKey: String?) {
    val gap = 3.dp
    val keys = bottomRowKeyWeights(spaceLabel = "space")
    Row(
        modifier = Modifier.offset(y = rowHeight * (rowCount - 1)),
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        keys.forEach { (label, widthWeight) ->
            KeyboardKey(
                label = when (label) {
                    "space" -> language.localeTag.replace('-', ' ').uppercase()
                    ENTER_KEY -> "↵"
                    else -> label
                },
                modifier = Modifier.weight(widthWeight),
                height = rowHeight,
                pressed = pressedKey == label || (label == "space" && pressedKey == " "),
                testKey = when (label) {
                    GLOBE_KEY -> "globe"
                    SETTINGS_KEY -> "settings"
                    BACKSPACE_KEY -> "backspace"
                    ENTER_KEY -> "enter"
                    else -> label
                },
                icon = when (label) {
                    GLOBE_KEY -> KeyboardKeyIcon.GLOBE
                    ENTER_KEY -> KeyboardKeyIcon.ENTER
                    else -> null
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
    testKey: String? = null,
    icon: KeyboardKeyIcon? = null,
    shiftState: KeyboardShiftState = KeyboardShiftState.LOWERCASE,
) {
    val keyModifier = modifier
        .then(width?.let { Modifier.width(it) } ?: Modifier)
        .height(height)
    val shape = RoundedCornerShape(8.dp)
    val faceColor by animateColorAsState(
        targetValue = if (pressed) KeyboardPalette.AccentWash else KeyboardPalette.Key,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "keyFill",
    )
    val borderColor by animateColorAsState(
        targetValue = if (pressed) KeyboardPalette.Accent else KeyboardPalette.KeyBorder,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "keyOutline",
    )
    Box(
        modifier = keyModifier
            .semantics {
                testKey?.let { contentDescription = "Iaido key $it" }
            }
            .background(faceColor, shape)
            .border(width = 1.dp, color = borderColor, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) {
            Text(
                label,
                color = KeyboardPalette.PrimaryInk,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            val iconColor = if (icon == KeyboardKeyIcon.SHIFT && shiftState != KeyboardShiftState.LOWERCASE) {
                KeyboardPalette.Accent
            } else {
                KeyboardPalette.PrimaryInk
            }
            Canvas(
                Modifier
                    .sizeIn(maxWidth = 24.dp, maxHeight = 24.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                drawKeyboardKeyIcon(icon, iconColor, shiftState)
            }
        }
    }
}

private fun DrawScope.drawKeyboardKeyIcon(
    icon: KeyboardKeyIcon,
    color: Color,
    shiftState: KeyboardShiftState,
) {
    val scale = size.minDimension / 24f
    if (scale <= 0f) return
    val left = (size.width - 24f * scale) / 2f
    val top = (size.height - 24f * scale) / 2f
    fun x(value: Float) = left + value * scale
    fun y(value: Float) = top + value * scale
    fun point(xValue: Float, yValue: Float) = Offset(x(xValue), y(yValue))
    val outline = Stroke(width = 1.8f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (icon) {
        KeyboardKeyIcon.BACKSPACE -> {
            val body = Path().apply {
                moveTo(x(8f), y(4.5f))
                lineTo(x(20f), y(4.5f))
                lineTo(x(20f), y(19.5f))
                lineTo(x(8f), y(19.5f))
                lineTo(x(2.5f), y(12f))
                close()
            }
            drawPath(body, color, style = outline)
            val cross = Path().apply {
                moveTo(x(11f), y(9f))
                lineTo(x(17f), y(15f))
                moveTo(x(17f), y(9f))
                lineTo(x(11f), y(15f))
            }
            drawPath(cross, color, style = outline)
        }
        KeyboardKeyIcon.ENTER -> {
            val returnStem = Path().apply {
                moveTo(x(18.5f), y(4.5f))
                lineTo(x(18.5f), y(10.5f))
                cubicTo(x(18.5f), y(12.5f), x(17f), y(14f), x(15f), y(14f))
                lineTo(x(6.5f), y(14f))
            }
            val arrowHead = Path().apply {
                moveTo(x(10.5f), y(9.5f))
                lineTo(x(5.5f), y(14f))
                lineTo(x(10.5f), y(18.5f))
            }
            drawPath(returnStem, color, style = outline)
            drawPath(arrowHead, color, style = outline)
        }
        KeyboardKeyIcon.GLOBE -> {
            drawCircle(color, radius = 8.5f * scale, center = point(12f, 12f), style = outline)
            drawLine(color, point(4f, 12f), point(20f, 12f), strokeWidth = outline.width, cap = StrokeCap.Round)
            val meridian = Path().apply {
                moveTo(x(12f), y(3.5f))
                cubicTo(x(8.5f), y(6f), x(7f), y(9f), x(7f), y(12f))
                cubicTo(x(7f), y(15f), x(8.5f), y(18f), x(12f), y(20.5f))
                cubicTo(x(15.5f), y(18f), x(17f), y(15f), x(17f), y(12f))
                cubicTo(x(17f), y(9f), x(15.5f), y(6f), x(12f), y(3.5f))
            }
            drawPath(meridian, color, style = outline)
        }
        KeyboardKeyIcon.SHIFT -> {
            val arrow = Path().apply {
                moveTo(x(12f), y(3f))
                lineTo(x(3.5f), y(11f))
                lineTo(x(8f), y(11f))
                lineTo(x(8f), y(19.5f))
                lineTo(x(16f), y(19.5f))
                lineTo(x(16f), y(11f))
                lineTo(x(20.5f), y(11f))
                close()
            }
            drawPath(
                path = arrow,
                color = color,
                style = if (shiftState == KeyboardShiftState.CAPS_LOCK) {
                    androidx.compose.ui.graphics.drawscope.Fill
                } else {
                    outline
                },
            )
        }
    }
}

private fun DrawScope.drawSwipeTrail(
    points: List<GesturePoint>,
    color: androidx.compose.ui.graphics.Color,
    alpha: Float,
) {
    if (points.isEmpty()) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = path,
        color = color.copy(alpha = 0.78f * alpha),
        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    points.takeLast(4).forEach { point ->
        drawCircle(color = color.copy(alpha = 0.9f * alpha), radius = 4.dp.toPx(), center = Offset(point.x, point.y))
    }
}

internal fun keyAt(
    x: Float,
    y: Float,
    size: Float,
    layout: KeyboardLayout,
    language: Language,
    showNumberRow: Boolean = false,
): String? {
    if (x < 0f || y < 0f || size <= 0f) return null
    if (showNumberRow && y < size) return numberRowKeyAt(x, size)
    if (y >= keyboardBottomRowTopPx(size, keyboardRowCount(showNumberRow))) {
        return bottomRowKeyAt(x, size, language)
    }
    val modifierRowTop = keyboardBottomRowTopPx(size, keyboardRowCount(showNumberRow)) - size
    if (y >= modifierRowTop) {
        val modifierWeight = modifierKeyWeight(keyboardLetterRowsFor(language).last().length)
        if (x < modifierWeight * size) {
            return if (language == Language.ENGLISH) SHIFT_KEY else null
        }
        if (x >= (KEYBOARD_LETTER_ROW_COLUMN_COUNT - modifierWeight) * size) return BACKSPACE_KEY
    }
    return layout.keys.minByOrNull { (x - it.x) * (x - it.x) + (y - it.y) * (y - it.y) }?.letter?.toString()
}

internal fun numberRowKeyAt(x: Float, size: Float): String? {
    if (x < 0f || size <= 0f) return null
    val slotWidth = KEYBOARD_LETTER_ROW_COLUMN_COUNT * size / 10f
    val digitIndex = (x / slotWidth).toInt().takeIf { it in 0..9 } ?: return null
    return "1234567890"[digitIndex].toString()
}

internal fun bottomRowKeyAt(x: Float, size: Float, language: Language): String? {
    if (x < 0f || size <= 0f) return null
    val keys = bottomRowKeyWeights(spaceLabel = " ")
    val unit = x / size
    var end = 0f
    return keys.firstOrNull { (_, weight) ->
        end += weight
        unit < end
    }?.first
}

/**
 * Row/weight pairs for the space-bar row. The comma and period balance around the centered space
 * bar, with language switching on the left and Enter on the right.
 *
 * These weights MUST sum to exactly [KEYBOARD_COLUMN_COUNT]. [bottomRowKeyAt] treats
 * `size` (== keySizePx == widthPx / KEYBOARD_LETTER_ROW_COLUMN_COUNT) as one weight unit and walks
 * cumulative weights to hit-test a touch x-coordinate; that only covers the full screen width when
 * the weights sum to the same column count used to derive `size`. A mismatch here silently breaks
 * hit-testing for whichever key ends up rightmost (see the regression fixed alongside this test).
 */
internal fun bottomRowKeyWeights(spaceLabel: String): List<Pair<String, Float>> = listOf(
    GLOBE_KEY to 1f,
    "," to 1f,
    spaceLabel to 6f,
    "." to 1f,
    ENTER_KEY to 1f,
)

internal fun modifierKeyWeight(letterCount: Int): Float =
    if (letterCount <= 0) 0f else maxOf(
        1f,
        ((KEYBOARD_LETTER_ROW_COLUMN_COUNT - letterCount).coerceAtLeast(0)) / 2f,
    )

internal fun modifierLetterKeyWeight(letterCount: Int): Float =
    if (letterCount <= 0) 0f else {
        (KEYBOARD_LETTER_ROW_COLUMN_COUNT - modifierKeyWeight(letterCount) * 2f) / letterCount
    }

internal fun modifierRowLetterCenterPx(index: Int, letterCount: Int, keySizePx: Float): Float {
    require(index in 0 until letterCount)
    return (modifierKeyWeight(letterCount) + (index + 0.5f) * modifierLetterKeyWeight(letterCount)) * keySizePx
}

/** Letter rows retain the existing QWERTY and Hebrew ordering. */
private fun keyboardLetterRowsFor(language: Language): List<String> = if (language == Language.ENGLISH) {
    listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
} else {
    listOf(
        "\u05e7\u05e8\u05d0\u05d8\u05d5\u05df\u05dd\u05e4",
        "\u05e9\u05d3\u05d2\u05db\u05e2\u05d9\u05d7\u05dc\u05da\u05e3",
        "\u05d6\u05e1\u05d1\u05d4\u05e0\u05de\u05e6\u05ea\u05e5",
    )
}

internal const val KEYBOARD_LETTER_ROW_COLUMN_COUNT = KEYBOARD_COLUMN_COUNT

internal fun keyboardLayoutFor(size: Float, language: Language, showNumberRow: Boolean): KeyboardLayout {
    val rows = keyboardLetterRowsFor(language)
    val columnCount = KEYBOARD_LETTER_ROW_COLUMN_COUNT
    val keys = buildList {
        rows.forEachIndexed { row, letters ->
            val layoutRow = row + if (showNumberRow) 1 else 0
            if (row == rows.lastIndex) {
                addModifierRow(letters, layoutRow, size)
            } else {
                addRow(letters, keyboardRowOffsetUnits(letters.length, columnCount), layoutRow, size)
            }
        }
    }
    return KeyboardLayout(keys)
}

private fun MutableList<KeyPosition>.addRow(letters: String, offset: Float, row: Int, size: Float) {
    letters.forEachIndexed { index, letter -> add(KeyPosition(letter, (index + 0.5f + offset) * size, (row + 0.5f) * size)) }
}

private fun MutableList<KeyPosition>.addModifierRow(letters: String, row: Int, size: Float) {
    letters.forEachIndexed { index, letter ->
        add(
            KeyPosition(
                letter,
                modifierRowLetterCenterPx(index, letters.length, size),
                (row + 0.5f) * size,
            ),
        )
    }
}

private fun MotionEvent.toGesturePoint(index: Int) = GesturePoint(getX(index), getY(index), eventTime)

/**
 * Builds an immutable [TouchFrame] snapshot of this event, capturing every active pointer's
 * position immediately. Never retains `this` - the caller passes the returned [TouchFrame]
 * onward instead, since Android recycles [MotionEvent] instances after the callback returns.
 */
private fun MotionEvent.toTouchFrame(surfaceWidthPx: Float, surfaceHeightPx: Float): TouchFrame = TouchFrame(
    action = actionMasked,
    eventTimeMs = eventTime,
    surfaceWidthPx = surfaceWidthPx,
    surfaceHeightPx = surfaceHeightPx,
    pointers = (0 until pointerCount).map { index ->
        TouchPointer(pointerId = getPointerId(index), xPx = getX(index), yPx = getY(index))
    },
)

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
private const val KEYBOARD_SIDE_INSET_DP = 4f
private val BOTTOM_KEYBOARD_CLEARANCE_DP = 24.dp
