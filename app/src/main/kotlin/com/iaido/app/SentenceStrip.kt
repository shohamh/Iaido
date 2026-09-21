package com.iaido.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.max

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
    var viewportWidthPx by remember(state.sentenceText) { mutableIntStateOf(0) }
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

    LaunchedEffect(state.selectionStart, state.words, metrics) {
        val focused = state.words.indexOfFirst { word ->
            state.selectionStart in word.start..word.endExclusive
        }
        if (focused >= 0) {
            val targetPx = metrics.focusScrollOffset(focused)
            scrollState.animateScrollTo(targetPx.toInt().coerceAtLeast(0))
        }
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
                    text = "SWIPE A WORD TO CORRECT",
                    modifier = Modifier.weight(1f),
                    color = KeyboardPalette.SubtleInk,
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
                    onClick = { actions?.undo() },
                )
                HistoryIcon(
                    description = "Iaido redo",
                    enabled = state.canRedo,
                    isUndo = false,
                    onClick = { actions?.redo() },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(78.dp)
                    .onSizeChanged { viewportWidthPx = it.width },
            ) {
                Row(
                    modifier = Modifier.height(78.dp).horizontalScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    measuredLanes.forEachIndexed { index, (word, measured) ->
                        val laneWidth = with(density) { measured.laneWidthPx.toDp() }
                        SentenceWordLane(
                            word = word,
                            wordIndex = index,
                            laneWidth = laneWidth,
                            currentStyle = currentStyle,
                            alternativeStyle = alternativeStyle,
                            cursorOffset = (state.selectionStart - word.start)
                                .takeIf { it in 0..(word.endExclusive - word.start) },
                            onSelect = { actions?.setSelection(word.endExclusive) },
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
            }
        }
    }
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
        )
        var textLayout by remember(word.id, word.text) { mutableStateOf<TextLayoutResult?>(null) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clickable(onClick = onSelect)
                .semantics {
                    contentDescription = "Iaido sentence word index=$wordIndex start=${word.start} " +
                        "end=${word.endExclusive} deleting=false text=${word.text}"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = word.text,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    val layout = textLayout
                    if (hasCaret && layout != null) {
                        val localOffset = cursorOffset!!.coerceIn(0, word.text.length)
                        val caret = layout.getCursorRect(localOffset)
                        drawLine(
                            color = Color(0xFF8BCBD0),
                            start = androidx.compose.ui.geometry.Offset(caret.left, caret.top),
                            end = androidx.compose.ui.geometry.Offset(caret.left, caret.bottom),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                },
                color = if (hasCaret) KeyboardPalette.Accent else KeyboardPalette.PrimaryInk,
                style = currentStyle,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { textLayout = it },
            )
        }
        AlternativeText(
            text = word.below,
            description = "Iaido sentence alternative word=$wordIndex side=below",
            style = alternativeStyle,
        )
    }
}

@Composable
private fun AlternativeText(text: String?, description: String, style: TextStyle) {
    Box(
        modifier = Modifier.fillMaxWidth().height(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text = text,
                modifier = Modifier.semantics {
                    contentDescription = "$description text=$text"
                },
                color = KeyboardPalette.PrimaryInk.copy(alpha = 0.64f),
                style = style,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HistoryIcon(
    description: String,
    enabled: Boolean,
    isUndo: Boolean,
    onClick: () -> Unit,
) {
    val color = if (enabled) KeyboardPalette.MutedInk else KeyboardPalette.SubtleInk.copy(alpha = 0.45f)
    Canvas(
        modifier = Modifier
            .size(32.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            },
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.25f
        val direction = if (isUndo) -1f else 1f
        val startAngle = if (isUndo) 35f else 145f
        drawArc(
            color = color,
            startAngle = startAngle,
            sweepAngle = 265f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
        )
        val x = if (isUndo) center.x - radius else center.x + radius
        val y = center.y - radius * 0.45f
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(x, y),
            end = androidx.compose.ui.geometry.Offset(x - direction * radius * 0.55f, y - radius * 0.45f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(x, y),
            end = androidx.compose.ui.geometry.Offset(x + direction * radius * 0.55f, y - radius * 0.45f),
            strokeWidth = 1.6.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
