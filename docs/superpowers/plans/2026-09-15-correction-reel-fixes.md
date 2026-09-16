# Correction Reel Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three confirmed root-cause bugs in the correction reel (the per-word alternative picker in the suggestion strip): it requires an undiscoverable long-press before it will scroll, it only ever shows one candidate so alternatives are invisible, and the live swipe-typing replacement reel completely hides all previously-typed correction chips while it is active.

**Architecture:** All three bugs live in `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt` and `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt`. No new files, no new abstractions — each task is a targeted fix to existing functions, proven by an existing or updated unit/instrumented test. Fixes are independent and are ordered so each is committed and verified before the next begins.

**Tech Stack:** Kotlin, Jetpack Compose (Foundation gestures), JUnit 5 (`app/src/test`), AndroidJUnit4 instrumented tests (`app/src/androidTest`), Gradle (`./gradlew`).

**Spec:** No separate spec doc — this plan is driven directly by the root-cause investigation below (grounded in the actual code, not speculation).

## Root cause summary (for context — do not re-investigate)

1. **Can't scroll the reel.** `4c385c8` ("Add correction reel and backspace gestures") simultaneously wrapped the suggestion strip `Row` in `.horizontalScroll(rememberScrollState())` *and* changed each chip's per-word vertical drag detector from `detectDragGestures` to `detectDragGesturesAfterLongPress` (`SuggestionStrip.kt:305` and `SuggestionStrip.kt:195`), to stop the child's any-direction drag detector from stealing the parent's horizontal scroll gesture. The workaround overshot: it also blocks the *intended* vertical swipe-to-correct gesture until the user holds first. The correct fix is orientation-locked gesture detection (`detectVerticalDragGestures`), which only claims vertical touch slop and lets the parent's horizontal `scrollable` handle horizontal drags — no long-press needed.

2. **No extra words shown.** `SuggestionReelMath.kt:8-9`:
   ```kotlin
   internal fun reelVisibleSlotCount(candidateCount: Int): Int =
       SINGLE_REEL_VISIBLE_SLOT   // always 1, ignores candidateCount
   ```
   The reel viewport is hard-clipped to exactly one row no matter how many alternatives a word has. The alpha-faded neighbor rows and gradient "peek" overlays in `SuggestionChipView` (`SuggestionStrip.kt:394-398`, `409-421`) already exist for a multi-row viewport but are fully clipped away. Fix: size the viewport to up to 3 rows based on candidate count, and center the selected row within it.

3. **Previous words disappear once you've written more than one.** `SuggestionStrip.kt:67-76`:
   ```kotlin
   if (replacementOptions.isNotEmpty()) {
       ReplacementSuggestionStrip(...)
       return   // chips param is discarded entirely
   }
   ```
   `chips` (`IaidoInputMethodService.kt:598-610`, `refreshSuggestionChips`) are the already-committed words around the cursor available for post-hoc correction/undo. `replacementOptions` is the live segmentation reel for the word(s) still inside the active swipe-typing transaction (`SwipeTypingCoordinator.replacementOptions()`). These represent different, coexisting concerns, but the early return means the moment there's an active swipe transaction (which is most of the time while typing with `SpacingMode.INFER_SPACES`), every previously-typed correction chip vanishes from the strip. Fix: render both in the same row instead of one replacing the other.

## Global Constraints

- Kotlin/Compose style already established in `SuggestionStrip.kt` — no new libraries.
- Every task must leave `./gradlew :app:testDebugUnitTest` green before moving to the next task.
- No behavior changes beyond what's described — no unrelated refactors.

---

### Task 1: Remove the long-press requirement from the reel drag gesture

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:8` (import), `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:195-216` (`ReplacementReelGroup`), `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:304-358` (`SuggestionChipView`)
- Test: `app/src/test/kotlin/com/iaido/app/SuggestionChipGestureTest.kt` (new — a small Robolectric-free pure check is not possible since this is gesture-detection wiring, so instead extend the existing instrumented coverage)
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing new — internal gesture wiring only, no signature changes.

Compose Foundation's `detectVerticalDragGestures` claims only vertical touch slop, so it composes correctly with a parent `Modifier.horizontalScroll` (which claims horizontal touch slop) without needing a long-press to disambiguate. This directly replaces the `detectDragGesturesAfterLongPress` workaround from `4c385c8`.

- [x] **Step 1: Add a failing instrumented test proving a quick (no-hold) vertical swipe changes the suggestion**

  Add to `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`:

  ```kotlin
  @Test
  fun correctionReelRespondsToAnImmediateSwipeWithoutRequiringALongPressFirst() {
      ImeScenario().also(artifacts::track).run {
          swipeWord("there")
          val before = state().expectedText
          val after = swipeSuggestionImmediately(index = 0, verticalDistancePx = -96f)
          check(after != before) { "Reel did not respond to an immediate (no long-press) swipe: '$before'" }
      }
  }
  ```

  Add a matching helper right after `swipeSuggestion` (around `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt:338`) that reuses the same suggestion-locating logic as `swipeSuggestion` but injects the swipe with `holdBeforeMoveMs = 0L` instead of `520L`:

  ```kotlin
  fun swipeSuggestionImmediately(index: Int, verticalDistancePx: Float): String {
      device.waitForIdle()
      SystemClock.sleep(1_000L)
      val suggestion = device.findObject(By.desc("Iaido suggestion $index"))
          ?: error("Missing suggestion $index for immediate swipe")
      val bounds = suggestion.visibleBounds
      val start = PointF((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
      val path = (1..3).map { step ->
          val fraction = step / 3f
          PointF(start.x, start.y + verticalDistancePx * fraction)
      }.let { listOf(start) + it }
      val before = expectedText
      pendingPointerEvents = pointer.injectScreenSwipe(points = path, holdBeforeMoveMs = 0L)
      val after = editor.waitForTextChange(before, timeoutMs = 1_500L)
      expectedText = after
      device.waitForIdle()
      SystemClock.sleep(200L)
      expectedSelection = editor.selection().last
      checkpoint("swipeSuggestionImmediately($index, $verticalDistancePx)")
      return expectedText
  }
  ```

- [x] **Step 2: Run the instrumented test to confirm it fails**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.correctionReelRespondsToAnImmediateSwipeWithoutRequiringALongPressFirst"`
  Expected: FAIL (times out waiting for a text change, because the drag detector currently requires a long-press hold first).

- [x] **Step 3: Replace the long-press drag detector with an orientation-locked one**

  In `SuggestionStrip.kt`, change the import at line 8:

  ```kotlin
  import androidx.compose.foundation.gestures.detectVerticalDragGestures
  ```

  In `ReplacementReelGroup` (`SuggestionStrip.kt:194-216`), replace:

  ```kotlin
              .pointerInput(stateKey, selectedIndex) {
                  detectDragGesturesAfterLongPress(
                      onDragStart = {
                          dragY = 0f
                          isDragging = true
                      },
                      onDrag = { change, amount ->
                          change.consume()
                          dragY += amount.y
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
  ```

  with:

  ```kotlin
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
  ```

  In `SuggestionChipView` (`SuggestionStrip.kt:304-358`), replace:

  ```kotlin
              .pointerInput(chip.id, chip.word, chip.selectedIndex) {
                  detectDragGesturesAfterLongPress(
                      onDragStart = {
                          scope.launch { reelOffset.stop() }
                          dragX = 0f
                          dragY = 0f
                          isDragging = true
                      },
                      onDragEnd = {
                          val releaseOffset = latestDragOffset.value
                          val shouldUndo = latestDragX.value <= -undoThresholdPx && latestDragY.value <= -undoThresholdPx
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
                              if (shouldSelect) {
                                  onRelease(targetIndex)
                              }
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
  ```

  with (the undo gesture no longer has a horizontal-drag signal available from `detectVerticalDragGestures`, so drop the `dragX`/undo-threshold check from this detector — undo already has its own dedicated backspace-swipe gesture per `KeyboardInputView.kt`'s `classifyBackspaceGesture`/`BackspaceGestureAction.UNDO` path, so this chip-level undo-by-diagonal-drag was a redundant, undiscoverable second entry point for the same action):

  ```kotlin
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
  ```

  This removal of the diagonal undo drag also frees up `dragX`, `latestDragX`, `onUndo` wiring at the call site that's now dead in this composable — **do not** remove the `onUndo` parameter or its call site in `KeyboardInputView.kt`/`IaidoInputMethodService.kt` (that's out of scope; only this now-orphaned diagonal-drag trigger inside `SuggestionChipView` goes away). Remove the now-unused `dragX`, `latestDragX` locals (lines around `SuggestionStrip.kt:252,264`) and their reset in the `LaunchedEffect` (`SuggestionStrip.kt:277-282`) and in `onDragStart`/`onDragEnd`/`onDragCancel` above (already reflected in the replacement block).

  Also remove the `onUndo: () -> Unit` parameter usage inside `SuggestionChipView` only if it becomes truly unused after this edit — check first with a search for `onUndo(` inside the function body; if nothing calls it anymore, leave the parameter itself in place (the caller in `SuggestionStrip`'s `Row` still passes `onUndo = { onUndo(index) }`) but it's fine for it to go unused inside `SuggestionChipView` for now — **do not delete the parameter**, that's a larger API change out of scope for this bug-fix task.

- [x] **Step 4: Run the instrumented test to confirm it passes**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.correctionReelRespondsToAnImmediateSwipeWithoutRequiringALongPressFirst"`
  Expected: PASS

- [x] **Step 5: Run the full existing reel E2E test to confirm no regression**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"`
  Expected: PASS (the original `correctionReelScrollsCommitsAndRemembersTheReleasedCandidate` test already holds 520ms before moving, which still satisfies a plain vertical-drag detector)

- [x] **Step 6: Run the unit test suite to confirm nothing else broke**

  Run: `./gradlew :app:testDebugUnitTest`
  Expected: PASS

- [x] **Step 7: Commit**

  ```bash
  git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt
  git commit -m "Fix correction reel requiring a long-press before it scrolls"
  ```

---

### Task 2: Show more than one alternative in the reel viewport

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt:8-9,20`
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:369-373` (`translationY` centering in `SuggestionChipView`)
- Test: `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt:13-17`

**Interfaces:**
- Consumes: `reelVisibleSlotCount(candidateCount: Int): Int` (existing signature, unchanged).
- Produces: `reelVisibleSlotCount` now varies with `candidateCount` (1..3) instead of always returning 1 — `SuggestionStrip.kt`'s `viewportHeight` calculations (already parameterized on this function's result) automatically grow to fit.

- [x] **Step 1: Update the existing test to the new expected behavior (this is the failing test)**

  Replace in `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt`:

  ```kotlin
      @Test
      fun `reel viewport always shows one candidate`() {
          assertEquals(1, reelVisibleSlotCount(candidateCount = 5))
          assertEquals(1, reelVisibleSlotCount(candidateCount = 1))
      }
  ```

  with:

  ```kotlin
      @Test
      fun `reel viewport shows up to three candidates so alternatives are visible at rest`() {
          assertEquals(1, reelVisibleSlotCount(candidateCount = 1))
          assertEquals(2, reelVisibleSlotCount(candidateCount = 2))
          assertEquals(3, reelVisibleSlotCount(candidateCount = 3))
          assertEquals(3, reelVisibleSlotCount(candidateCount = 5))
      }

      @Test
      fun `reel viewport never shrinks below one slot even with no candidates`() {
          assertEquals(1, reelVisibleSlotCount(candidateCount = 0))
      }
  ```

- [x] **Step 2: Run the test to verify it fails**

  Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
  Expected: FAIL — `reelVisibleSlotCount(candidateCount = 3)` returns `1`, not `3`.

- [x] **Step 3: Implement**

  In `SuggestionReelMath.kt`, replace:

  ```kotlin
  internal fun reelVisibleSlotCount(candidateCount: Int): Int =
      SINGLE_REEL_VISIBLE_SLOT
  ```

  with:

  ```kotlin
  internal fun reelVisibleSlotCount(candidateCount: Int): Int =
      candidateCount.coerceIn(1, MAX_REEL_VISIBLE_SLOTS)
  ```

  and replace the constant at the bottom of the file:

  ```kotlin
  private const val SINGLE_REEL_VISIBLE_SLOT = 1
  ```

  with:

  ```kotlin
  private const val MAX_REEL_VISIBLE_SLOTS = 3
  ```

- [x] **Step 4: Run the test to verify it passes**

  Run: `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SuggestionReelMathTest"`
  Expected: PASS

- [x] **Step 5: Center the selected row within the now-taller viewport**

  The viewport height already scales with `reelVisibleSlotCount` (`SuggestionStrip.kt:80,261`), but the column inside `SuggestionChipView` still positions the selected row at the *top* of the viewport (`translationY` shifts so row `chip.selectedIndex` lands at y=0), so with a 3-slot viewport the selected word would sit in the top row instead of the center. Fix the centering in `SuggestionStrip.kt`.

  Replace (around `SuggestionStrip.kt:366-373`):

  ```kotlin
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .graphicsLayer {
                          translationY = with(density) {
                              ((renderedOffset - chip.selectedIndex) * REEL_STEP_DP).dp.toPx()
                          }
                      },
              ) {
  ```

  with:

  ```kotlin
              val centerSlotOffset = (visibleSlotCount - 1) / 2f
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .graphicsLayer {
                          translationY = with(density) {
                              ((renderedOffset - chip.selectedIndex + centerSlotOffset) * REEL_STEP_DP).dp.toPx()
                          }
                      },
              ) {
  ```

  (`visibleSlotCount` is already an in-scope parameter of `SuggestionChipView`, per `SuggestionStrip.kt:242`. With `visibleSlotCount == 1`, `centerSlotOffset == 0f`, so single-alternative chips render identically to before — no regression there.)

- [x] **Step 6: Add a math-level regression test for the centering offset**

  Add to `SuggestionReelMathTest.kt` (this documents the centering contract at the math layer even though the actual pixel translation lives in the Composable — add a small pure helper so it's testable):

  First, extract the centering formula into `SuggestionReelMath.kt` as its own pure function so it's unit-testable rather than buried in a Composable:

  ```kotlin
  internal fun reelCenterSlotOffset(visibleSlotCount: Int): Float =
      (visibleSlotCount - 1) / 2f
  ```

  Then in `SuggestionStrip.kt`, use it instead of the inline expression:

  ```kotlin
              val centerSlotOffset = reelCenterSlotOffset(visibleSlotCount)
  ```

  And add the test:

  ```kotlin
      @Test
      fun `selected candidate centers within a multi-slot viewport`() {
          assertEquals(0f, reelCenterSlotOffset(visibleSlotCount = 1))
          assertEquals(0.5f, reelCenterSlotOffset(visibleSlotCount = 2))
          assertEquals(1f, reelCenterSlotOffset(visibleSlotCount = 3))
      }
  ```

- [x] **Step 7: Run the full unit test suite**

  Run: `./gradlew :app:testDebugUnitTest`
  Expected: PASS

- [x] **Step 8: Run the reel E2E instrumented test to confirm the visible viewport change doesn't break gesture bounds**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"`
  Expected: PASS — `swipeSuggestion`/`swipeSuggestionImmediately` locate the chip by content description and compute drag distance from its (now taller) `visibleBounds`, so no change needed there.

- [x] **Step 9: Commit**

  ```bash
  git add app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt
  git commit -m "Show up to three alternatives in the correction reel instead of one"
  ```

---

### Task 3: Stop the live replacement reel from hiding previously-typed correction chips

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:20` (import), `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt:56-155` (`SuggestionStrip`, deletes `ReplacementSuggestionStrip`, adds `ReplacementReelSlot`)
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt` (new test)

**Interfaces:**
- Consumes: `ReplacementReelGroup(options, selectedIndex, rtl, viewportHeight, onPreview, onRelease, onCancel)` (existing, unchanged signature, `SuggestionStrip.kt:158-166`).
- Produces: `SuggestionStrip`'s public signature is unchanged — this is purely an internal layout fix.

- [x] **Step 1: Add a failing instrumented test proving chips stay visible during an active swipe-typing reel**

  Add to `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`:

  ```kotlin
  @Test
  fun previouslyTypedCorrectionChipsStayVisibleWhileTheNextWordIsMidSwipe() {
      ImeScenario().also(artifacts::track).run {
          swipeWord("there")
          swipeSuggestion(index = 0, verticalDistancePx = -96f)
          device.waitForIdle()
          check(device.findObject(By.desc("Iaido suggestion 0")) != null) {
              "First word's correction chip disappeared while a later word's swipe reel is active"
          }
      }
  }
  ```

  (`swipeWord` starts a new INFER_SPACES transaction for its own word, which is what previously wiped out chip 0 for the already-corrected first word — see root cause #3 above. Check `ImeScenario.swipeWord` at `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt` to confirm it can be called a second time in the same session before writing this test; if the existing scenario only supports one `swipeWord` call per run, call the underlying gesture helper it uses directly a second time instead — read that method first.)

- [x] **Step 2: Run the test to verify it fails**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.previouslyTypedCorrectionChipsStayVisibleWhileTheNextWordIsMidSwipe"`
  Expected: FAIL — `SuggestionStrip`'s early return replaces the whole strip with only the live replacement reel, so "Iaido suggestion 0" is absent.

- [x] **Step 3: Implement — render chips and the live replacement reel together instead of one replacing the other**

  In `SuggestionStrip.kt`, remove the now-unused import at line 20:

  ```kotlin
  import androidx.compose.foundation.lazy.LazyRow
  ```

  Replace the whole `SuggestionStrip` function and delete `ReplacementSuggestionStrip` and its `Saver` (`SuggestionStrip.kt:56-155`) with:

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
      Row(
          modifier = Modifier
              .fillMaxWidth()
              .height(viewportHeight + 8.dp)
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
  ```

  This keeps `ReplacementReelGroup` (`SuggestionStrip.kt:158-236` in the original file, unchanged) and `SuggestionChipView` (unchanged besides Task 1/2's edits) exactly as they are — only the composition root changes from "one or the other" to "both, in the same scrollable row."

- [x] **Step 4: Run the new test to verify it passes**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest.previouslyTypedCorrectionChipsStayVisibleWhileTheNextWordIsMidSwipe"`
  Expected: PASS

- [x] **Step 5: Run the full reel E2E suite and unit suite to confirm no regressions**

  Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.iaido.app.ImeReelE2eTest"`
  Run: `./gradlew :app:testDebugUnitTest`
  Expected: PASS on both

- [x] **Step 6: Commit**

  ```bash
  git add app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt
  git commit -m "Stop the live replacement reel from hiding previously-typed correction chips"
  ```

---

### Task 4: Push and manually verify

**Files:** none (verification-only task)

- [ ] **Step 1: Push to `dev`**

  Per this repo's branching policy, routine work goes to `dev`, not `main`.

  ```bash
  git push origin main:dev
  ```

  (If `main` and `dev` have diverged in a way that makes this a non-fast-forward push, stop and ask the user how to reconcile — do not force-push.)

- [ ] **Step 2: Manually verify on an emulator or the physical test device**

  Build and install a debug APK, then in a real text field:
  - Swipe-type a word, then immediately (no hold) swipe up/down on its suggestion chip and confirm it scrolls through alternatives.
  - Confirm more than one alternative word is visible in the chip at rest (not just after dragging).
  - Swipe-type a second word right after correcting the first, and confirm the first word's correction chip is still visible in the strip.

  Do not merge `dev` → `main` until this manual verification passes — ask the user before merging, per the repo's `main`-is-a-special-occasion policy.

## Addendum — work beyond this plan

While proving Task 3's fix with a real end-to-end test, two additional pieces of work happened at the user's direction, beyond what this plan originally scoped:

- **A bonus bug, found and fixed:** `core-engine`'s `SessionCorrectionHistory.aroundCursor` used an inclusive cursor-boundary check that dropped earlier correction chips whenever the cursor rested exactly at a just-finished word's end (the normal resting position after typing a word) — so finishing a second word right after a first made the first word's chip disappear. Fixed by making the boundary exclusive (`it.start until it.end`), with a unit regression test. Commit: the `aroundCursor` fix, reviewed and approved.
- **A genuine two-word pinning test was attempted but blocked** by a separate, unrelated finding: the suggestion strip's accessibility-tree semantics description freezes on its first value and never updates through later state transitions, even though the app's actual Compose/coordinator state demonstrably progresses correctly. Two investigation rounds (an async-selection-guard race hypothesis, then a direct hypothesis about the accessibility tree itself) each ruled out one cause and narrowed toward the next, but the root cause of the staleness itself was not found. At the user's explicit direction, this was filed as a separate follow-up task rather than continued in this session, and the E2E test was left in its previous, honestly-caveated passing form.
- A final whole-branch review (independent of the per-task reviews above) found the suggestion strip's height was changing at runtime as chip/reel candidate counts varied (1-3 slots), causing the keyboard to visibly jump while typing — fixed by pinning the strip's outer height to a constant maximum while preserving per-chip centering math. It also flagged a test name that overclaimed its own coverage; renamed.

Final state: all three original bugs fixed and reviewed; the bonus `aroundCursor` bug fixed and reviewed; 185/185 unit tests green; accessibility-tree staleness tracked as an open follow-up, not resolved here.
