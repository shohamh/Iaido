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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onUndo: (chipIndex: Int) -> Unit = {},
    onReplacementPreview: (ReplacementOption) -> Unit = {},
    onReplacementRelease: (ReplacementOption) -> Unit = {},
    onReplacementCancel: () -> Unit = {},
) {
    val ordered = if (rtl) chips.asReversed() else chips
    val chipSlotCount = ordered.maxOfOrNull { reelVisibleSlotCount(it.alternatives.size) }
        ?: reelVisibleSlotCount(0)
    val replacementSlotCount = if (replacementOptions.isEmpty()) 0 else reelVisibleSlotCount(replacementOptions.size)
    val visibleSlotCount = maxOf(chipSlotCount, replacementSlotCount)
    val viewportHeight = (REEL_STEP_DP * visibleSlotCount).dp
    // The outer strip's height is pinned to the maximum possible slot count so the strip
    // (and therefore the whole keyboard, which wraps its height around it) never grows or
    // shrinks at runtime as chips with different candidate counts appear and clear. Chips
    // and reel groups below still use the per-render `viewportHeight`/`visibleSlotCount` for
    // their own internal centering.
    val pinnedStripHeight = (REEL_STEP_DP * MAX_REEL_VISIBLE_SLOTS).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(pinnedStripHeight + 8.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = SUGGESTION_STRIP_DESCRIPTION },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (replacementOptions.isNotEmpty() && rtl) {
            ReplacementReelSlot(
                options = replacementOptions,
                rtl = rtl,
                viewportHeight = viewportHeight,
                onPreview = onReplacementPreview,
                onRelease = onReplacementRelease,
                onCancel = onReplacementCancel,
            )
        }
        ordered.forEachIndexed { index, chip ->
            SuggestionChipView(
                chip = chip,
                index = index,
                visibleSlotCount = visibleSlotCount,
                modifier = Modifier,
                onRelease = { candidate -> onRelease(index, candidate) },
                onUndo = { onUndo(index) },
            )
        }
        if (replacementOptions.isNotEmpty() && !rtl) {
            ReplacementReelSlot(
                options = replacementOptions,
                rtl = rtl,
                viewportHeight = viewportHeight,
                onPreview = onReplacementPreview,
                onRelease = onReplacementRelease,
                onCancel = onReplacementCancel,
            )
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
                        val shouldCommit = abs(dragY) >= thresholdPx || options.size == 1
                        isDragging = false
                        if (shouldCommit) onRelease(options[previewIndex]) else onCancel()
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

@Composable
private fun SuggestionChipView(
    chip: SuggestionChip,
    index: Int,
    visibleSlotCount: Int,
    modifier: Modifier,
    onRelease: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    val alternatives = chip.alternatives.ifEmpty { listOf(chip.word) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val stateKey = chip.id ?: index
    val reelOffset = remember(stateKey) { Animatable(0f) }
    var dragY by remember(stateKey) { mutableFloatStateOf(0f) }
    var isDragging by remember(stateKey) { mutableStateOf(false) }
    val reelStepPx = with(density) { REEL_STEP_DP.dp.toPx() }
    val dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
    val maxIndex = alternatives.lastIndex
    val minOffset = (chip.selectedIndex - maxIndex).toFloat()
    val maxOffset = chip.selectedIndex.toFloat()
    val viewportHeight = (REEL_STEP_DP * visibleSlotCount).dp
    val dragOffset = (dragY / reelStepPx).coerceIn(minOffset, maxOffset)
    val latestDragOffset = rememberUpdatedState(dragOffset)
    val renderedOffset = if (isDragging) dragOffset else reelOffset.value
    val displayedIndex = displayedReelIndex(chip.selectedIndex, renderedOffset, maxIndex)
    val currentWord = alternatives.getOrNull(displayedIndex).orEmpty()
    val shape = RoundedCornerShape(16.dp)
    val containerColor = if (chip.corrected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
    }
    val foregroundColor = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(chip.id, chip.word, chip.selectedIndex) {
        dragY = 0f
        isDragging = false
        reelOffset.snapTo(0f)
    }

    Row(
        modifier = modifier
            .widthIn(min = 104.dp, max = 184.dp)
            .width(160.dp)
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
            .pointerInput(chip.id, chip.word, chip.selectedIndex) {
                detectVerticalDragGestures(
                    onDragStart = {
                        scope.launch { reelOffset.stop() }
                        dragY = 0f
                        isDragging = true
                    },
                    onDragEnd = {
                        val releaseOffset = latestDragOffset.value
                        val shouldSelect = abs(dragY) >= dragThresholdPx && alternatives.size > 1
                        val targetIndex = displayedReelIndex(chip.selectedIndex, releaseOffset, maxIndex)
                        val targetOffset = if (shouldSelect) {
                            reelSettleOffset(targetIndex, chip.selectedIndex)
                        } else {
                            0f
                        }
                        isDragging = false
                        scope.launch {
                            reelOffset.snapTo(releaseOffset)
                            reelOffset.animateTo(
                                targetValue = targetOffset,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                            if (shouldSelect) {
                                onRelease(targetIndex)
                            }
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
                            ((renderedOffset - chip.selectedIndex + centerSlotOffset) * REEL_STEP_DP).dp.toPx()
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

private const val REEL_STEP_DP = 36f
private const val DRAG_THRESHOLD_DP = 12f
