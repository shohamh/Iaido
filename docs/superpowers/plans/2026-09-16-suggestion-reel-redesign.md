# Suggestion Reel Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix commit latency, add auto-scroll/reflow animation, size chips to their word's actual width, and surface joined-word suggestions (e.g. "wh"+"at" -> "what") by scrolling either source chip past its last real alternative, instead of a separate edge-anchored reel.

**Architecture:** Extract all sizing/overflow/correlation math into pure, unit-testable functions first (`SuggestionReelMath.kt`, new `ReplacementJoinAttachment.kt`), then wire them into a rewritten `SuggestionStrip.kt` that uses `LazyRow` (for `animateItem()` reflow animation and `animateScrollToItem()` auto-follow) and a custom `Modifier.layout {}` primitive that lets one reel row visually paint wider than its reserved slot (elevated z-index, overlapping a neighbor) without disturbing sibling layout — the same primitive serves both an oversized same-chip alternative and an attached join candidate.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.11.00, includes stable `LazyItemScope.animateItem()`), JUnit 5 for `app`/`core-engine` unit tests, the existing `ImeScenario` UiAutomator-based connected-test DSL for `androidTest`.

**Spec:** [docs/superpowers/specs/2026-09-16-suggestion-reel-redesign-design.md](../specs/2026-09-16-suggestion-reel-redesign-design.md)

## Global Constraints

- Split options (`sourceWords.size == 1 && replacementWords.size > 1`) keep today's separate `ReplacementReelGroup` edge-slot rendering, unchanged. Only join options (`sourceWords.size > 1 && replacementWords.size == 1`) move to the new attached-reel-slot rendering.
- `SuggestionStrip`'s public parameters (`chips`, `rtl`, `replacementOptions`, `onRelease`, `onUndo`, `onReplacementPreview`, `onReplacementRelease`, `onReplacementCancel`) are unchanged — `SwipeTypingCoordinator.previewReplacement`/`releaseReplacement`/`cancelReplacement` plumbing is reused as-is.
- New sizing/spacing constants (exact dp values below) are starting points; the design doc explicitly calls out that they may need live tuning against the running keyboard, which is fine to do as a follow-up, not a blocker for this plan.

---

### Task 1: Chip reserved-width sizing math

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt`
- Test: `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt`

**Interfaces:**
- Produces: `internal fun chipReservedWidthDp(measuredTextWidthDp: Float): Float`, `internal const val CHIP_HORIZONTAL_PADDING_DP: Float`, `internal const val MIN_CHIP_WIDTH_DP: Float`, `internal const val MAX_CHIP_WIDTH_DP: Float`, `internal const val REEL_ITEM_SPACING_DP: Float` — consumed by Task 6 and Task 7.

- [ ] **Step 1: Write the failing tests**

Add to `SuggestionReelMathTest.kt`:

```kotlin
    @Test
    fun `reserved chip width adds padding around the measured word and has a usable minimum`() {
        assertEquals(56f, chipReservedWidthDp(measuredTextWidthDp = 10f))
        assertEquals(120f, chipReservedWidthDp(measuredTextWidthDp = 100f))
    }

    @Test
    fun `reserved chip width caps very long words so one chip cannot eat the whole strip`() {
        assertEquals(140f, chipReservedWidthDp(measuredTextWidthDp = 500f))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
Expected: FAIL with "Unresolved reference: chipReservedWidthDp"

- [ ] **Step 3: Add the constants and function**

Add to `SuggestionReelMath.kt` (alongside the existing `MAX_REEL_VISIBLE_SLOTS` constant):

```kotlin
internal const val CHIP_HORIZONTAL_PADDING_DP = 10f
internal const val MIN_CHIP_WIDTH_DP = 56f
internal const val MAX_CHIP_WIDTH_DP = 140f
internal const val REEL_ITEM_SPACING_DP = 8f

/** A chip's resting width: its selected word's measured width plus padding, clamped to a usable range. */
internal fun chipReservedWidthDp(measuredTextWidthDp: Float): Float =
    (measuredTextWidthDp + CHIP_HORIZONTAL_PADDING_DP * 2).coerceIn(MIN_CHIP_WIDTH_DP, MAX_CHIP_WIDTH_DP)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt
git commit -m "Add word-width-based chip reserved-width sizing math"
```

---

### Task 2: Overflow-grow draw-width math

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt`
- Test: `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt`

**Interfaces:**
- Consumes: `REEL_ITEM_SPACING_DP` (Task 1).
- Produces: `internal fun overflowDrawWidthDp(naturalWidthDp: Float, reservedWidthDp: Float, neighborReservedWidthDp: Float?): Float` — consumed by Task 7.

- [ ] **Step 1: Write the failing tests**

Add to `SuggestionReelMathTest.kt`:

```kotlin
    @Test
    fun `a row no wider than its reserved slot draws at the reserved width`() {
        assertEquals(80f, overflowDrawWidthDp(naturalWidthDp = 50f, reservedWidthDp = 80f, neighborReservedWidthDp = 100f))
    }

    @Test
    fun `a wider row grows up to its reserved width plus the neighbor it overlaps`() {
        assertEquals(
            150f,
            overflowDrawWidthDp(naturalWidthDp = 150f, reservedWidthDp = 80f, neighborReservedWidthDp = 100f),
        )
    }

    @Test
    fun `growth is capped at the reserved width plus neighbor plus item spacing`() {
        assertEquals(
            188f,
            overflowDrawWidthDp(naturalWidthDp = 300f, reservedWidthDp = 80f, neighborReservedWidthDp = 100f),
        )
    }

    @Test
    fun `a row cannot grow past its own reserved width when there is no neighbor to overlap`() {
        assertEquals(
            80f,
            overflowDrawWidthDp(naturalWidthDp = 300f, reservedWidthDp = 80f, neighborReservedWidthDp = null),
        )
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
Expected: FAIL with "Unresolved reference: overflowDrawWidthDp"

- [ ] **Step 3: Add the function**

Add to `SuggestionReelMath.kt`:

```kotlin
/**
 * The width to actually draw a reel row at. Never below [reservedWidthDp] (its slot's resting
 * width) and, when a neighbor exists to visually overlap, never above [reservedWidthDp] plus
 * [neighborReservedWidthDp] plus one item-spacing gap. With no neighbor to overlap, the row is
 * capped at its own reserved width (the caller's Text then ellipsizes it).
 */
internal fun overflowDrawWidthDp(
    naturalWidthDp: Float,
    reservedWidthDp: Float,
    neighborReservedWidthDp: Float?,
): Float {
    if (neighborReservedWidthDp == null) return reservedWidthDp
    val cap = reservedWidthDp + neighborReservedWidthDp + REEL_ITEM_SPACING_DP
    return naturalWidthDp.coerceIn(reservedWidthDp, cap)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt
git commit -m "Add overflow-grow draw-width math shared by oversized alternatives and joins"
```

---

### Task 3: Auto-scroll target index math

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt`
- Test: `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt`

**Interfaces:**
- Produces: `internal fun autoScrollTargetIndex(chipCount: Int, rtl: Boolean): Int` — consumed by Task 5.

- [ ] **Step 1: Write the failing tests**

Add to `SuggestionReelMathTest.kt`:

```kotlin
    @Test
    fun `auto-scroll targets the last chip for left-to-right and the first for right-to-left`() {
        assertEquals(4, autoScrollTargetIndex(chipCount = 5, rtl = false))
        assertEquals(0, autoScrollTargetIndex(chipCount = 5, rtl = true))
    }

    @Test
    fun `auto-scroll target is zero with no chips`() {
        assertEquals(0, autoScrollTargetIndex(chipCount = 0, rtl = false))
        assertEquals(0, autoScrollTargetIndex(chipCount = 0, rtl = true))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
Expected: FAIL with "Unresolved reference: autoScrollTargetIndex"

- [ ] **Step 3: Add the function**

Add to `SuggestionReelMath.kt`:

```kotlin
/**
 * Index, within the chip-only portion of the strip (in the same order [SuggestionStrip] already
 * renders chips), of the newest chip: last for left-to-right, first for right-to-left, matching
 * `ordered = if (rtl) chips.asReversed() else chips`.
 */
internal fun autoScrollTargetIndex(chipCount: Int, rtl: Boolean): Int {
    if (chipCount <= 0) return 0
    return if (rtl) 0 else chipCount - 1
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt
git commit -m "Add auto-scroll target index math"
```

---

### Task 4: Join-candidate chip correlation

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/ReplacementJoinAttachment.kt`
- Test: `app/src/test/kotlin/com/iaido/app/ReplacementJoinAttachmentTest.kt`

**Interfaces:**
- Consumes: `com.iaido.core.recognition.SuggestionChip` (`word`, `alternatives`, `selectedIndex`, `corrected`, `id: Int?`), `com.iaido.core.recognition.ReplacementOption` (`sourceWords: List<String>`, `replacementWords: List<String>`, `score: Double`, `id: String`).
- Produces: `internal data class JoinAttachment(val option: ReplacementOption, val firstChipId: Int, val lastChipId: Int)` and `internal fun attachJoinCandidates(chips: List<SuggestionChip>, options: List<ReplacementOption>): List<JoinAttachment>` — consumed by Task 8.
- **Note for Task 8:** `chips` here must be the *canonical* (non-RTL-reversed) list — the same order `option.sourceWords` is in. The result identifies chips by stable `id` (falling back to their position in the matched run when `id` is null), not by index into any particular rendering order, precisely so Task 8 can look them up safely against `ordered` (which may be RTL-reversed) without an index-space mismatch.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/iaido/app/ReplacementJoinAttachmentTest.kt`:

```kotlin
package com.iaido.app

import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SuggestionChip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplacementJoinAttachmentTest {
    private fun chip(word: String) = SuggestionChip(word = word, alternatives = listOf(word))

    @Test
    fun `a join option matching adjacent chips attaches to their contiguous run`() {
        val chips = listOf(chip("wh"), chip("at"))
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        val attachments = attachJoinCandidates(chips, listOf(option))

        assertEquals(1, attachments.size)
        assertEquals(option, attachments.first().option)
        assertEquals(0, attachments.first().firstChipId)
        assertEquals(1, attachments.first().lastChipId)
    }

    @Test
    fun `a join option is found at either end of a longer chip list`() {
        val chips = listOf(chip("hello"), chip("wh"), chip("at"))
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        val attachments = attachJoinCandidates(chips, listOf(option))

        // Chips here have no explicit id, so it falls back to position within the matched run.
        assertEquals(1, attachments.first().firstChipId)
        assertEquals(2, attachments.first().lastChipId)
    }

    @Test
    fun `a chip's stable id is used over its position when both are available`() {
        val chips = listOf(
            SuggestionChip(word = "hello", alternatives = listOf("hello"), id = 10),
            SuggestionChip(word = "wh", alternatives = listOf("wh"), id = 11),
            SuggestionChip(word = "at", alternatives = listOf("at"), id = 12),
        )
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        val attachments = attachJoinCandidates(chips, listOf(option))

        assertEquals(11, attachments.first().firstChipId)
        assertEquals(12, attachments.first().lastChipId)
    }

    @Test
    fun `a stale join option with no matching contiguous run is dropped`() {
        val chips = listOf(chip("hello"), chip("world"))
        val option = ReplacementOption(listOf("wh", "at"), listOf("what"), 0.9)

        assertTrue(attachJoinCandidates(chips, listOf(option)).isEmpty())
    }

    @Test
    fun `split options are never attached, only joins`() {
        val chips = listOf(chip("inthe"))
        val split = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.9)

        assertTrue(attachJoinCandidates(chips, listOf(split)).isEmpty())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.ReplacementJoinAttachmentTest"`
Expected: FAIL (file/functions don't exist yet)

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/iaido/app/ReplacementJoinAttachment.kt`:

```kotlin
package com.iaido.app

import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SuggestionChip

/**
 * A join [option]'s source words matched against a contiguous run of chips, identified by stable
 * [firstChipId]/[lastChipId] (a chip's `id`, or its position within the matched run when `id` is
 * null) rather than by index -- so callers can look these up safely against a rendering order
 * that may differ from the canonical order this was computed from (e.g. RTL-reversed).
 */
internal data class JoinAttachment(
    val option: ReplacementOption,
    val firstChipId: Int,
    val lastChipId: Int,
)

/**
 * Finds, for each join-shaped entry in [options] (more than one source word merging into exactly
 * one replacement word), the contiguous run of [chips] -- in canonical, non-RTL-reversed order,
 * matching the logical order `sourceWords` is in -- whose words equal its `sourceWords` in order.
 * An option with no matching run (stale relative to the current chip list) is omitted; it will
 * attach again once a fresh, matching option arrives.
 */
internal fun attachJoinCandidates(
    chips: List<SuggestionChip>,
    options: List<ReplacementOption>,
): List<JoinAttachment> =
    options
        .filter { option -> option.sourceWords.size > 1 && option.replacementWords.size == 1 }
        .mapNotNull { option -> findContiguousRun(chips, option) }

private fun findContiguousRun(chips: List<SuggestionChip>, option: ReplacementOption): JoinAttachment? {
    val sourceWords = option.sourceWords
    if (sourceWords.isEmpty() || sourceWords.size > chips.size) return null
    for (start in 0..chips.size - sourceWords.size) {
        val window = chips.subList(start, start + sourceWords.size)
        if (window.map { it.word } == sourceWords) {
            return JoinAttachment(
                option = option,
                firstChipId = window.first().id ?: start,
                lastChipId = window.last().id ?: (start + sourceWords.size - 1),
            )
        }
    }
    return null
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.ReplacementJoinAttachmentTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/ReplacementJoinAttachment.kt app/src/test/kotlin/com/iaido/app/ReplacementJoinAttachmentTest.kt
git commit -m "Add join-candidate to adjacent-chip correlation"
```

---

### Task 5: Immediate commit on release

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:310-335` (the `onDragEnd` handler inside `SuggestionChipView`)
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

**Interfaces:**
- Produces: `ImeScenario.swipeSuggestionCommitLatencyMs(index: Int, verticalDistancePx: Float): Long`.

- [ ] **Step 1: Write the failing connected test**

Add to `ImeReelE2eTest.kt`:

```kotlin
    @Test
    fun correctionReelCommitsImmediatelyRatherThanAfterTheSettleAnimation() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("there")
            val latencyMs = swipeSuggestionCommitLatencyMs(index = 0, verticalDistancePx = -96f)
            check(latencyMs < 400L) {
                "Reel commit took ${latencyMs}ms after release; expected the word to commit " +
                    "immediately on release, not after the settle animation finishes"
            }
        }
    }
```

Add to `ImeScenario.kt`, next to `swipeSuggestionImmediately`:

```kotlin
    /** Like [swipeSuggestion], but returns the elapsed ms between the release (ACTION_UP) and the editor text actually changing. */
    fun swipeSuggestionCommitLatencyMs(index: Int, verticalDistancePx: Float): Long {
        device.waitForIdle()
        SystemClock.sleep(1_000L)
        val target = locateReelSwipeTarget(index, verticalDistancePx)
        val path = (1..3).map { step ->
            val fraction = step / 3f
            PointF(target.start.x, target.start.y + target.verticalDistancePx * fraction)
        }.let { listOf(target.start) + it }
        target.validatePath(path)
        val before = expectedText
        var releasedAtMs = -1L
        pendingPointerEvents = pointer.injectScreenSwipe(
            points = path,
            holdBeforeMoveMs = 520L,
            onEvent = { event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    releasedAtMs = SystemClock.elapsedRealtime()
                }
            },
        )
        check(releasedAtMs >= 0L) { "Reel swipe never reported a release event" }
        val after = editor.waitForTextChange(before, timeoutMs = 1_500L)
        val committedAtMs = SystemClock.elapsedRealtime()
        expectedText = after
        device.waitForIdle()
        SystemClock.sleep(200L)
        expectedSelection = editor.selection().last
        checkpoint("swipeSuggestionCommitLatencyMs($index, $verticalDistancePx)")
        return committedAtMs - releasedAtMs
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.correctionReelCommitsImmediatelyRatherThanAfterTheSettleAnimation"`
Expected: FAIL (`latencyMs` is well above 400ms, since `onRelease` currently waits for the settle spring animation)

- [ ] **Step 3: Fix `onDragEnd` in `SuggestionStrip.kt`**

In `SuggestionChipView`'s `pointerInput` block, move the `onRelease(targetIndex)` call out of the `scope.launch { ... }` coroutine so it fires synchronously when the drag ends, before the settle animation starts:

```kotlin
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
                        if (shouldSelect) {
                            onRelease(targetIndex)
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.correctionReelCommitsImmediatelyRatherThanAfterTheSettleAnimation"`
Expected: PASS

- [ ] **Step 5: Run the full connected reel suite to check for regressions**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"`
Expected: PASS (all tests, including the pre-existing ones)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt
git commit -m "Commit reel selection immediately on release instead of after the settle animation"
```

---

### Task 6: LazyRow conversion with reflow animation and auto-scroll

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt` (the `SuggestionStrip` composable's root `Row`)
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

**Interfaces:**
- Consumes: `autoScrollTargetIndex(chipCount: Int, rtl: Boolean): Int` (Task 3).

- [ ] **Step 1: Write the failing connected test**

Add to `ImeReelE2eTest.kt`:

```kotlin
    @Test
    fun stripAutoScrollsSoTheNewestChipStaysInFrameAfterSeveralWords() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("one")
            tapSpace()
            swipeWord("two")
            tapSpace()
            swipeWord("three")
            tapSpace()
            swipeWord("four")
            val device = androidx.test.uiautomator.UiDevice.getInstance(
                androidx.test.InstrumentationRegistry.getInstrumentation(),
            )
            device.waitForIdle()
            check(device.findObject(androidx.test.uiautomator.By.desc("Iaido suggestion 3")) != null) {
                "Newest chip (index 3, 'four') is not visible without further scrolling after four words"
            }
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.stripAutoScrollsSoTheNewestChipStaysInFrameAfterSeveralWords"`
Expected: FAIL (the strip never scrolls, so the fourth chip sits off the visible edge)

- [ ] **Step 3: Convert the root `Row` to `LazyRow`**

Replace the `SuggestionStrip` composable's root layout in `SuggestionStrip.kt`:

```kotlin
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
    val pinnedStripHeight = (REEL_STEP_DP * MAX_REEL_VISIBLE_SLOTS).dp
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(ordered.map { it.id }, rtl) {
        if (ordered.isEmpty()) return@LaunchedEffect
        val leadingExtraItem = replacementOptions.isNotEmpty() && rtl
        val targetIndex = (if (leadingExtraItem) 1 else 0) + autoScrollTargetIndex(ordered.size, rtl)
        listState.animateScrollToItem(targetIndex)
    }

    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(pinnedStripHeight + 8.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = SUGGESTION_STRIP_DESCRIPTION },
        horizontalArrangement = Arrangement.spacedBy(REEL_ITEM_SPACING_DP.dp),
    ) {
        if (replacementOptions.isNotEmpty() && rtl) {
            item(key = "replacement-slot") {
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
        itemsIndexed(ordered, key = { _, chip -> chip.id ?: -1 }) { index, chip ->
            SuggestionChipView(
                chip = chip,
                index = index,
                visibleSlotCount = visibleSlotCount,
                modifier = Modifier.animateItem(),
                onRelease = { candidate -> onRelease(index, candidate) },
                onUndo = { onUndo(index) },
            )
        }
        if (replacementOptions.isNotEmpty() && !rtl) {
            item(key = "replacement-slot") {
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
}
```

Add the needed imports at the top of the file (replace the now-unused `horizontalScroll`/`rememberScrollState` imports):

```kotlin
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
```

(remove `import androidx.compose.foundation.horizontalScroll` and `import androidx.compose.foundation.rememberScrollState`, and drop the now-unqualified `androidx.compose.foundation.lazy.*` prefixes above once the imports are in place, keeping the file's normal style.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.stripAutoScrollsSoTheNewestChipStaysInFrameAfterSeveralWords"`
Expected: PASS

- [ ] **Step 5: Run the full connected reel suite to check for regressions**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"`
Expected: PASS

- [ ] **Step 6: Manually verify reflow animation**

Use the `run` skill to launch the app on the emulator/device, type several words in a row, and confirm chips visibly slide into their new position (rather than jumping) as new ones appear, and slide back when backspacing a word away. Take a screenshot for the record.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt
git commit -m "Convert suggestion strip to LazyRow for reflow animation and auto-scroll to newest chip"
```

---

### Task 7: Word-width-based chip sizing

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt` (`SuggestionChipView`)

**Interfaces:**
- Consumes: `chipReservedWidthDp(measuredTextWidthDp: Float): Float` (Task 1).

- [ ] **Step 1: Replace the fixed chip width with a measured one**

In `SuggestionChipView`, replace:

```kotlin
    val alternatives = chip.alternatives.ifEmpty { listOf(chip.word) }
    val density = LocalDensity.current
```

with:

```kotlin
    val alternatives = chip.alternatives.ifEmpty { listOf(chip.word) }
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val selectedWord = alternatives.getOrElse(chip.selectedIndex) { alternatives.first() }
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val reservedWidthDp = remember(selectedWord, bodyStyle) {
        val measuredWidthPx = textMeasurer.measure(text = selectedWord, style = bodyStyle).size.width
        val measuredWidthDp = with(density) { measuredWidthPx.toDp() }.value
        chipReservedWidthDp(measuredWidthDp)
    }
```

Then replace the chip's fixed width modifier:

```kotlin
            .widthIn(min = 104.dp, max = 184.dp)
            .width(160.dp)
```

with:

```kotlin
            .width(reservedWidthDp.dp)
```

- [ ] **Step 2: Remove the now-unused `widthIn`/fixed-width imports if no longer referenced elsewhere in the file**

Check whether `androidx.compose.foundation.layout.widthIn` is still used anywhere else in `SuggestionStrip.kt`; if not, remove the import.

- [ ] **Step 3: Manually verify against the running app**

Use the `run` skill to launch the app on the emulator/device. Type a short word (e.g. "a") and a long one (e.g. "something") and confirm the chip visibly shrinks/grows to fit each, rather than staying a fixed width. Take a screenshot for the record.

- [ ] **Step 4: Run the full connected reel suite to check for regressions**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt
git commit -m "Size each suggestion chip to its selected word's measured width"
```

---

### Task 8: Unified overflow-grow rendering for oversized alternatives and attached joins

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

**Interfaces:**
- Consumes: `overflowDrawWidthDp(naturalWidthDp: Float, reservedWidthDp: Float, neighborReservedWidthDp: Float?): Float` (Task 2), `attachJoinCandidates(chips: List<SuggestionChip>, options: List<ReplacementOption>): List<JoinAttachment>` (Task 4).

- [ ] **Step 1: Write the failing connected test**

Add to `ImeReelE2eTest.kt`. This drags the first ("wh"-equivalent) chip of a two-chip sequence far enough to scroll past its own alternatives into the attached join candidate, matching the fixture pair used in the segmenter's own regression coverage:

```kotlin
    @Test
    fun scrollingPastAChipsLastAlternativeCommitsTheJoinedWord() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("wh")
            tapSpace()
            swipeWord("at")
            val before = state().expectedText
            val after = swipeSuggestion(index = 0, verticalDistancePx = -480f)
            check(after != before) { "Scrolling past the chip's alternatives did not commit the join: '$before'" }
            check(after.trim() == "what") { "Expected the joined word 'what', got '$after'" }
            val device = androidx.test.uiautomator.UiDevice.getInstance(
                androidx.test.InstrumentationRegistry.getInstrumentation(),
            )
            check(device.findObject(androidx.test.uiautomator.By.desc("Iaido suggestion 1")) == null) {
                "Second source chip is still present after the join committed"
            }
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.scrollingPastAChipsLastAlternativeCommitsTheJoinedWord"`
Expected: FAIL (no join candidate is attached to either chip's own reel yet; the drag just clamps at the last real alternative)

- [ ] **Step 3: Add the overflow-grow layout modifier**

Add near the bottom of `SuggestionStrip.kt`, above the existing `private const val REEL_STEP_DP = 36f` line:

```kotlin
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
```

Add the required imports:

```kotlin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
```

- [ ] **Step 4: Wire join attachment and overflow-grow into `SuggestionChipView`**

`SuggestionChipView` needs three new parameters so it can render an attached join candidate past its real alternatives, and the strip needs to compute per-chip reserved widths up front so neighbors can be looked up. In `SuggestionStrip.kt`:

Replace the `SuggestionStrip` composable's `itemsIndexed` block (added in Task 6) with:

Note this uses the top-level `chips` parameter (canonical, non-RTL-reversed order) when calling `attachJoinCandidates` -- **not** `ordered` -- because `option.sourceWords` is in that same canonical/logical order (see Task 4's note); `ordered` may be RTL-reversed and would silently fail to match. The result is then looked up by stable chip `id` against `ordered`'s actual rendering positions, so the overlap direction (`growsForward`) is computed from where the other chip in the join *actually* renders relative to this one, correctly handling both LTR and RTL:

```kotlin
        val reservedWidths = remember(ordered, bodyTextStyleKey) {
            ordered.map { chip ->
                val word = chip.alternatives.getOrElse(chip.selectedIndex) { chip.alternatives.firstOrNull() ?: chip.word }
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
```

Above that block (inside `SuggestionStrip`, before the `LazyRow`), compute the shared measurer/style once:

```kotlin
    val density = LocalDensity.current
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val bodyTextStyleKey = bodyStyle
```

Update `SuggestionChipView`'s signature to accept the new parameters and use them instead of its own local `rememberTextMeasurer`/`reservedWidthDp` computation added in Task 7 (that per-chip computation moves up to the strip level so neighbor widths are available):

```kotlin
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
    val baseAlternatives = chip.alternatives.ifEmpty { listOf(chip.word) }
    val alternatives = if (joinCandidate != null) {
        baseAlternatives + joinCandidate.replacementWords.joinToString(" ")
    } else {
        baseAlternatives
    }
    val density = LocalDensity.current
```

(remove the `rememberTextMeasurer`/`selectedWord`/`bodyStyle`/`reservedWidthDp` local computation added in Task 7's Step 1 -- it is now a parameter).

Keep everything else in `SuggestionChipView` working off `alternatives` as before (the join candidate is just one more entry at the end), except:

- The `maxIndex`/`minOffset`/`maxOffset` math already derives from `alternatives.lastIndex`, so dragging past the real words naturally reaches the appended join-candidate index -- no change needed there.
- In `onDragEnd`, when `targetIndex == joinCandidate`'s index (i.e. `targetIndex == baseAlternatives.size` and `joinCandidate != null`), call `onReplacementRelease(joinCandidate)` instead of `onRelease(targetIndex)`; when merely previewing it while dragging (via the existing preview `LaunchedEffect` pattern used by `ReplacementReelGroup`), call `onReplacementPreview(joinCandidate)`. Concretely, replace the `onDragEnd` block's `if (shouldSelect) { onRelease(targetIndex) }` line with:

```kotlin
                        if (shouldSelect) {
                            if (joinCandidate != null && targetIndex == baseAlternatives.size) {
                                onReplacementRelease(joinCandidate)
                            } else {
                                onRelease(targetIndex)
                            }
                        }
```

- Apply `overflowGrow` to the chip's outer `Row` modifier, using `overflowDrawWidthDp` with the *currently displayed* row's natural text width. Replace the chip `Row`'s width modifier (set in Task 7 to `.width(reservedWidthDp.dp)`) with:

```kotlin
    val displayedWord = alternatives.getOrNull(displayedIndex).orEmpty()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val displayedWordWidthDp = remember(displayedWord) {
        val widthPx = textMeasurer.measure(text = displayedWord, style = bodyStyle).size.width
        with(density) { widthPx.toDp() }.value
    }
    val drawWidthDp = overflowDrawWidthDp(displayedWordWidthDp, reservedWidthDp, neighborReservedWidthDp)
```

and use `.overflowGrow(reservedWidthDp, drawWidthDp, growsForward)` in place of `.width(reservedWidthDp.dp)` on the chip's `Row` modifier chain.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.scrollingPastAChipsLastAlternativeCommitsTheJoinedWord"`
Expected: PASS

- [ ] **Step 6: Run the full connected reel suite plus JVM unit tests to check for regressions**

Run:
```bash
./gradlew :app:testDebugUnitTest :core-engine:test
./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"
```
Expected: PASS

- [ ] **Step 7: Manually verify the overlap visual**

Use the `run` skill to launch the app, swipe "wh" then "at" as two separate words, and drag the "wh" chip's reel down past its last alternative: confirm the joined "what" candidate visibly widens across into "at"'s space, drawn above it, and releasing there replaces both chips with a single "what" chip. Take a screenshot for the record.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt
git commit -m "Attach join candidates to source chips' own reels with overlap-grow rendering"
```

---

### Task 9: Remove the join case from the edge-anchored replacement slot

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt:744-770` (`dragReplacement`)
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

**Interfaces:**
- None new; this task narrows existing behavior.

- [ ] **Step 1: Write the failing connected test**

Add to `ImeReelE2eTest.kt`, asserting the edge slot no longer appears for a join (it's now only reachable by scrolling a source chip, per Task 8):

```kotlin
    @Test
    fun theEdgeAnchoredReplacementSlotNoLongerAppearsForAJoinCandidate() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("wh")
            tapSpace()
            swipeWord("at")
            val device = androidx.test.uiautomator.UiDevice.getInstance(
                androidx.test.InstrumentationRegistry.getInstrumentation(),
            )
            device.waitForIdle()
            check(device.findObject(androidx.test.uiautomator.By.descStartsWith("Iaido replacement:")) == null) {
                "The old edge-anchored replacement slot is still rendered for a join candidate"
            }
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.theEdgeAnchoredReplacementSlotNoLongerAppearsForAJoinCandidate"`
Expected: FAIL (the `ReplacementReelSlot` item still renders for join options, since `SuggestionStrip` still passes the full unfiltered `replacementOptions` to it)

- [ ] **Step 3: Filter join options out of the edge-anchored slot**

In `SuggestionStrip.kt`, where `ReplacementReelSlot` is rendered (both the `rtl` and `!rtl` `item(key = "replacement-slot")` blocks from Task 6), filter `replacementOptions` down to splits only:

```kotlin
        val splitOnlyReplacementOptions = remember(replacementOptions) {
            replacementOptions.filterNot { option -> option.sourceWords.size > 1 && option.replacementWords.size == 1 }
        }
```

Add this alongside the other `remember`s near the top of `SuggestionStrip`, and use `splitOnlyReplacementOptions` (instead of `replacementOptions`) as both the `if (replacementOptions.isNotEmpty() ...)` condition and the `options =` argument in both `item(key = "replacement-slot") { ReplacementReelSlot(...) }` blocks. Leave `replacementSlotCount`'s existing computation (used for the strip's pinned height) reading from `replacementOptions` unchanged if it's only sizing the vertical viewport -- but since joins no longer render there, change it too, to `splitOnlyReplacementOptions`, so the strip doesn't reserve vertical height for a slot type it no longer shows for joins.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.theEdgeAnchoredReplacementSlotNoLongerAppearsForAJoinCandidate"`
Expected: PASS

- [ ] **Step 5: Run the full connected reel suite and JVM unit tests to check for regressions, especially split-option coverage**

Run:
```bash
./gradlew :app:testDebugUnitTest :core-engine:test
./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest" --tests "com.iaido.app.ImeInferenceE2eTest"
```
Expected: PASS -- in particular any existing test exercising `previewReplacementThenCancel`/`releaseReplacement` for a **split** must still pass unchanged, since splits still use the edge-anchored slot.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt
git commit -m "Stop rendering join candidates in the edge-anchored replacement slot"
```

---

### Task 10: Tune final sizing against the running keyboard

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt` (`REEL_STEP_DP`)
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt` (`CHIP_HORIZONTAL_PADDING_DP`, `MIN_CHIP_WIDTH_DP`, `MAX_CHIP_WIDTH_DP`)

**Interfaces:**
- None new; this task only adjusts constant values from Task 1.

- [ ] **Step 1: Launch the app and compare against the old sizing**

Use the `run` skill to launch the app on the emulator/device. Type several words and open the reel a few times. Compare against the "too big" complaint: the strip's pinned height (`REEL_STEP_DP * MAX_REEL_VISIBLE_SLOTS + 8dp`, currently 116dp) and chip widths should read as noticeably more compact than before this plan, without clipping any word's text or making the tap targets uncomfortably small.

- [ ] **Step 2: Adjust constants if needed**

If the strip still reads as too tall, reduce `REEL_STEP_DP` in `SuggestionStrip.kt` (starting point: `36f` -> `28f`). If chips still read as too wide/narrow, adjust `CHIP_HORIZONTAL_PADDING_DP`, `MIN_CHIP_WIDTH_DP`, or `MAX_CHIP_WIDTH_DP` in `SuggestionReelMath.kt` (starting points from Task 1: `10f`, `56f`, `140f`). Re-run `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"` after any constant change -- the fixed-value assertions from Task 1/2 will need updating to match (update the test expectations, not the invariants they check, e.g. `chipReservedWidthDp` still adds padding and clamps to the (possibly new) min/max).

- [ ] **Step 3: Run the full test suite**

Run:
```bash
./gradlew :app:testDebugUnitTest :core-engine:test
./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt
git commit -m "Tune reel sizing constants against the running keyboard"
```
