package com.iaido.app

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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.math.abs
import kotlin.math.max

private const val CURSOR_HOLD_TIMEOUT_MS = 360L

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
    val layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    val previewState = remember(state.sentenceStart) { mutableStateOf<SentenceStripPreview?>(null) }
    var preview by previewState
    val alternativeOverrides = remember { mutableStateMapOf<String, SentenceWordAlternativeOverride>() }
    val currentLayouts = remember { mutableStateMapOf<String, TextLayoutResult>() }
    val edgeScrollTargetState = remember { mutableStateOf<EdgeScrollTarget?>(null) }
    val gestureActiveState = remember { mutableStateOf(false) }
    var lastEdgeDirection by remember { mutableStateOf(EdgeScrollDirection.RIGHT) }
    var viewportCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val measuredLanes = remember(state.words, currentStyle, alternativeStyle, textMeasurer, density) {
        state.words.map { word ->
            val currentLayout = textMeasurer.measure(word.text, currentStyle, maxLines = 1, softWrap = false)
            val upperLayout = word.above?.let {
                textMeasurer.measure(it, alternativeStyle, maxLines = 1, softWrap = false)
            }
            val lowerLayout = word.below?.let {
                textMeasurer.measure(it, alternativeStyle, maxLines = 1, softWrap = false)
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
    val separatorWidthsPx = remember(separators, currentStyle, textMeasurer) {
        separators.map { textMeasurer.measure(it, currentStyle, maxLines = 1, softWrap = false).size.width.toFloat() }
    }
    val metrics = remember(measuredLanes, separatorWidthsPx, rtl, viewportWidthPx, density) {
        SentenceStripGeometry.create(
            words = measuredLanes.map { it.second },
            gapWidthsPx = separatorWidthsPx,
            viewportWidthPx = viewportWidthPx.toFloat(),
            isRtl = rtl,
            addedGapPx = with(density) { SentenceStripGeometry.WORD_GAP_CSS_PX.dp.toPx() },
        )
    }

    LaunchedEffect(state.words) {
        val wordsById = state.words.associateBy(SentenceStripWord::id)
        alternativeOverrides.toMap().forEach { (id, override) ->
            if (wordsById[id]?.text != override.selectedText) alternativeOverrides.remove(id)
        }
    }
    val displayWords = state.words.map { word ->
        alternativeOverrides[word.id]?.apply(word) ?: word
    }

    LaunchedEffect(state.selectionStart, state.words, metrics, gestureActiveState.value) {
        if (gestureActiveState.value) return@LaunchedEffect
        val focused = state.words.indexOfFirst { word ->
            state.selectionStart in word.start..word.endExclusive
        }
        if (focused >= 0) {
            val targetPx = metrics.focusScrollOffset(focused)
            scrollState.animateScrollTo(
                targetPx.toInt().coerceAtLeast(0),
                tween(durationMillis = 180, easing = FastOutSlowInEasing),
            )
        }
    }

    val liveState = rememberUpdatedState(state.copy(words = displayWords))
    val liveMetrics = rememberUpdatedState(metrics)
    val liveActions = rememberUpdatedState(actions)
    val liveViewportCoordinates = rememberUpdatedState(viewportCoordinates)
    val liveRowCoordinates = rememberUpdatedState(rowCoordinates)
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
                HistoryIcon(
                    description = "Iaido undo",
                    enabled = state.canUndo,
                    isUndo = true,
                    preview = state.undoPreview,
                    onClick = { actions?.undo() },
                )
                HistoryIcon(
                    description = "Iaido redo",
                    enabled = state.canRedo,
                    isUndo = false,
                    preview = state.redoPreview,
                    onClick = { actions?.redo() },
                )
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
                        viewport = liveViewportCoordinates,
                        row = liveRowCoordinates,
                        layouts = currentLayouts,
                        alternativeOverrides = alternativeOverrides,
                        scrollState = scrollState,
                        density = density,
                        viewportWidthPx = rememberUpdatedState(viewportWidthPx),
                        edgeScrollTarget = edgeScrollTargetState,
                        gestureActive = gestureActiveState,
                        rtl = rtl,
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
                        val laneWidth = with(density) { measured.laneWidthPx.toDp() }
                        val selected = wordPreview?.takeIf { it.wordIndex == index }
                        val split = replacementPreview?.takeIf {
                            it.isSplit && it.sourceWordIndices.singleOrNull() == index
                        }
                        val deleting = deletionPreview?.wordRange?.contains(index) == true
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
                    if (trailingText.isNotEmpty()) {
                        Text(
                            text = trailingText,
                            color = KeyboardPalette.PrimaryInk,
                            style = currentStyle,
                            maxLines = 1,
                            softWrap = false,
                        )
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
                        if (lastEdgeDirection == EdgeScrollDirection.LEFT) Alignment.CenterStart
                        else Alignment.CenterEnd,
                    ),
                )
                replacementPreview?.takeIf(SentenceReplacementPreview::isJoin)?.let { join ->
                    JoinPreviewOverlay(
                        preview = join,
                        state = state,
                        geometry = metrics,
                        viewportCoordinates = viewportCoordinates,
                        rowCoordinates = rowCoordinates,
                        scrollValue = scrollState.value,
                        density = density,
                    )
                }
                deletionPreview?.let { deletion ->
                    DeletionPreviewOverlay(
                        preview = deletion,
                        geometry = metrics,
                        viewportCoordinates = viewportCoordinates,
                        rowCoordinates = rowCoordinates,
                        scrollValue = scrollState.value,
                        density = density,
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
    viewportCoordinates: LayoutCoordinates?,
    rowCoordinates: LayoutCoordinates?,
    scrollValue: Int,
    density: Density,
) {
    val first = preview.sourceWordIndices.minOrNull() ?: return
    val last = preview.sourceWordIndices.maxOrNull() ?: return
    val bounds = geometry.joinUnion(first, last)
    val leftPx = stripViewportX(bounds.left, viewportCoordinates, rowCoordinates, scrollValue)
    val rightPx = stripViewportX(bounds.right, viewportCoordinates, rowCoordinates, scrollValue)
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
            .offset(x = left, y = 6.dp)
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
                    .background(KeyboardPalette.Accent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text("JOIN", color = KeyboardPalette.Page, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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
    viewportCoordinates: LayoutCoordinates?,
    rowCoordinates: LayoutCoordinates?,
    scrollValue: Int,
    density: Density,
) {
    val bounds = geometry.joinUnion(preview.wordRange.first, preview.wordRange.last)
    val leftPx = stripViewportX(bounds.left, viewportCoordinates, rowCoordinates, scrollValue)
    val rightPx = stripViewportX(bounds.right, viewportCoordinates, rowCoordinates, scrollValue)
    val left = with(density) { minOf(leftPx, rightPx).toDp() }
    val width = with(density) { abs(rightPx - leftPx).toDp() }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .offset(x = left, y = 6.dp)
            .width(width)
            .height(66.dp)
            .background(KeyboardPalette.Delete.copy(alpha = 0.12f), shape)
            .border(1.5.dp, KeyboardPalette.Delete, shape)
            .semantics {
                contentDescription = "Iaido deletion preview startWord=${preview.wordRange.first} " +
                    "endWord=${preview.wordRange.last} start=${preview.sourceStart} " +
                    "end=${preview.sourceEndExclusive}"
            },
    ) {
        Text(
            text = "DELETE",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .background(KeyboardPalette.Delete, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp),
            color = KeyboardPalette.Page,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun stripViewportX(
    contentX: Float,
    viewport: LayoutCoordinates?,
    row: LayoutCoordinates?,
    scrollValue: Int,
): Float = if (viewport != null && row != null && viewport.isAttached && row.isAttached) {
    viewport.localPositionOf(row, Offset(contentX, 0f)).x
} else {
    contentX - scrollValue
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
    splitPreviewWords: List<String>?,
    isDeleting: Boolean,
) {
    val hasCaret = cursorOffset != null
    Column(
        modifier = Modifier.width(laneWidth),
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
    viewport: State<LayoutCoordinates?>,
    row: State<LayoutCoordinates?>,
    layouts: Map<String, TextLayoutResult>,
    alternativeOverrides: MutableMap<String, SentenceWordAlternativeOverride>,
    scrollState: ScrollState,
    density: Density,
    viewportWidthPx: State<Int>,
    edgeScrollTarget: MutableState<EdgeScrollTarget?>,
    gestureActive: MutableState<Boolean>,
    rtl: Boolean,
): Modifier = pointerInput(Unit) {
    coroutineScope {
        val gestureScope = this
        val dragSlop = with(density) { 7.dp.toPx() }
        awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val frozenState = state.value
        val frozenGeometry = geometry.value
        val viewportCoordinates = viewport.value
        val rowCoordinates = row.value

        fun contentPosition(position: Offset): Offset {
            return if (viewportCoordinates != null && rowCoordinates != null &&
                viewportCoordinates.isAttached && rowCoordinates.isAttached
            ) {
                rowCoordinates.localPositionOf(viewportCoordinates, position)
            } else {
                Offset(position.x + scrollState.value, position.y)
            }
        }

        fun selectionAt(position: Offset): Int {
            val content = contentPosition(position)
            val wordIndex = frozenGeometry.wordAt(content.x)
            if (wordIndex != null) {
                val word = frozenState.words.getOrNull(wordIndex) ?: return frozenState.selectionStart
                val layout = layouts[word.id]
                val rawOffset = if (layout != null) {
                    val laneLeft = frozenGeometry.words[wordIndex].laneBounds.left
                    layout.getOffsetForPosition(
                        Offset(
                            x = (content.x - laneLeft).coerceIn(0f, layout.size.width.toFloat()),
                            y = layout.size.height / 2f,
                        ),
                    )
                } else {
                    frozenGeometry.cursorOffsetAt(wordIndex, content.x)?.minus(word.start) ?: 0
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

        val originIndex = frozenGeometry.wordAt(contentPosition(down.position).x)
        var mode = SentencePointerMode.PENDING
        var released = false
        var latestPosition = down.position
        var lastEdgeFrameNanos = 0L
        var lastCursorSelection: Int? = null
        var activePreview: SentenceStripPreview? = null
        var edgeFrameJob: Job? = null

        fun setCursorAt(position: Offset) {
            val selection = selectionAt(position)
            if (selection != lastCursorSelection) {
                actions.value?.setSelection(selection)
                lastCursorSelection = selection
            }
        }

        fun updateAlternativeAt(position: Offset) {
            val deletionRange = originIndex?.let { index ->
                frozenGeometry.deletionWordRange(index, contentPosition(position).x)
            }
            activePreview = if (deletionRange != null) {
                SentenceStripPreviewMath.deletion(frozenState, deletionRange)
            } else {
                originIndex?.let { index ->
                    SentenceStripPreviewMath.alternative(
                        frozenState,
                        index,
                        if (position.y < down.position.y) SentenceAlternativeSide.ABOVE
                        else SentenceAlternativeSide.BELOW,
                    )
                }
            }
            preview.value = activePreview
        }

        fun updateEdgeFrame(frameNanos: Long) {
            if (mode != SentencePointerMode.CURSOR && mode != SentencePointerMode.ALTERNATIVE) {
                edgeScrollTarget.value = null
                lastEdgeFrameNanos = 0L
                return
            }
            val viewportWidth = viewportWidthPx.value.toFloat()
            val target = SentenceStripEdgeScrollMath.targetAt(
                x = latestPosition.x,
                viewportWidth = viewportWidth,
                zoneWidth = with(density) { EDGE_SCROLL_ZONE_WIDTH_DP.dp.toPx() },
            )
            edgeScrollTarget.value = target
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
                        activePreview = SentenceStripPreviewMath.alternative(
                            frozenState,
                            originIndex,
                            if (change.position.y < down.position.y) SentenceAlternativeSide.ABOVE
                            else SentenceAlternativeSide.BELOW,
                        )
                        preview.value = activePreview
                        gestureActive.value = true
                        change.consume()
                    } else if (abs(dx) > dragSlop) {
                        mode = SentencePointerMode.SCROLL
                    }
                } else if (abs(dx) > dragSlop) {
                    mode = SentencePointerMode.SCROLL
                }
            }
        }

            if (!released && mode == SentencePointerMode.PENDING && originIndex != null) {
            mode = SentencePointerMode.CURSOR
            gestureActive.value = true
            setCursorAt(down.position)
        } else if (released && mode == SentencePointerMode.PENDING) {
            setCursorAt(down.position)
        }

        if (mode == SentencePointerMode.CURSOR || mode == SentencePointerMode.ALTERNATIVE) {
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
                        activePreview = SentenceStripPreviewMath.alternative(
                            frozenState,
                            originIndex,
                            if (change.position.y < down.position.y) SentenceAlternativeSide.ABOVE
                            else SentenceAlternativeSide.BELOW,
                        )
                        preview.value = activePreview
                        gestureActive.value = true
                        startEdgeFrames()
                        change.consume()
                    } else if (abs(dx) > dragSlop) {
                        mode = SentencePointerMode.SCROLL
                    }
                }
                SentencePointerMode.SCROLL -> Unit
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
        Canvas(Modifier.size(32.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.25f
            val direction = if (isUndo) -1f else 1f
            val startAngle = if (isUndo) 35f else 145f
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = 265f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
            val x = if (isUndo) center.x - radius else center.x + radius
            val y = center.y - radius * 0.45f
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x - direction * radius * 0.55f, y - radius * 0.45f),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x + direction * radius * 0.55f, y - radius * 0.45f),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
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
