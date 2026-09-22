package com.iaido.app

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.max

private const val CURSOR_HOLD_TIMEOUT_MS = 360L

private data class SentenceStripFocusTarget(
    val sentenceStart: Int,
    val wordStart: Int,
    val selectionStart: Int,
    val scrollValue: Int,
    val cursorContentX: Float,
    val cursorViewportX: Float?,
    val viewportWidthPx: Int,
)

@Composable
internal fun SentenceStrip(
    state: SentenceStripState,
    rtl: Boolean,
    actions: SentenceStripActions? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val currentStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val alternativeStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)
    val scrollState = rememberScrollState()
    val layoutDirection = SentenceStripLayoutMath.textDirection(rtl)
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val previewState = remember(state.sentenceStart) { mutableStateOf<SentenceStripPreview?>(null) }
    var preview by previewState
    val alternativeOverrides = remember { mutableStateMapOf<String, SentenceWordAlternativeOverride>() }
    val currentLayouts = remember { mutableStateMapOf<String, TextLayoutResult>() }
    val wordCoordinates = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    val edgeScrollTargetState = remember { mutableStateOf<EdgeScrollTarget?>(null) }
    val gestureActiveState = remember { mutableStateOf(false) }
    var lastEdgeDirection by remember { mutableStateOf(EdgeScrollDirection.RIGHT) }
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val separators = remember(state.words, state.sentenceText, currentStyle, textMeasurer) {
        state.words.zipWithNext().map { (word, next) ->
            val start = (word.endExclusive - state.sentenceStart).coerceIn(0, state.sentenceText.length)
            val end = (next.start - state.sentenceStart).coerceIn(start, state.sentenceText.length)
            state.sentenceText.substring(start, end)
        }
    }
    val trailingText = remember(state.words, state.sentenceText) {
        state.words.lastOrNull()?.let { last ->
            state.sentenceText.substring((last.endExclusive - state.sentenceStart).coerceIn(0, state.sentenceText.length))
        } ?: state.sentenceText
    }
    val trailingWidthPx = remember(trailingText, currentStyle, textMeasurer, layoutDirection) {
        textMeasurer.measure(
            trailingText,
            currentStyle,
            maxLines = 1,
            softWrap = false,
            layoutDirection = layoutDirection,
        ).size.width.toFloat()
    }
    val separatorWidthsPx = remember(separators, currentStyle, textMeasurer, layoutDirection) {
        separators.map {
            textMeasurer.measure(
                it,
                currentStyle,
                maxLines = 1,
                softWrap = false,
                layoutDirection = layoutDirection,
            ).size.width.toFloat()
        }
    }
    LaunchedEffect(state.words) {
        val wordsById = state.words.associateBy(SentenceStripWord::id)
        alternativeOverrides.toMap().forEach { (id, override) ->
            if (wordsById[id]?.text != override.selectedText) alternativeOverrides.remove(id)
        }
        val liveIds = wordsById.keys
        wordCoordinates.keys.toList()
            .filterNot(liveIds::contains)
            .forEach(wordCoordinates::remove)
        currentLayouts.keys.toList()
            .filterNot(liveIds::contains)
            .forEach(currentLayouts::remove)
    }
    val displayWords = state.words.map { word ->
        alternativeOverrides[word.id]?.apply(word) ?: word
    }
    val measuredLanes = remember(
        displayWords,
        currentStyle,
        alternativeStyle,
        textMeasurer,
        density,
        layoutDirection,
    ) {
        displayWords.map { word ->
            val currentLayout = textMeasurer.measure(
                word.text,
                currentStyle,
                maxLines = 1,
                softWrap = false,
                layoutDirection = layoutDirection,
            )
            val upperLayout = word.above?.let {
                textMeasurer.measure(
                    it,
                    alternativeStyle,
                    maxLines = 1,
                    softWrap = false,
                    layoutDirection = layoutDirection,
                )
            }
            val lowerLayout = word.below?.let {
                textMeasurer.measure(
                    it,
                    alternativeStyle,
                    maxLines = 1,
                    softWrap = false,
                    layoutDirection = layoutDirection,
                )
            }
            val maxWidth = max(currentLayout.size.width, max(upperLayout?.size?.width ?: 0, lowerLayout?.size?.width ?: 0))
            val anchors = (0..word.text.length).map { offset ->
                currentLayout.getHorizontalPosition(offset, usePrimaryDirection = true)
            }
            word to SentenceStripMeasuredWord(
                id = word.id,
                start = word.start,
                endExclusive = word.endExclusive,
                laneWidthPx = maxWidth.toFloat(),
                glyphWidthPx = currentLayout.size.width.toFloat(),
                baselinePx = currentLayout.firstBaseline,
                cursorAnchorsPx = anchors,
            )
        }
    }
    val metrics = remember(measuredLanes, separatorWidthsPx, trailingWidthPx, rtl, viewportWidthPx, density) {
        SentenceStripGeometry.create(
            words = measuredLanes.map { it.second },
            gapWidthsPx = separatorWidthsPx,
            viewportWidthPx = viewportWidthPx.toFloat(),
            isRtl = rtl,
            addedGapPx = with(density) {
                SentenceStripGeometry.WORD_GAP_CSS_PX.dp.roundToPx().toFloat()
            },
            trailingContentWidthPx = trailingWidthPx,
        )
    }

    val liveState = rememberUpdatedState(state.copy(words = displayWords))
    val liveMetrics = rememberUpdatedState(metrics)
    val liveViewportCoordinates = rememberUpdatedState(viewportCoordinates)
    val liveRowCoordinates = rememberUpdatedState(rowCoordinates)
    LaunchedEffect(gestureActiveState.value, rtl) {
        if (gestureActiveState.value) return@LaunchedEffect
        var lastSentenceStart: Int? = null
        var lastFocusedWordStart: Int? = null
        var lastViewportWidthPx = 0
        snapshotFlow {
            val currentState = liveState.value
            val currentMetrics = liveMetrics.value
            val previousFocusIndex = lastFocusedWordStart?.let { start ->
                currentState.words.indexOfFirst { it.start == start }.takeIf { it >= 0 }
            }
            val focused = cursorFocusedWordIndex(
                words = currentState.words,
                cursorPosition = currentState.selectionStart,
                start = SentenceStripWord::start,
                endExclusive = SentenceStripWord::endExclusive,
                previousFocusedIndex = previousFocusIndex,
            ) ?: -1
            val word = currentState.words.getOrNull(focused)
            val cursorOffset = word?.let {
                (currentState.selectionStart - it.start).coerceIn(0, it.text.length)
            }
            val renderedLayout = word?.let { currentLayouts[it.id] }
            // Prefer the measured viewport position. Model-space RTL bounds can
            // be stale for one frame while a long, newly typed sentence is
            // being remeasured, which otherwise makes the strip follow a gap.
            val viewport = liveViewportCoordinates.value
            val renderedWord = word?.let { wordCoordinates[it.id] }
            val renderedCursorViewportX = if (
                viewport != null && viewport.isAttached &&
                renderedWord != null && renderedWord.isAttached &&
                cursorOffset != null && renderedLayout != null
            ) {
                val textLeft = (renderedWord.size.width - renderedLayout.size.width) / 2f
                val cursorLocalX = textLeft + renderedLayout.getCursorRect(cursorOffset).left
                viewport.localPositionOf(renderedWord, Offset(cursorLocalX, 0f)).x
            } else {
                null
            }
            val cursorX = renderedCursorViewportX?.let { it + SentenceStripScrollMath.contentOffsetForValue(
                scrollValuePx = scrollState.value.toFloat(),
                maxScrollPx = scrollState.maxValue.toFloat(),
                isRtl = rtl,
            ) } ?: currentMetrics.cursorContentX(focused, currentState.selectionStart)
            if (word == null || cursorX == null) {
                null
            } else {
                val maxScrollPx = scrollState.maxValue.toFloat()
                val targetPx = SentenceStripScrollMath.valueForContentOffset(
                    contentOffsetPx = currentMetrics.focusScrollOffset(focused),
                    maxScrollPx = maxScrollPx,
                    isRtl = rtl,
                ).toInt().coerceIn(0, scrollState.maxValue)
                SentenceStripFocusTarget(
                    sentenceStart = currentState.sentenceStart,
                    wordStart = word.start,
                    selectionStart = currentState.selectionStart,
                    scrollValue = targetPx,
                    cursorContentX = cursorX,
                    cursorViewportX = renderedCursorViewportX,
                    viewportWidthPx = viewportWidthPx,
                )
            }
        }.collectLatest { target ->
            if (target == null) return@collectLatest
            val isNewFocus = target.sentenceStart != lastSentenceStart ||
                target.wordStart != lastFocusedWordStart ||
                (target.viewportWidthPx > 0 && target.viewportWidthPx != lastViewportWidthPx)
            val comfortMarginPx = minOf(with(density) { 28.dp.toPx() }, viewportWidthPx / 3f)
            val currentContentOffset = SentenceStripScrollMath.contentOffsetForValue(
                scrollValuePx = scrollState.value.toFloat(),
                maxScrollPx = scrollState.maxValue.toFloat(),
                isRtl = rtl,
            )
            val cursorViewportX = target.cursorViewportX ?: (target.cursorContentX - currentContentOffset)
            val measuredFollowTarget = SentenceStripScrollMath.targetValueForCursorViewport(
                cursorViewportX = cursorViewportX,
                viewportWidthPx = viewportWidthPx.toFloat(),
                currentScrollValuePx = scrollState.value.toFloat(),
                maxScrollPx = scrollState.maxValue.toFloat(),
                isRtl = rtl,
                comfortMarginPx = comfortMarginPx,
            )
            if (isNewFocus) {
                val focusTarget = when {
                    measuredFollowTarget != null -> measuredFollowTarget.toInt()
                        .coerceIn(0, scrollState.maxValue)
                    target.cursorViewportX != null -> null
                    else -> target.scrollValue
                }
                focusTarget?.let {
                    scrollState.animateScrollTo(
                        it,
                        tween(durationMillis = 120, easing = FastOutSlowInEasing),
                    )
                }
            } else {
                if (measuredFollowTarget != null) {
                    val cursorTarget = measuredFollowTarget.toInt().coerceIn(0, scrollState.maxValue)
                    scrollState.animateScrollTo(
                        cursorTarget,
                        tween(durationMillis = 90, easing = FastOutSlowInEasing),
                    )
                }
            }
            lastSentenceStart = target.sentenceStart
            lastFocusedWordStart = target.wordStart
            lastViewportWidthPx = target.viewportWidthPx
        }
    }

    val liveActions = rememberUpdatedState(actions)
    val wordPreview = preview as? SentenceWordPreview
    val replacementPreview = preview as? SentenceReplacementPreview
    val deletionPreview = preview as? SentenceDeletionPreview
    LaunchedEffect(edgeScrollTargetState.value?.direction) {
        edgeScrollTargetState.value?.direction?.let { lastEdgeDirection = it }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(KeyboardPalette.Screen)
                .semantics {
                    contentDescription = "Iaido sentence strip"
                },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (deletionPreview != null) "DELETE WORDS" else "SWIPE A WORD TO CORRECT",
                    modifier = Modifier.weight(1f),
                    color = if (deletionPreview != null) KeyboardPalette.Delete else KeyboardPalette.SubtleInk,
                    fontSize = 11.sp,
                    letterSpacing = 1.3.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = state.words.firstOrNull { state.selectionStart in it.start..it.endExclusive }?.text
                        ?.uppercase() ?: "",
                    color = KeyboardPalette.Accent,
                    fontSize = 12.sp,
                    letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.width(4.dp))
                androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row {
                        SentenceStripLayoutMath.historyIsUndoOrder(rtl).forEach { isUndo ->
                            HistoryIcon(
                                description = if (isUndo) "Iaido undo" else "Iaido redo",
                                enabled = if (isUndo) state.canUndo else state.canRedo,
                                isUndo = isUndo,
                                preview = if (isUndo) state.undoPreview else state.redoPreview,
                                onClick = { if (isUndo) actions?.undo() else actions?.redo() },
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(78.dp)
                    .onSizeChanged { viewportWidthPx = it.width }
                    .onGloballyPositioned { viewportCoordinates = it }
                    .sentenceStripInput(
                        state = liveState,
                        geometry = liveMetrics,
                        actions = liveActions,
                        preview = previewState,
                        layouts = currentLayouts,
                        alternativeOverrides = alternativeOverrides,
                        scrollState = scrollState,
                        density = density,
                        viewportWidthPx = rememberUpdatedState(viewportWidthPx),
                        edgeScrollTarget = edgeScrollTargetState,
                        gestureActive = gestureActiveState,
                        rtl = rtl,
                        viewportCoordinates = liveViewportCoordinates,
                        rowCoordinates = liveRowCoordinates,
                        wordCoordinates = wordCoordinates,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .height(78.dp)
                        .horizontalScroll(scrollState)
                        .onGloballyPositioned { rowCoordinates = it }
                        .semantics {
                            preview?.let { contentDescription = "Iaido sentence preview text=${it.sentenceText}" }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    measuredLanes.forEachIndexed { index, (_, measured) ->
                        val word = displayWords[index]
                        key(word.id) {
                            val laneWidth = with(density) { measured.laneWidthPx.toDp() }
                            val selected = wordPreview?.takeIf { it.wordIndex == index }
                            val split = replacementPreview?.takeIf {
                                it.isSplit && it.sourceWordIndices.singleOrNull() == index
                            }
                            val deleting = deletionPreview?.sourceWordIds?.contains(word.id) == true
                            val renderWord = selected?.let {
                                word.copy(
                                    text = it.replacement,
                                    above = if (it.side == SentenceAlternativeSide.ABOVE) word.text else word.above,
                                    below = if (it.side == SentenceAlternativeSide.BELOW) word.text else word.below,
                                )
                            } ?: split?.let {
                                word.copy(
                                    above = if (it.side == SentenceAlternativeSide.ABOVE) word.text else word.above,
                                    below = if (it.side == SentenceAlternativeSide.BELOW) word.text else word.below,
                                )
                            } ?: if (deleting) word.copy(above = null, below = null) else word
                            SentenceWordLane(
                                word = renderWord,
                                wordIndex = index,
                                laneWidth = laneWidth,
                                currentStyle = currentStyle,
                                alternativeStyle = alternativeStyle,
                                cursorOffset = (state.selectionStart - word.start)
                                    .takeIf { split == null && !deleting && it in 0..(word.endExclusive - word.start) },
                                onSelect = { actions?.setSelection(word.endExclusive) },
                                isPreviewing = selected != null || split != null,
                                previewSide = selected?.side ?: split?.side,
                                onCurrentTextLayout = { currentLayouts[word.id] = it },
                                onPositioned = { wordCoordinates[word.id] = it },
                                splitPreviewWords = split?.replacementWords,
                                isDeleting = deleting,
                            )
                            if (index < separators.size) {
                                Text(
                                    text = separators[index],
                                    color = KeyboardPalette.PrimaryInk,
                                    style = currentStyle,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.widthIn(min = 0.dp),
                                )
                                Spacer(Modifier.width(SentenceStripGeometry.WORD_GAP_CSS_PX.dp))
                            }
                        }
                    }
                    if (trailingText.isNotEmpty()) {
                        Text(
                            text = trailingText,
                            color = KeyboardPalette.PrimaryInk,
                            style = currentStyle,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (metrics.focusRunwayWidthPx > 0f) {
                        Spacer(Modifier.width(with(density) { metrics.focusRunwayWidthPx.toDp() }))
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(1.dp)
                        .semantics {
                            contentDescription = "Iaido sentence cursor offset=${state.selectionStart}"
                        },
                )
                val edgeFade by animateFloatAsState(
                    targetValue = if (edgeScrollTargetState.value != null) 1f else 0f,
                    animationSpec = tween(110),
                    label = "edge-scroll-affordance",
                )
                EdgeScrollAffordance(
                    direction = lastEdgeDirection,
                    penetration = edgeScrollTargetState.value?.penetration ?: 0f,
                    active = edgeScrollTargetState.value != null,
                    modifier = Modifier
                        .graphicsLayer { alpha = edgeFade }
                        .align(
                            if (lastEdgeDirection == EdgeScrollDirection.LEFT) {
                                Alignment.CenterStart
                            } else {
                                Alignment.CenterEnd
                            },
                    ),
                )
                replacementPreview?.takeIf(SentenceReplacementPreview::isJoin)?.let { join ->
                    JoinPreviewOverlay(
                        preview = join,
                        state = state,
                        geometry = metrics,
                        scrollValue = scrollState.value,
                        maxScrollValue = scrollState.maxValue,
                        isRtl = rtl,
                        density = density,
                        viewportCoordinates = viewportCoordinates,
                        rowCoordinates = rowCoordinates,
                        wordCoordinates = wordCoordinates,
                    )
                }
                deletionPreview?.let { deletion ->
                    DeletionPreviewOverlay(
                        preview = deletion,
                        geometry = metrics,
                        scrollValue = scrollState.value,
                        maxScrollValue = scrollState.maxValue,
                        isRtl = rtl,
                        density = density,
                        viewportCoordinates = viewportCoordinates,
                        rowCoordinates = rowCoordinates,
                        wordCoordinates = wordCoordinates,
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinPreviewOverlay(
    preview: SentenceReplacementPreview,
    state: SentenceStripState,
    geometry: SentenceStripGeometry,
    scrollValue: Int,
    maxScrollValue: Int,
    isRtl: Boolean,
    density: Density,
    viewportCoordinates: LayoutCoordinates?,
    rowCoordinates: LayoutCoordinates?,
    wordCoordinates: Map<String, LayoutCoordinates>,
) {
    val first = preview.sourceWordIndices.minOrNull() ?: return
    val last = preview.sourceWordIndices.maxOrNull() ?: return
    val bounds = geometry.joinUnion(first, last)
    val viewportBounds = viewportWordUnion(
        ids = (first..last).mapNotNull { state.words.getOrNull(it)?.id },
        viewportCoordinates = viewportCoordinates,
        wordCoordinates = wordCoordinates,
    )
    // Measured bounds are already in the viewport's coordinate space. Only
    // use the model-space transform as a fallback before the first layout pass.
    val leftPx = viewportBounds?.left ?: stripViewportX(
        bounds.left,
        geometry.contentWidthPx,
        scrollValue,
        maxScrollValue,
        isRtl,
        viewportCoordinates,
        rowCoordinates,
    )
    val rightPx = viewportBounds?.right ?: stripViewportX(
        bounds.right,
        geometry.contentWidthPx,
        scrollValue,
        maxScrollValue,
        isRtl,
        viewportCoordinates,
        rowCoordinates,
    )
    val left = with(density) { minOf(leftPx, rightPx).toDp() }
    val width = with(density) { abs(rightPx - leftPx).toDp() }
    val originalText = preview.sourceWordIndices.sorted().mapNotNull(state.words::getOrNull)
        .joinToString(" ", transform = SentenceStripWord::text)
    val alternative = preview.sourceWordIndices.asSequence()
        .mapNotNull(state.words::getOrNull)
        .flatMap { sequenceOf(it.above, it.below) }
        .filterNotNull()
        .firstOrNull { it != preview.replacementText && it != originalText }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .absoluteOffset(x = left, y = 6.dp)
            .width(width)
            .height(66.dp)
            .background(KeyboardPalette.Screen, shape)
            .border(1.2.dp, KeyboardPalette.Accent, shape)
            .semantics {
                contentDescription = "Iaido join preview text=${preview.replacementText} " +
                    "sourceStart=${preview.sourceStart} sourceEnd=${preview.sourceEndExclusive}"
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .requiredWidth(44.dp)
                    .background(KeyboardPalette.Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text(
                    "JOIN",
                    color = KeyboardPalette.Page,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Text(
                text = originalText,
                color = KeyboardPalette.PrimaryInk.copy(alpha = 0.64f),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = preview.replacementText,
                color = KeyboardPalette.Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = alternative.orEmpty(),
                color = KeyboardPalette.PrimaryInk.copy(alpha = 0.64f),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun DeletionPreviewOverlay(
    preview: SentenceDeletionPreview,
    geometry: SentenceStripGeometry,
    scrollValue: Int,
    maxScrollValue: Int,
    isRtl: Boolean,
    density: Density,
    viewportCoordinates: LayoutCoordinates?,
    rowCoordinates: LayoutCoordinates?,
    wordCoordinates: Map<String, LayoutCoordinates>,
) {
    val bounds = preview.wordRange.first
        .takeIf { it >= 0 && preview.wordRange.last < geometry.words.size }
        ?.let { geometry.joinUnion(it, preview.wordRange.last) }
    val viewportBounds = viewportWordUnion(
        ids = preview.sourceWordIds,
        viewportCoordinates = viewportCoordinates,
        wordCoordinates = wordCoordinates,
    )
    if (viewportCoordinates?.isAttached == true && viewportBounds == null) return
    if (viewportBounds == null && bounds == null) return
    // The rendered lanes are laid out by Compose's bidi-aware Row. Use their
    // measured viewport bounds whenever available; transforming model-space
    // bounds again mirrors the RTL row a second time and clips the overlay.
    val leftPx = viewportBounds?.left ?: bounds?.let {
        stripViewportX(
            it.left,
            geometry.contentWidthPx,
            scrollValue,
            maxScrollValue,
            isRtl,
            viewportCoordinates,
            rowCoordinates,
        )
    } ?: return
    val rightPx = viewportBounds?.right ?: bounds?.let {
        stripViewportX(
            it.right,
            geometry.contentWidthPx,
            scrollValue,
            maxScrollValue,
            isRtl,
            viewportCoordinates,
            rowCoordinates,
        )
    } ?: return
    val left = with(density) { minOf(leftPx, rightPx).toDp() }
    val width = with(density) { abs(rightPx - leftPx).toDp() }
    val badgeWidth = 64.dp
    val badgeWidthPx = with(density) { badgeWidth.toPx() }
    val badgeLeft = with(density) {
        (
            ((leftPx + rightPx) / 2f - badgeWidthPx / 2f)
                .coerceIn(0f, (geometry.viewportWidthPx - badgeWidthPx).coerceAtLeast(0f))
            ).toDp()
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "Iaido deletion preview startWord=${preview.wordRange.first} " +
                    "endWord=${preview.wordRange.last} start=${preview.sourceStart} " +
                    "end=${preview.sourceEndExclusive}"
            },
    ) {
        Box(
            modifier = Modifier
                .absoluteOffset(x = left, y = 6.dp)
                .width(width)
                .height(66.dp)
                .background(KeyboardPalette.Delete.copy(alpha = 0.12f), shape)
                .border(1.5.dp, KeyboardPalette.Delete, shape),
        )
        Text(
            text = "DELETE",
            modifier = Modifier
                .absoluteOffset(x = badgeLeft, y = 6.dp)
                .requiredWidth(badgeWidth)
                .background(KeyboardPalette.Delete, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp),
            color = KeyboardPalette.Page,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun viewportWordUnion(
    ids: List<String>,
    viewportCoordinates: LayoutCoordinates?,
    wordCoordinates: Map<String, LayoutCoordinates>,
): StripRect? {
    if (ids.isEmpty() || viewportCoordinates == null || !viewportCoordinates.isAttached) return null
    val boundsById = ids.mapNotNull { id ->
        val word = wordCoordinates[id] ?: return@mapNotNull null
        if (!word.isAttached) return@mapNotNull null
        // Use Compose's transformed bounding box instead of transforming two
        // endpoints. This preserves the actual RTL placement and horizontal
        // scroll translation applied to the lane, including any intermediate
        // graphics layers.
        val bounds = viewportCoordinates.localBoundingBoxOf(word, clipBounds = false)
        if (bounds.width <= 0f || bounds.height <= 0f) return@mapNotNull null
        id to StripRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
    return unionRenderedWordBounds(ids, boundsById.toMap())
}

private fun stripViewportX(
    contentX: Float,
    contentWidthPx: Float,
    scrollValue: Int,
    maxScrollValue: Int,
    isRtl: Boolean,
    viewportCoordinates: LayoutCoordinates? = null,
    rowCoordinates: LayoutCoordinates? = null,
): Float = if (
    viewportCoordinates != null && rowCoordinates != null &&
    viewportCoordinates.isAttached && rowCoordinates.isAttached && contentWidthPx > 0f
) {
    val transform = stripAxisTransform(viewportCoordinates, rowCoordinates, contentWidthPx)
    transform?.viewportXFromContent(contentX) ?: contentX
} else {
    val contentOffset = SentenceStripScrollMath.contentOffsetForValue(
        scrollValuePx = scrollValue.toFloat(),
        maxScrollPx = maxScrollValue.toFloat(),
        isRtl = isRtl,
    )
    SentenceStripCoordinateMath.viewportXFromContent(
        contentX = contentX,
        viewportLeftInRoot = 0f,
        rowLeftInRoot = -contentOffset,
    )
}

private fun stripAxisTransform(
    viewportCoordinates: LayoutCoordinates,
    rowCoordinates: LayoutCoordinates,
    contentWidthPx: Float,
): SentenceStripCoordinateMath.AxisTransform? {
    if (contentWidthPx <= 0f || rowCoordinates.size.width <= 0) return null
    val viewportLeft = viewportCoordinates.localPositionOf(rowCoordinates, Offset.Zero).x
    val viewportRight = viewportCoordinates.localPositionOf(
        rowCoordinates,
        Offset(rowCoordinates.size.width.toFloat(), 0f),
    ).x
    return SentenceStripCoordinateMath.fromBounds(
        contentLeft = 0f,
        contentRight = contentWidthPx,
        viewportLeft = viewportLeft,
        viewportRight = viewportRight,
    )
}

@Composable
private fun SentenceWordLane(
    word: SentenceStripWord,
    wordIndex: Int,
    laneWidth: androidx.compose.ui.unit.Dp,
    currentStyle: TextStyle,
    alternativeStyle: TextStyle,
    cursorOffset: Int?,
    onSelect: () -> Unit,
    isPreviewing: Boolean,
    previewSide: SentenceAlternativeSide?,
    onCurrentTextLayout: (TextLayoutResult) -> Unit,
    onPositioned: (LayoutCoordinates) -> Unit,
    splitPreviewWords: List<String>?,
    isDeleting: Boolean,
) {
    val hasCaret = cursorOffset != null
    Column(
        modifier = Modifier.width(laneWidth).onGloballyPositioned(onPositioned),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AlternativeText(
            text = word.above,
            description = "Iaido sentence alternative word=$wordIndex side=above",
            style = alternativeStyle,
            motionDirection = if (previewSide == SentenceAlternativeSide.ABOVE) 1 else 0,
        )
        var textLayout by remember(word.id, word.text) { mutableStateOf<TextLayoutResult?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Iaido sentence word index=$wordIndex start=${word.start} " +
                        "end=${word.endExclusive} deleting=$isDeleting text=${word.text}"
                    onClick(label = "Place cursor at end of word") {
                        onSelect()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (splitPreviewWords != null) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    splitPreviewWords.forEachIndexed { index, text ->
                        if (index > 0) Spacer(Modifier.width(SentenceStripGeometry.WORD_GAP_CSS_PX.dp))
                        Text(
                            text = text,
                            color = KeyboardPalette.Accent,
                            style = currentStyle,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else AnimatedContent(
                targetState = word.text,
                transitionSpec = {
                    val direction = when (previewSide) {
                        SentenceAlternativeSide.ABOVE -> -1
                        SentenceAlternativeSide.BELOW -> 1
                        null -> 0
                    }
                    (slideInVertically(tween(165, easing = FastOutSlowInEasing)) { direction * it } +
                        fadeIn(tween(110))) togetherWith
                        (slideOutVertically(tween(165, easing = FastOutSlowInEasing)) { -direction * it } +
                            fadeOut(tween(110)))
                },
                label = "sentenceWordSwap",
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { renderedText ->
                Text(
                    text = renderedText,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        val layout = textLayout
                        if (hasCaret && renderedText == word.text && layout != null) {
                            val localOffset = cursorOffset!!.coerceIn(0, word.text.length)
                            val caret = layout.getCursorRect(localOffset)
                            drawLine(
                                color = KeyboardPalette.Accent,
                                start = androidx.compose.ui.geometry.Offset(caret.left, caret.top),
                                end = androidx.compose.ui.geometry.Offset(caret.left, caret.bottom),
                                strokeWidth = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                        }
                    },
                    color = when {
                        isDeleting -> KeyboardPalette.Delete
                        hasCaret || isPreviewing -> KeyboardPalette.Accent
                        else -> KeyboardPalette.PrimaryInk
                    },
                    style = currentStyle,
                    textDecoration = if (isDeleting) TextDecoration.LineThrough else TextDecoration.None,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    onTextLayout = {
                        if (renderedText == word.text) {
                            textLayout = it
                            onCurrentTextLayout(it)
                        }
                    },
                )
            }
        }
        AlternativeText(
            text = word.below,
            description = "Iaido sentence alternative word=$wordIndex side=below",
            style = alternativeStyle,
            motionDirection = if (previewSide == SentenceAlternativeSide.BELOW) -1 else 0,
        )
    }
}

@Composable
private fun AlternativeText(
    text: String?,
    description: String,
    style: TextStyle,
    motionDirection: Int,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (slideInVertically(tween(165, easing = FastOutSlowInEasing)) { motionDirection * it } +
                        fadeIn(tween(110))) togetherWith
                        (slideOutVertically(tween(165, easing = FastOutSlowInEasing)) { -motionDirection * it } +
                            fadeOut(tween(110)))
                },
                label = "sentenceAlternativeSwap",
            ) { renderedText ->
                Text(
                    text = renderedText,
                    modifier = Modifier.semantics {
                        contentDescription = "$description text=$renderedText"
                    },
                    color = KeyboardPalette.PrimaryInk.copy(alpha = 0.64f),
                    style = style,
                    maxLines = 1,
                )
            }
        }
    }
}

private enum class SentencePointerMode { PENDING, SCROLL, CURSOR, ALTERNATIVE }

private const val EDGE_SCROLL_ZONE_WIDTH_DP = 44f
private const val EDGE_SCROLL_AFFORDANCE_WIDTH_DP = 42f

private fun Modifier.sentenceStripInput(
    state: State<SentenceStripState>,
    geometry: State<SentenceStripGeometry>,
    actions: State<SentenceStripActions?>,
    preview: MutableState<SentenceStripPreview?>,
    layouts: Map<String, TextLayoutResult>,
    alternativeOverrides: MutableMap<String, SentenceWordAlternativeOverride>,
    scrollState: ScrollState,
    density: Density,
    viewportWidthPx: State<Int>,
    edgeScrollTarget: MutableState<EdgeScrollTarget?>,
    gestureActive: MutableState<Boolean>,
    rtl: Boolean,
    viewportCoordinates: State<LayoutCoordinates?>,
    rowCoordinates: State<LayoutCoordinates?>,
    wordCoordinates: Map<String, LayoutCoordinates>,
): Modifier = pointerInput(Unit) {
    coroutineScope {
        val gestureScope = this
        val dragSlop = with(density) { 7.dp.toPx() }
        awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val frozenState = state.value
        val frozenGeometry = geometry.value

        fun renderedGeometry(): SentenceStripGeometry? {
            val viewport = viewportCoordinates.value
            if (viewport == null || !viewport.isAttached) return null
            val bounds = frozenState.words.map { word ->
                val coordinates = wordCoordinates[word.id] ?: return null
                if (!coordinates.isAttached || coordinates.size.width <= 0) return null
                val left = viewport.localPositionOf(coordinates, Offset.Zero).x
                val right = viewport.localPositionOf(
                    coordinates,
                    Offset(coordinates.size.width.toFloat(), 0f),
                ).x
                StripRect(minOf(left, right), 0f, maxOf(left, right), coordinates.size.height.toFloat())
            }
            return frozenGeometry.withRenderedLaneBounds(bounds)
        }

        fun contentPosition(position: Offset): Offset {
            val viewport = viewportCoordinates.value
            val row = rowCoordinates.value
            val contentX = if (
                viewport != null && row != null && viewport.isAttached && row.isAttached &&
                    frozenGeometry.contentWidthPx > 0f
            ) {
                val transform = stripAxisTransform(viewport, row, frozenGeometry.contentWidthPx)
                transform?.contentXFromViewport(position.x)
                    ?: position.x
            } else {
                val contentOffset = SentenceStripScrollMath.contentOffsetForValue(
                    scrollValuePx = scrollState.value.toFloat(),
                    maxScrollPx = scrollState.maxValue.toFloat(),
                    isRtl = rtl,
                )
                SentenceStripCoordinateMath.contentXFromViewport(
                    viewportX = position.x,
                    viewportLeftInRoot = 0f,
                    rowLeftInRoot = -contentOffset,
                )
            }
            return Offset(contentX, position.y)
        }

        fun selectionAt(position: Offset): Int {
            val content = contentPosition(position)
            val visualGeometry = renderedGeometry()
            val hitX = if (visualGeometry != null) position.x else content.x
            val wordIndex = (visualGeometry ?: frozenGeometry).wordAt(hitX)
            if (wordIndex != null) {
                val word = frozenState.words.getOrNull(wordIndex) ?: return frozenState.selectionStart
                val layout = layouts[word.id]
                val rawOffset = if (layout != null) {
                    val laneLeft = (visualGeometry ?: frozenGeometry).words[wordIndex].laneBounds.left
                    layout.getOffsetForPosition(
                        Offset(
                            x = (hitX - laneLeft).coerceIn(0f, layout.size.width.toFloat()),
                            y = layout.size.height / 2f,
                        ),
                    )
                } else {
                    (visualGeometry ?: frozenGeometry).cursorOffsetAt(wordIndex, hitX)?.minus(word.start) ?: 0
                }
                return word.start + SentenceTextModel.snapToGraphemeBoundary(
                    word.text,
                    rawOffset,
                    frozenState.language,
                )
            }

            val boundaries = frozenGeometry.words.indices.flatMap { index ->
                val word = frozenState.words[index]
                val layout = layouts[word.id]
                if (layout != null) {
                    listOf(0, word.text.length).map { offset ->
                        frozenGeometry.words[index].laneBounds.left +
                            layout.getHorizontalPosition(offset, usePrimaryDirection = true) to (word.start + offset)
                    }
                } else {
                    listOf(
                        frozenGeometry.words[index].glyphBounds.left to word.start,
                        frozenGeometry.words[index].glyphBounds.right to word.endExclusive,
                    )
                }
            }
            return boundaries.minByOrNull { (anchorX, _) -> kotlin.math.abs(content.x - anchorX) }
                ?.second ?: frozenState.selectionStart
        }

        val downContent = contentPosition(down.position)
        val downVisualGeometry = renderedGeometry()
        val originIndex = (downVisualGeometry ?: frozenGeometry).wordAt(
            if (downVisualGeometry != null) down.position.x else downContent.x,
        )
        var mode = SentencePointerMode.PENDING
        var released = false
        var latestPosition = down.position
        var lastEdgeFrameNanos = 0L
        var lastCursorSelection: Int? = null
        var activePreview: SentenceStripPreview? = null
        var edgeFrameJob: Job? = null

        fun heldForMs(): Long = (SystemClock.uptimeMillis() - down.uptimeMillis).coerceAtLeast(0L)

        fun setCursorAt(position: Offset) {
            val selection = selectionAt(position)
            if (selection != lastCursorSelection) {
                actions.value?.setSelection(selection)
                lastCursorSelection = selection
            }
        }

        fun updateAlternativeAt(position: Offset) {
            val visualGeometry = renderedGeometry()
            val content = contentPosition(position)
            activePreview = originIndex?.let { index ->
                SentenceStripPreviewMath.gestureAt(
                    state = frozenState,
                    geometry = visualGeometry ?: frozenGeometry,
                    originWordIndex = index,
                    contentX = if (visualGeometry != null) position.x else content.x,
                    side = if (position.y < down.position.y) SentenceAlternativeSide.ABOVE
                    else SentenceAlternativeSide.BELOW,
                )
            }
            preview.value = activePreview
        }

        fun updateEdgeFrame(frameNanos: Long) {
            val viewportWidth = viewportWidthPx.value.toFloat()
            val target = SentenceStripEdgeScrollMath.targetAt(
                x = latestPosition.x,
                viewportWidth = viewportWidth,
                zoneWidth = with(density) { EDGE_SCROLL_ZONE_WIDTH_DP.dp.toPx() },
            )
            edgeScrollTarget.value = target

            if (mode == SentencePointerMode.SCROLL &&
                SentenceStripGestureMath.shouldPromoteEdgeScrollToCursor(
                    heldForMs = heldForMs(),
                    hasWordOrigin = originIndex != null,
                    isInEdgeZone = target != null,
                )
            ) {
                mode = SentencePointerMode.CURSOR
                gestureActive.value = true
                setCursorAt(latestPosition)
            }
            if (mode != SentencePointerMode.CURSOR && mode != SentencePointerMode.ALTERNATIVE) {
                lastEdgeFrameNanos = 0L
                return
            }
            if (target == null) {
                lastEdgeFrameNanos = 0L
                return
            }

            val previousFrame = lastEdgeFrameNanos
            lastEdgeFrameNanos = frameNanos
            if (previousFrame == 0L) return
            val elapsedSeconds = SentenceStripEdgeScrollMath.frameDeltaSeconds(previousFrame, frameNanos)
            val speed = SentenceStripEdgeScrollMath.speedPxPerSecond(target.penetration, density.density)
            scrollState.dispatchRawDelta(
                SentenceStripEdgeScrollMath.scrollSign(target.direction, rtl) * speed * elapsedSeconds,
            )
            when (mode) {
                SentencePointerMode.CURSOR -> setCursorAt(latestPosition)
                SentencePointerMode.ALTERNATIVE -> updateAlternativeAt(latestPosition)
                else -> Unit
            }
        }

        fun startEdgeFrames() {
            if (edgeFrameJob != null) return
            edgeFrameJob = gestureScope.launch {
                while (isActive) {
                    val frameTime = withFrameNanos { it }
                    updateEdgeFrame(frameTime)
                }
            }
        }

        withTimeoutOrNull(CURSOR_HOLD_TIMEOUT_MS) {
            while (!released && mode == SentencePointerMode.PENDING) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                latestPosition = change.position
                if (change.changedToUpIgnoreConsumed()) {
                    released = true
                    break
                }
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dy) > dragSlop) {
                    if (originIndex != null) {
                        mode = SentencePointerMode.ALTERNATIVE
                        updateAlternativeAt(change.position)
                        gestureActive.value = true
                        change.consume()
                    } else if (abs(dx) > dragSlop) {
                        mode = SentencePointerMode.SCROLL
                        startEdgeFrames()
                    }
                } else if (SentenceStripGestureMath.hasStartedHorizontalScroll(
                        dx,
                        density.density,
                        hasWordOrigin = originIndex != null,
                    )
                ) {
                    if (SentenceStripGestureMath.shouldStartCursorScrub(
                            heldForMs = change.uptimeMillis - down.uptimeMillis,
                            hasWordOrigin = originIndex != null,
                        )
                    ) {
                        mode = SentencePointerMode.CURSOR
                        gestureActive.value = true
                        setCursorAt(change.position)
                        startEdgeFrames()
                        change.consume()
                    } else {
                        mode = SentencePointerMode.SCROLL
                        startEdgeFrames()
                    }
                }
            }
        }

        if (!released && mode == SentencePointerMode.PENDING && originIndex != null) {
            mode = SentencePointerMode.CURSOR
            gestureActive.value = true
            setCursorAt(latestPosition)
        } else if (released && mode == SentencePointerMode.PENDING) {
            setCursorAt(down.position)
        }

        if (mode == SentencePointerMode.CURSOR || mode == SentencePointerMode.ALTERNATIVE ||
            mode == SentencePointerMode.SCROLL
        ) {
            startEdgeFrames()
        }
        while (!released) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            latestPosition = change.position
            if (change.changedToUpIgnoreConsumed()) {
                released = true
                val cancelled = change.isConsumed
                if (!cancelled) when (mode) {
                    SentencePointerMode.PENDING -> actions.value?.setSelection(selectionAt(change.position))
                    SentencePointerMode.ALTERNATIVE -> when (val selected = activePreview) {
                        is SentenceWordPreview -> frozenState.words.getOrNull(selected.wordIndex)?.let { word ->
                            if (actions.value?.commitWordReplacement(word.id, word.text, selected.replacement) == true) {
                                alternativeOverrides[word.id] = SentenceWordAlternativeOverride(
                                    selectedText = selected.replacement,
                                    displacedText = word.text,
                                    side = selected.side,
                                )
                            }
                        }
                        is SentenceReplacementPreview -> actions.value?.commitReplacement(selected.replacement)
                        is SentenceDeletionPreview -> actions.value?.commitDeletion(selected)
                        null -> Unit
                    }
                    SentencePointerMode.CURSOR,
                    SentencePointerMode.SCROLL -> Unit
                }
                preview.value = null
                edgeScrollTarget.value = null
                gestureActive.value = false
                if (!cancelled && (mode == SentencePointerMode.CURSOR || mode == SentencePointerMode.ALTERNATIVE)) {
                    change.consume()
                }
                continue
            }

            when (mode) {
                SentencePointerMode.PENDING -> {
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (abs(dy) > dragSlop && originIndex != null) {
                        mode = SentencePointerMode.ALTERNATIVE
                        updateAlternativeAt(change.position)
                        gestureActive.value = true
                        startEdgeFrames()
                        change.consume()
                    } else if (SentenceStripGestureMath.hasStartedHorizontalScroll(
                            dx,
                            density.density,
                            hasWordOrigin = originIndex != null,
                        )
                    ) {
                        if (SentenceStripGestureMath.shouldStartCursorScrub(
                                heldForMs = change.uptimeMillis - down.uptimeMillis,
                                hasWordOrigin = originIndex != null,
                            )
                        ) {
                            mode = SentencePointerMode.CURSOR
                            gestureActive.value = true
                            setCursorAt(change.position)
                            startEdgeFrames()
                            change.consume()
                        } else {
                            mode = SentencePointerMode.SCROLL
                            startEdgeFrames()
                        }
                    }
                }
                SentencePointerMode.SCROLL -> {
                    if (SentenceStripGestureMath.shouldPromoteEdgeScrollToCursor(
                            heldForMs = heldForMs(),
                            hasWordOrigin = originIndex != null,
                            isInEdgeZone = SentenceStripEdgeScrollMath.targetAt(
                                x = change.position.x,
                                viewportWidth = viewportWidthPx.value.toFloat(),
                                zoneWidth = with(density) { EDGE_SCROLL_ZONE_WIDTH_DP.dp.toPx() },
                            ) != null,
                        )
                    ) {
                        mode = SentencePointerMode.CURSOR
                        gestureActive.value = true
                        setCursorAt(change.position)
                        startEdgeFrames()
                        change.consume()
                    }
                }
                SentencePointerMode.CURSOR -> {
                    setCursorAt(change.position)
                    change.consume()
                }
                SentencePointerMode.ALTERNATIVE -> {
                    updateAlternativeAt(change.position)
                    change.consume()
                }
            }
        }
        if (!released) {
            preview.value = null
            edgeScrollTarget.value = null
            gestureActive.value = false
        }
        edgeFrameJob?.cancel()
        edgeFrameJob = null
        }
    }
}

@Composable
private fun EdgeScrollAffordance(
    direction: EdgeScrollDirection,
    penetration: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val maxAlpha = SentenceStripEdgeScrollMath.MAX_AFFORDANCE_ALPHA
    Canvas(
        modifier = modifier
            .then(
                if (active) Modifier.semantics {
                    contentDescription = "Iaido edge zone direction=${direction.name.lowercase()} " +
                        "penetration=${penetration.coerceIn(0f, 1f)}"
                } else Modifier.clearAndSetSemantics { },
            )
            .width(EDGE_SCROLL_AFFORDANCE_WIDTH_DP.dp)
            .fillMaxSize(),
    ) {
        val transparent = KeyboardPalette.Accent.copy(alpha = 0f)
        val visible = KeyboardPalette.Accent.copy(alpha = maxAlpha)
        val colors = if (direction == EdgeScrollDirection.LEFT) {
            listOf(visible, transparent)
        } else {
            listOf(transparent, visible)
        }
        drawRect(brush = Brush.horizontalGradient(colors))
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val halfWidth = 3.5.dp.toPx()
        val halfHeight = 9.dp.toPx()
        val tipX = if (direction == EdgeScrollDirection.LEFT) centerX - halfWidth else centerX + halfWidth
        val innerX = if (direction == EdgeScrollDirection.LEFT) centerX + halfWidth else centerX - halfWidth
        val arrowColor = KeyboardPalette.Accent.copy(alpha = 0.64f + 0.28f * penetration.coerceIn(0f, 1f))
        drawLine(
            color = arrowColor,
            start = Offset(innerX, centerY - halfHeight),
            end = Offset(tipX, centerY),
            strokeWidth = 2.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = arrowColor,
            start = Offset(tipX, centerY),
            end = Offset(innerX, centerY + halfHeight),
            strokeWidth = 2.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun HistoryIcon(
    description: String,
    enabled: Boolean,
    isUndo: Boolean,
    preview: SentenceHistoryPreview?,
    onClick: () -> Unit,
) {
    val color = if (enabled) KeyboardPalette.MutedInk else KeyboardPalette.SubtleInk.copy(alpha = 0.45f)
    val enabledState = rememberUpdatedState(enabled)
    val actionState = rememberUpdatedState(onClick)
    val previewState = rememberUpdatedState(preview)
    var showPreview by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(36.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!enabledState.value) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var moved = false
                    var released = false
                    var cancelledRelease = false
                    val releasedBeforeHold = withTimeoutOrNull(HISTORY_PREVIEW_HOLD_MS) {
                        while (!released && !moved) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (dx * dx + dy * dy > slop * slop) moved = true
                            if (change.changedToUpIgnoreConsumed()) {
                                released = true
                                cancelledRelease = change.isConsumed
                            }
                        }
                        released
                    }
                    if (releasedBeforeHold == true) {
                        if (!cancelledRelease) actionState.value()
                        return@awaitEachGesture
                    }
                    if (releasedBeforeHold != null || moved || !enabledState.value) return@awaitEachGesture

                    showPreview = true
                    try {
                        while (!released) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                released = true
                                val cancelled = change.isConsumed
                                change.consume()
                                if (!cancelled && enabledState.value) actionState.value()
                            }
                        }
                    } finally {
                        showPreview = false
                    }
                }
            }
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
                onClick {
                    if (!enabledState.value) false else {
                        actionState.value()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(
                if (isUndo) R.drawable.ic_undo_material else R.drawable.ic_redo_material,
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(color),
        )
        if (showPreview && previewState.value != null) {
            val density = LocalDensity.current
            Popup(alignment = Alignment.TopCenter, offset = with(density) { IntOffset(0, -76.dp.roundToPx()) }) {
                val historyPreview = previewState.value ?: return@Popup
                Column(
                    modifier = Modifier
                        .widthIn(max = 230.dp)
                        .background(KeyboardPalette.Deck, RoundedCornerShape(10.dp))
                        .border(1.dp, KeyboardPalette.Divider, RoundedCornerShape(10.dp))
                        .semantics {
                            contentDescription = "Iaido history preview action=${historyPreview.actionLabel} " +
                                "before=${historyPreview.beforeText} after=${historyPreview.afterText}"
                        }
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        historyPreview.actionLabel,
                        color = KeyboardPalette.Accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(historyPreview.beforeText.ifEmpty { "∅" }, color = KeyboardPalette.MutedInk, fontSize = 13.sp, maxLines = 1)
                        Text("  →  ", color = KeyboardPalette.SubtleInk, fontSize = 12.sp)
                        Text(historyPreview.afterText.ifEmpty { "∅" }, color = KeyboardPalette.PrimaryInk, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private const val HISTORY_PREVIEW_HOLD_MS = 380L
