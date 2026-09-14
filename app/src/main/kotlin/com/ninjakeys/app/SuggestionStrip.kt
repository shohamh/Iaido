package com.ninjakeys.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ninjakeys.core.recognition.SuggestionChip
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun SuggestionStrip(
    chips: List<SuggestionChip>,
    rtl: Boolean,
    onRelease: (chipIndex: Int, candidateIndex: Int) -> Unit = { _, _ -> },
    onUndo: (chipIndex: Int) -> Unit = {},
) {
    val ordered = if (rtl) chips.asReversed() else chips
    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        itemsIndexed(ordered, key = { index, chip -> "${chip.id ?: index}" }) { index, chip ->
            SuggestionChipView(
                chip = chip,
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
    modifier: Modifier,
    onRelease: (Int) -> Unit,
    onUndo: () -> Unit,
) {
    var dragX by remember(chip.word) { mutableStateOf(0f) }
    var dragY by remember(chip.word) { mutableStateOf(0f) }
    val alternatives = chip.alternatives
    val density = LocalDensity.current
    val reelStepPx = with(density) { REEL_STEP_DP.dp.toPx() }
    val dragThresholdPx = with(density) { DRAG_THRESHOLD_DP.dp.toPx() }
    val undoThresholdPx = with(density) { UNDO_THRESHOLD_DP.dp.toPx() }
    val displayedIndex = if (alternatives.isEmpty()) 0 else {
        (chip.selectedIndex - (dragY / reelStepPx).roundToInt()).coerceIn(0, alternatives.lastIndex)
    }

    val current = alternatives.getOrElse(displayedIndex) { chip.word }
    val previous = alternatives.getOrNull(displayedIndex - 1)
    val next = alternatives.getOrNull(displayedIndex + 1)

    Row(
        modifier = modifier
            .padding(2.dp)
            .border(
                width = 1.dp,
                color = if (chip.corrected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            .background(
                if (chip.corrected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .pointerInput(chip.word) {
                detectDragGestures(
                    onDragStart = {
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        if (dragX <= -undoThresholdPx && dragY <= -undoThresholdPx) {
                            onUndo()
                        } else if (abs(dragY) >= dragThresholdPx) {
                            onRelease(displayedIndex)
                        }
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                    onDrag = { change, amount ->
                        dragX += amount.x
                        dragY += amount.y
                    },
                )
            },
    ) {
        Column {
            previous?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)) }
            Text(current)
            next?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)) }
        }
    }
}

private const val REEL_STEP_DP = 48f
private const val DRAG_THRESHOLD_DP = 12f
private const val UNDO_THRESHOLD_DP = 24f
