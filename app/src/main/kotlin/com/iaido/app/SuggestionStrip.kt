package com.iaido.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iaido.core.recognition.SuggestionChip
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun SuggestionStrip(
    chips: List<SuggestionChip>,
    rtl: Boolean,
    onRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onUndo: (chipIndex: Int) -> Unit = {},
) {
    val ordered = if (rtl) chips.asReversed() else chips
    val visibleSlotCount = ordered.maxOfOrNull { reelVisibleSlotCount(it.alternatives.size) }
        ?: reelVisibleSlotCount(0)
    val viewportHeight = (REEL_STEP_DP * visibleSlotCount).dp
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(viewportHeight + 16.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(ordered, key = { index, chip -> "${chip.id ?: index}" }) { index, chip ->
            SuggestionChipView(
                chip = chip,
                index = index,
                visibleSlotCount = visibleSlotCount,
                modifier = Modifier,
                onRelease = { candidate -> onRelease(index, candidate) },
                onUndo = { onUndo(index) },
            )
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
    val reelOffset = remember(chip.id, chip.word) { Animatable(0f) }
    var dragX by remember(chip.id, chip.word) { mutableFloatStateOf(0f) }
    var dragY by remember(chip.id, chip.word) { mutableFloatStateOf(0f) }
    var isDragging by remember(chip.id, chip.word) { mutableStateOf(false) }
    val reelStepPx = with(density) { REEL_STEP_DP.dp.toPx() }
    val dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
    val undoThresholdPx = with(density) { UNDO_THRESHOLD_DP.dp.toPx() }
    val maxIndex = alternatives.lastIndex
    val maxUpwardOffset = -chip.selectedIndex.toFloat()
    val maxDownwardOffset = (maxIndex - chip.selectedIndex).toFloat()
    val viewportHeight = (REEL_STEP_DP * visibleSlotCount).dp
    val dragOffset = (dragY / reelStepPx).coerceIn(maxUpwardOffset, maxDownwardOffset)
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
        dragX = 0f
        dragY = 0f
        isDragging = false
        reelOffset.snapTo(0f)
    }

    Row(
        modifier = modifier
            .widthIn(min = 104.dp, max = 184.dp)
            .height(viewportHeight)
            .semantics {
                contentDescription = buildString {
                    append("Iaido suggestion $index: $currentWord")
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
                detectDragGestures(
                    onDragStart = {
                        scope.launch { reelOffset.stop() }
                        dragX = 0f
                        dragY = 0f
                        isDragging = true
                    },
                    onDragEnd = {
                        val releaseOffset = dragOffset
                        val shouldUndo = dragX <= -undoThresholdPx && dragY <= -undoThresholdPx
                        val shouldSelect = !shouldUndo && abs(dragY) >= dragThresholdPx && alternatives.size > 1
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
                            if (shouldUndo) onUndo()
                            if (shouldSelect) onRelease(targetIndex)
                            reelOffset.snapTo(0f)
                        }
                        dragX = 0f
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
                        dragX = 0f
                        dragY = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        translationY = with(density) {
                            (renderedOffset * REEL_STEP_DP).dp.toPx()
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

private const val REEL_STEP_DP = 24f
private const val DRAG_THRESHOLD_DP = 12f
private const val UNDO_THRESHOLD_DP = 24f
