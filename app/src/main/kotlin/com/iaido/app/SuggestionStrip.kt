package com.iaido.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.iaido.core.recognition.SuggestionChip
import com.iaido.core.recognition.ReplacementOption
import kotlinx.coroutines.launch
import kotlin.math.abs

const val SUGGESTION_STRIP_DESCRIPTION = "Iaido suggestion strip"

@Composable
fun SuggestionStrip(
    chips: List<SuggestionChip>,
    rtl: Boolean,
    replacementOptions: List<ReplacementOption> = emptyList(),
    liveReplacementOptionIds: Set<String> = emptySet(),
    onRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onUndo: (chipIndex: Int) -> Unit = {},
    onReplacementPreview: (ReplacementOption) -> Unit = {},
    onReplacementRelease: (ReplacementOption) -> Unit = {},
    onReplacementCancel: () -> Unit = {},
) {
    val ordered = if (rtl) chips.asReversed() else chips
    val chipSlotCount = ordered.maxOfOrNull {
        reelVisibleSlotCount(reelCandidatesForDisplay(it).size)
    }
        ?: reelVisibleSlotCount(0)
    // The edge-anchored slot only ever hides a join-shaped option once it is no longer part of
    // the live SwipeTypingCoordinator transaction (a history-derived join, or a live join whose
    // transaction has since moved on) -- Task 8's per-chip reel takes over showing it there. A
    // still-live join stays visible here even once a matching chip pair also exists (chips can
    // populate earlier than the transaction resolves; provenance, not chip-attachment, is what
    // distinguishes the two cases -- see SuggestionStrip.kt task 9 report for why a chip-match
    // based filter doesn't work).
    val splitOrLiveReplacementOptions = remember(replacementOptions, liveReplacementOptionIds) {
        replacementOptions.filterNot { option ->
            option.sourceWords.size > 1 && option.replacementWords.size == 1 &&
                option.id !in liveReplacementOptionIds
        }
    }
    val replacementSlotCount = if (splitOrLiveReplacementOptions.isEmpty()) {
        0
    } else {
        reelVisibleSlotCount(splitOrLiveReplacementOptions.size)
    }
    val visibleSlotCount = maxOf(chipSlotCount, replacementSlotCount)
    val viewportHeight = (REEL_STEP_DP * visibleSlotCount).dp
    // The outer strip's height is pinned to the maximum possible slot count so the strip
    // (and therefore the whole keyboard, which wraps its height around it) never grows or
    // shrinks at runtime as chips with different candidate counts appear and clear. Chips
    // and reel groups below still use the per-render `viewportHeight`/`visibleSlotCount` for
    // their own internal centering.
    val pinnedStripHeight = (REEL_STEP_DP * MAX_REEL_VISIBLE_SLOTS).dp
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val bodyTextStyleKey = bodyStyle

    LaunchedEffect(ordered.map { it.id }, rtl) {
        if (ordered.isEmpty()) return@LaunchedEffect
        val leadingExtraItem = splitOrLiveReplacementOptions.isNotEmpty() && rtl
        val targetIndex = (if (leadingExtraItem) 1 else 0) + autoScrollTargetIndex(ordered.size, rtl)
        listState.animateScrollToItem(targetIndex)
    }

    val reservedWidths = remember(ordered, bodyTextStyleKey) {
        ordered.map { chip ->
            val candidates = reelCandidatesForDisplay(chip)
            val selectedIndex = reelSelectedIndexForDisplay(chip, candidates)
            val word = candidates[selectedIndex]
            val measuredWidthPx = textMeasurer.measure(text = word, style = bodyStyle).size.width
            chipReservedWidthDp(with(density) { measuredWidthPx.toDp() }.value)
        }
    }
    val joinAttachments = remember(chips, replacementOptions) {
        attachJoinCandidates(chips, replacementOptions)
    }
    val orderedIdToIndex = remember(ordered) {
        ordered.mapIndexed { i, orderedChip -> (orderedChip.id ?: -1) to i }.toMap()
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(pinnedStripHeight + 8.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = SUGGESTION_STRIP_DESCRIPTION },
        horizontalArrangement = Arrangement.spacedBy(REEL_ITEM_SPACING_DP.dp),
    ) {
        if (splitOrLiveReplacementOptions.isNotEmpty() && rtl) {
            item(key = "replacement-slot") {
                ReplacementReelSlot(
                    options = splitOrLiveReplacementOptions,
                    rtl = rtl,
                    viewportHeight = viewportHeight,
                    onPreview = onReplacementPreview,
                    onRelease = onReplacementRelease,
                    onCancel = onReplacementCancel,
                )
            }
        }
        itemsIndexed(ordered, key = { _, chip -> chip.id ?: -1 }) { index, chip ->
            val chipId = chip.id ?: -1
            val trailingJoin = joinAttachments.firstOrNull { it.firstChipId == chipId }
            val leadingJoin = joinAttachments.firstOrNull { it.lastChipId == chipId }
            val join = trailingJoin ?: leadingJoin
            // The other chip this join spans to, if any -- looked up by its actual rendered
            // position so the overlap direction is correct even when `ordered` is RTL-reversed.
            val otherChipIndex = when {
                trailingJoin != null -> orderedIdToIndex[trailingJoin.lastChipId]
                leadingJoin != null -> orderedIdToIndex[leadingJoin.firstChipId]
                else -> null
            }
            // With no join at this boundary, an oversized *same-chip* alternative still needs a
            // neighbor to grow into: prefer the next (more-recently-typed-side) chip when one
            // exists, otherwise the previous one, otherwise none (it just ellipsizes).
            val growsForward = when {
                otherChipIndex != null -> otherChipIndex > index
                index + 1 < reservedWidths.size -> true
                else -> false
            }
            val neighborReservedWidthDp = when {
                otherChipIndex != null -> reservedWidths.getOrNull(otherChipIndex)
                growsForward -> reservedWidths.getOrNull(index + 1)
                else -> reservedWidths.getOrNull(index - 1)
            }
            SuggestionChipView(
                chip = chip,
                index = index,
                visibleSlotCount = visibleSlotCount,
                modifier = Modifier.animateItem(),
                reservedWidthDp = reservedWidths[index],
                neighborReservedWidthDp = neighborReservedWidthDp,
                growsForward = growsForward,
                joinCandidate = join?.option,
                onRelease = { candidate -> onRelease(index, candidate) },
                onUndo = { onUndo(index) },
                onReplacementPreview = onReplacementPreview,
                onReplacementRelease = onReplacementRelease,
                onReplacementCancel = onReplacementCancel,
            )
        }
        if (splitOrLiveReplacementOptions.isNotEmpty() && !rtl) {
            item(key = "replacement-slot") {
                ReplacementReelSlot(
                    options = splitOrLiveReplacementOptions,
                    rtl = rtl,
                    viewportHeight = viewportHeight,
                    onPreview = onReplacementPreview,
                    onRelease = onReplacementRelease,
                    onCancel = onReplacementCancel,
                )
            }
        }
    }
}

private val ReplacementReelSelectionSaver = Saver<ReplacementReelSelection, String>(
    save = { selection -> selection.selectedOptionId.orEmpty() },
    restore = { selectedOptionId ->
        ReplacementReelSelection(initialSelectedOptionId = selectedOptionId.ifEmpty { null })
    },
)

@Composable
private fun ReplacementReelSlot(
    options: List<ReplacementOption>,
    rtl: Boolean,
    viewportHeight: androidx.compose.ui.unit.Dp,
    onPreview: (ReplacementOption) -> Unit,
    onRelease: (ReplacementOption) -> Unit,
    onCancel: () -> Unit,
) {
    val selection = rememberSaveable(saver = ReplacementReelSelectionSaver) {
        ReplacementReelSelection()
    }
    selection.updateOptions(options)
    val selectedIndex = selection.selectedIndex()
    ReplacementReelGroup(
        options = options,
        selectedIndex = selectedIndex,
        rtl = rtl,
        viewportHeight = viewportHeight,
        onPreview = { option ->
            selection.preview(option)
            onPreview(option)
        },
        onRelease = { option ->
            selection.release(option)
            onRelease(option)
        },
        onCancel = {
            selection.cancel()
            onCancel()
        },
    )
}

@Composable
private fun ReplacementReelGroup(
    options: List<ReplacementOption>,
    selectedIndex: Int,
    rtl: Boolean,
    viewportHeight: androidx.compose.ui.unit.Dp,
    onPreview: (ReplacementOption) -> Unit,
    onRelease: (ReplacementOption) -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val stateKey = options.joinToString { it.id }
    var dragY by remember(stateKey, selectedIndex) { mutableFloatStateOf(0f) }
    var isDragging by remember(stateKey, selectedIndex) { mutableStateOf(false) }
    val stepPx = with(density) { REEL_STEP_DP.dp.toPx() }
    val thresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
    val previewIndex = displayedReelIndex(selectedIndex, dragY / stepPx, options.lastIndex)
    val option = options[previewIndex]
    val layout = replacementReelLayout(option, rtl)
    val description = replacementReelDescription(option, previewIndex, options.size)
    val shape = RoundedCornerShape(16.dp)

    LaunchedEffect(isDragging, previewIndex, stateKey) {
        if (isDragging) onPreview(option)
    }

    Row(
        modifier = Modifier
            .width((160 * layout.widthSlots).dp)
            .height(viewportHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = "Swipe vertically to preview; release to commit"
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(stateKey, selectedIndex) {
                detectVerticalDragGestures(
                    onDragStart = {
                        dragY = 0f
                        isDragging = true
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragY += amount
                    },
                    onDragEnd = {
                        // `previewIndex` above is a composable-scope `val`, recomputed only when
                        // this function recomposes -- but this whole `pointerInput(stateKey,
                        // selectedIndex)` block (and every lambda inside it, onDragEnd included)
                        // is only re-created when those keys change, not on every recomposition
                        // driven by `dragY` changing during the drag itself. So `previewIndex` as
                        // captured here is stale: it reflects whatever it was when this gesture
                        // detector was (re)established (typically 0, from before the drag ever
                        // moved), not the index actually shown to the user at release time.
                        // Recomputing it fresh from `dragY` (a live State read, unlike the `val`
                        // above) is what makes the committed option match the previewed one --
                        // mirroring the `rememberUpdatedState` pattern SuggestionChipView already
                        // uses for the same reason (see its `latestDragOffset`).
                        val releasePreviewIndex = displayedReelIndex(selectedIndex, dragY / stepPx, options.lastIndex)
                        val shouldCommit = abs(dragY) >= thresholdPx || options.size == 1
                        isDragging = false
                        if (shouldCommit) onRelease(options[releasePreviewIndex]) else onCancel()
                        dragY = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        dragY = 0f
                        onCancel()
                    },
                )
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        layout.renderedWords.forEach { word ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    text = word,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun reelCandidatesForDisplay(chip: SuggestionChip): List<String> =
    (chip.alternatives + chip.word).distinct()

internal fun reelSelectedIndexForDisplay(
    chip: SuggestionChip,
    candidates: List<String>,
): Int = candidates.indexOf(chip.word).takeIf { it >= 0 }
    ?: chip.selectedIndex.coerceIn(0, candidates.lastIndex.coerceAtLeast(0))

@Composable
private fun SuggestionChipView(
    chip: SuggestionChip,
    index: Int,
    visibleSlotCount: Int,
    modifier: Modifier,
    reservedWidthDp: Float,
    neighborReservedWidthDp: Float?,
    growsForward: Boolean,
    joinCandidate: ReplacementOption?,
    onRelease: (Int) -> Unit,
    onUndo: () -> Unit,
    onReplacementPreview: (ReplacementOption) -> Unit,
    onReplacementRelease: (ReplacementOption) -> Unit,
    onReplacementCancel: () -> Unit,
) {
    val baseAlternatives = reelCandidatesForDisplay(chip)
    val alternatives = if (joinCandidate != null) {
        baseAlternatives + joinCandidate.replacementWords.joinToString(" ")
    } else {
        baseAlternatives
    }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val stateKey = chip.id ?: index
    val reelOffset = remember(stateKey) { Animatable(0f) }
    var dragY by remember(stateKey) { mutableFloatStateOf(0f) }
    var isDragging by remember(stateKey) { mutableStateOf(false) }
    val reelStepPx = with(density) { REEL_STEP_DP.dp.toPx() }
    val dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
    val maxIndex = alternatives.lastIndex
    val selectedIndex = reelSelectedIndexForDisplay(chip, baseAlternatives)
    val minOffset = (selectedIndex - maxIndex).toFloat()
    val maxOffset = selectedIndex.toFloat()
    val viewportHeight = (REEL_STEP_DP * visibleSlotCount).dp
    val dragOffset = (dragY / reelStepPx).coerceIn(minOffset, maxOffset)
    val latestDragOffset = rememberUpdatedState(dragOffset)
    val renderedOffset = if (isDragging) dragOffset else reelOffset.value
    val displayedIndex = displayedReelIndex(selectedIndex, renderedOffset, maxIndex)
    val currentWord = alternatives.getOrNull(displayedIndex).orEmpty()
    val shape = RoundedCornerShape(16.dp)
    val containerColor = if (chip.corrected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
    }
    val foregroundColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(chip.id, chip.word, selectedIndex) {
        dragY = 0f
        isDragging = false
        reelOffset.snapTo(0f)
    }

    val displayedWord = alternatives.getOrNull(displayedIndex).orEmpty()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val displayedWordWidthDp = remember(displayedWord) {
        val widthPx = textMeasurer.measure(text = displayedWord, style = bodyStyle).size.width
        with(density) { widthPx.toDp() }.value
    }
    val drawWidthDp = overflowDrawWidthDp(displayedWordWidthDp, reservedWidthDp, neighborReservedWidthDp)

    Row(
        modifier = modifier
            .overflowGrow(reservedWidthDp, drawWidthDp, growsForward)
            .height(viewportHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = "Iaido suggestion $index"
                stateDescription = buildString {
                    append(currentWord)
                    if (alternatives.size > 1) append("; option ${displayedIndex + 1} of ${alternatives.size}; swipe vertically to change")
                }
            }
            .clip(shape)
            .background(containerColor)
            .border(
                width = if (chip.corrected) 1.5.dp else 1.dp,
                color = if (chip.corrected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .pointerInput(chip.id, chip.word, selectedIndex) {
                detectVerticalDragGestures(
                    onDragStart = {
                        scope.launch { reelOffset.stop() }
                        dragY = 0f
                        isDragging = true
                    },
                    onDragEnd = {
                        val releaseOffset = latestDragOffset.value
                        val shouldSelect = abs(dragY) >= dragThresholdPx && alternatives.size > 1
                        val targetIndex = displayedReelIndex(selectedIndex, releaseOffset, maxIndex)
                        val targetOffset = if (shouldSelect) {
                            reelSettleOffset(targetIndex, selectedIndex)
                        } else {
                            0f
                        }
                        isDragging = false
                        if (shouldSelect) {
                            if (joinCandidate != null && targetIndex == baseAlternatives.size) {
                                onReplacementRelease(joinCandidate)
                            } else {
                                onRelease(targetIndex)
                            }
                        }
                        scope.launch {
                            reelOffset.snapTo(releaseOffset)
                            reelOffset.animateTo(
                                targetValue = targetOffset,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            reelOffset.snapTo(0f)
                        }
                        dragY = 0f
                    },
                    onDragCancel = {
                        val releaseOffset = dragOffset
                        isDragging = false
                        scope.launch {
                            reelOffset.snapTo(releaseOffset)
                            reelOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                            reelOffset.snapTo(0f)
                        }
                        dragY = 0f
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragY += amount
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                    .fillMaxSize()
                .clipToBounds()
                .padding(horizontal = 14.dp),
        ) {
            val centerSlotOffset = reelCenterSlotOffset(visibleSlotCount)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = with(density) {
                            ((renderedOffset - selectedIndex + centerSlotOffset) * REEL_STEP_DP).dp.toPx()
                        }
                    },
            ) {
                alternatives.forEachIndexed { candidateIndex, candidate ->
                    val distance = abs(candidateIndex - displayedIndex)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(REEL_STEP_DP.dp)
                            .background(
                                if (candidateIndex == displayedIndex) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                } else {
                                    Color.Transparent
                                },
                            ),
                    ) {
                        Text(
                            text = candidate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            color = foregroundColor.copy(alpha = when (distance) {
                                0 -> 1f
                                1 -> 0.48f
                                else -> 0.2f
                            }),
                            style = if (distance == 0) {
                                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            maxLines = 1,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Brush.verticalGradient(listOf(containerColor, containerColor.copy(alpha = 0f)))),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .align(androidx.compose.ui.Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(containerColor.copy(alpha = 0f), containerColor))),
            )
        }
    }
}

/**
 * Measures [content] at [drawWidthDp] (which may exceed [reservedWidthDp]) but reports
 * [reservedWidthDp] as this element's layout size, so siblings in the parent layout are never
 * disturbed by the overflow. When [drawWidthDp] is wider than [reservedWidthDp], the content is
 * drawn at an elevated z-index so it paints over the neighbor it overlaps instead of underneath
 * it, anchored on the side given by [growsForward] (true: overflow extends past the element's
 * trailing/end edge; false: past its leading/start edge).
 */
private fun Modifier.overflowGrow(
    reservedWidthDp: Float,
    drawWidthDp: Float,
    growsForward: Boolean,
): Modifier = this
    .zIndex(if (drawWidthDp > reservedWidthDp) 1f else 0f)
    .layout { measurable, constraints ->
        val reservedWidthPx = reservedWidthDp.dp.roundToPx()
        val drawWidthPx = drawWidthDp.dp.roundToPx()
        val placeable = measurable.measure(
            constraints.copy(minWidth = drawWidthPx, maxWidth = drawWidthPx),
        )
        layout(reservedWidthPx, placeable.height) {
            val x = if (growsForward) 0 else reservedWidthPx - drawWidthPx
            placeable.place(x, 0)
        }
    }

private const val REEL_STEP_DP = 28f
private const val DRAG_THRESHOLD_DP = 12f
