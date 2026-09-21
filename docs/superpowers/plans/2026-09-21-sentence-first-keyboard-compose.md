# Sentence-First Keyboard Compose Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with the verification gates below. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Iaido’s box-reel suggestion strip and raised keyboard styling with the approved sentence-first keyboard, including synchronized cursor gestures, correction previews, deletion, edge scrolling, and multi-step undo/redo.

**Architecture:** Keep editor text, UTF-16 selection, correction candidates, and edit history owned by `IaidoInputMethodService`. Introduce one immutable strip state and a small semantic action interface between the service and Compose; keep touch geometry, previews, scroll animation, and rendering inside a measured sentence-strip module. Retain `SwipeTypingCoordinator` and `ReplacementOption` as the source of inferred split/join choices, and commit every strip edit through one service-owned editor/history transaction.

**Tech Stack:** Kotlin/JVM 17, Android InputMethodService/InputConnection, Jetpack Compose Material 3, JUnit 5 app/core tests, Android instrumentation, UiAutomator, existing `ImeScenario` pointer injection, Gradle.

**Spec:** [Reel strip behavior contract](../specs/2026-09-20-reel-strip-behavior.md); [Jetpack Compose visual and motion design](../specs/2026-09-21-sentence-first-keyboard-compose-design.md).

## Global Constraints

- Use the sentence-first behavior contract as the interaction source of truth. The older box-reel plan is marked superseded and must not be executed.
- Keep committed text and the UTF-16 selection authoritative in `InputConnection`/`IaidoInputMethodService`; Compose gesture previews never edit host text before pointer release.
- Keep the QWERTY and Hebrew character order, row offsets, bottom-row weights, and hit-test geometry aligned. Refactor them only where a shared geometry function is needed to keep rendering and hit testing identical.
- Use the design tokens in the Compose design document: page `#111518`, panel `#191E21`, screen `#171C1F`, deck `#1A2023`, key `#20272A`, key border `#3B4549`, primary ink `#EDF0F1`, muted ink `#8E9A9E`, subtle ink `#647176`, accent `#8BCBD0`, accent wash `#263B3E`, divider `#30393D`, and deletion red `#EB737B`.
- Increase the visible word gap by exactly 4 prototype CSS px at the reference density; extend each adjacent word hit target halfway into that added spacing without moving glyphs.
- Keep the sentence strip box-free at rest, clamp all candidate and horizontal movement without wrapping, and expose autocorrect words only; omit duplicates and capitalization-only variants.
- A held gesture shows its complete proposed sentence without changing editor text or selection. Releasing commits one atomic transaction; cancellation restores the unmodified editor state.
- Draw deletion feedback only for words whose midpoints were crossed. Returning over the source word resumes alternative selection; pulling back across midpoints removes words from the deletion preview.
- Use one shared measured geometry snapshot for glyph layout, hit targets, caret mapping, join bounds, deletion bounds, and edge-scroll selection. Freeze the snapshot while a pointer is active so recomposition cannot move targets under the finger.
- Undo and redo stay visible, have independent enabled states, retain up to 40 logical transactions, restore selection, and clear redo after a new edit.
- Preserve infer-space, two-finger typing, backspace, language switching, editor privacy, and existing debug identity. Do not inspect or stage unrelated `.superpowers/brainstorm/` artifacts.
- Add no dependencies. Compose and Android instrumentation dependencies are already configured in `app/build.gradle.kts`.
- Treat connected IME results as verified only after an actual emulator instrumentation result. Keep the supplied screenshots and V7 prototype as visual references; do not claim screenshot parity from semantics or build success alone.
- Validation adjustment (2026-09-21): per user request, defer the expensive connected IME journeys and screenshot comparisons to Task 9. During Tasks 4–8, use JVM tests and APK/test-APK compilation as the iteration gate; do not rerun the IME harness for each task.

---

## Existing Files and Responsibilities

- `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt` owns the keyboard Compose tree, key rendering, pointer collection, swipe trail, and split/backspace gestures. It is the keyboard-shell integration point; keep recognition-path capture working while moving sentence-strip gestures into their own stable pointer handler.
- `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt` currently renders box/chip reels and replacement slots. Replace this rendering with the sentence-first strip, then remove this file after all callers migrate.
- `app/src/main/kotlin/com/iaido/app/KeyboardGeometry.kt` defines keyboard surface sizing and row offsets; `core-engine/src/main/kotlin/com/iaido/core/layout/KeyboardLayout.kt` defines shared key positions, while `KeyboardInputView.kt` maps those positions to rendered keys. Keep all three consistent with `keyAt`, `bottomRowKeyAt`, and swipe recognition.
- `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt` owns `InputConnection`, `onUpdateSelection`, extracted-text observation, `SessionCorrectionHistory`, the `SwipeTypingCoordinator`, and the current Compose callbacks. It remains the sole editor mutation owner.
- `app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt` owns inferred sentence-span replacements and exposes structured `ReplacementOption`s. Keep candidate generation here; use the strip only to preview and select an option.
- `app/src/main/kotlin/com/iaido/app/EditorTextChangeDetector.kt` distinguishes the IME’s own commits from host-app edits. Preserve this protection while adding sentence-state synchronization and undo records.
- `app/src/main/kotlin/com/iaido/app/IaidoTheme.kt` provides app-wide Material colors. Prefer a keyboard-scoped palette so the requested keyboard appearance does not unintentionally recolor Settings.
- `app/src/androidTest/kotlin/com/iaido/app/SentenceStripImeDriver.kt`, `ImeReelE2eTest.kt`, `ImeInferenceE2eTest.kt`, and `SettingsImeReelE2eTest.kt` contain the new semantic contract and real-IME acceptance paths committed with this design. Implement production semantics to satisfy these tests; do not weaken them to old reel assertions.
- `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt`, `ReplacementReelLayoutTest.kt`, and `ReplacementJoinAttachmentTest.kt` cover old box-reel helpers. Retire only the assertions/helpers whose production paths are removed; keep language recognition and transaction tests.
- `docs/superpowers/prototypes/keyboard-reel-inline-v7.html` is a throwaway interaction prototype, not production code. The three supplied phone captures are in `docs/superpowers/prototypes/keyboard-reel-references/` and are embedded in the Compose design document.

## Proposed Strip Interface

Keep the Compose caller’s interface semantic and independent of pixel coordinates. The service publishes state; Compose owns active pointer positions and derives preview state from measured strip geometry.

```kotlin
internal data class SentenceStripWord(
    val id: String,
    val text: String,
    val start: Int,
    val endExclusive: Int,
    val above: String?,
    val below: String?,
)

internal data class SentenceStripReplacement(
    val option: ReplacementOption,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
)

internal data class SentenceStripState(
    val sentenceText: String,
    val sentenceStart: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val words: List<SentenceStripWord>,
    val replacementOptions: List<SentenceStripReplacement>,
    val language: Language,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val undoPreview: SentenceHistoryPreview?,
    val redoPreview: SentenceHistoryPreview?,
)

internal data class SentenceHistoryPreview(
    val actionLabel: String,
    val beforeText: String,
    val afterText: String,
)

internal interface SentenceStripActions {
    fun setSelection(start: Int, endExclusive: Int = start): Boolean
    fun commitWordReplacement(wordId: String, expectedCurrent: String, replacement: String): Boolean
    fun commitReplacement(replacement: SentenceStripReplacement): Boolean
    fun commitDeletion(preview: SentenceDeletionPreview): Boolean
    fun undo(): Boolean
    fun redo(): Boolean
}
```

All selection and word-span offsets in this interface are absolute editor UTF-16 offsets. `sentenceText` is the current sentence slice and `sentenceStart` is its absolute start offset. Each `SentenceStripReplacement` pairs the existing engine `ReplacementOption` with the absolute source range resolved from current spans; the service rejects stale IDs or ranges instead of applying a candidate to a neighboring word. Preview text is derived by applying the proposed range edit to `sentenceText` using offsets relative to `sentenceStart`, and is never sent through `SentenceStripActions` until release.

## Implementation Tasks

### Task 1: Establish the baseline and lock the state contract

**Files:**

- Read: `docs/superpowers/specs/2026-09-20-reel-strip-behavior.md`
- Read: `docs/superpowers/specs/2026-09-21-sentence-first-keyboard-compose-design.md`
- Read: `app/src/androidTest/kotlin/com/iaido/app/SentenceStripImeDriver.kt`
- Read: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`
- Test baseline: `core-engine/src/test/`, `app/src/test/`, and the current connected `SettingsImeReelE2eTest`

- [x] Run the existing JVM baseline with `./gradlew --no-daemon --max-workers=2 :core-engine:test :app:testDebugUnitTest`; record the failing test names before changing production behavior.
- [x] Build the existing app and Android test APKs with `./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest`; record whether the current prospective sentence-strip test sources compile.
- [x] Before asserting the new sentence-strip semantics, capture the current debug IME in Settings with the existing `UiDevice`/`ArtifactWriter` instrumentation path and save it as the before image. Record emulator logical size and density using `adb shell wm size` and `adb shell wm density`.
- [x] Mark each STRIP-01…STRIP-27 case as already covered by the committed driver/tests or still missing. Keep the behavior document’s IDs stable and add only the missing cases in their owning task below.
- [x] Keep this baseline step read-only for production code. Retain the test output and named screenshot artifact, and do not alter unrelated `.superpowers/brainstorm/` state.

**Baseline evidence (2026-09-21):** JVM baseline passed; both APKs built, including the prospective Android test sources. The current connected Settings journey reaches typing “Hi” and then fails at its expected missing `Iaido sentence strip` semantic node. The pre-assertion screenshot is `app/build/outputs/baseline/settings-ime-typed-hi-sentence-strip.png`; emulator physical size is 1080×2400 at 420 dpi. The connected XML report is under `app/build/outputs/androidTest-results/connected/debug/`.

**Existing test ownership:** `SettingsImeReelE2eTest` represents STRIP-01 and captures the initial visual baseline for STRIP-25. `ImeReelE2eTest` has journeys for STRIP-02…03, 06, 10…12, 13a, a partial STRIP-14/16/17 deletion path, right-side STRIP-19 cursor scrolling, and partial STRIP-22/23 history behavior. `ImeInferenceE2eTest` protects inference recognition and non-mutating split/join integration beyond the numbered strip cases. The remaining explicit assertions are STRIP-04…05, 07…09, 13, 15, 18, 20…21, 24, and 26…27; complete midpoint reversal, both edge directions, history cancellation/exhaustion, and screenshot comparison are still required in the tasks below.

**Pass condition:** JVM baseline and test APK build results are recorded, current Settings/IME screenshot geometry is known, and every behavior-contract row has an identified test owner.

### Task 2: Build the sentence snapshot and cursor synchronization model

**Files:**

- Create: `app/src/main/kotlin/com/iaido/app/SentenceStripState.kt`
- Create: `app/src/main/kotlin/com/iaido/app/SentenceTextModel.kt`
- Create: `app/src/test/kotlin/com/iaido/app/SentenceTextModelTest.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Reuse: `EditorSnapshot`, `SessionCorrectionHistory`, `SwipeTypingCoordinator`, `ReplacementOption`

- [x] Add `SentenceStripWord`, `SentenceStripState`, and the `SentenceStripActions` interface shown above. Keep preview-only fields out of the service state; retain candidates and history preview snapshots in the immutable state.
- [x] Implement `SentenceTextModel.update(previous: SentenceStripState?, snapshot: EditorSnapshot, language: Language, history: List<SessionWord>, replacementOptions: List<ReplacementOption>): SentenceStripState` as the single pure entry point. Reuse `SessionWord.id` for tracked spans, retain prior IDs for unchanged spans translated through the observed edit, and allocate new IDs only for new/split/join output spans.
- [x] Use the monitored `ExtractedText` snapshot and its `startOffset` when available. Find the sentence around the active selection, preserve punctuation and separators, and map tracked correction/history IDs onto matching text spans. When no sentence text can be observed, expose no stale strip words.
- [x] Use `java.text.BreakIterator.getWordInstance(Locale.forLanguageTag(language.localeTag))` for locale-aware spans and `BreakIterator.getCharacterInstance(Locale.forLanguageTag(language.localeTag))` to snap character taps to legal grapheme boundaries. Retain apostrophes, Hebrew letters/marks, punctuation offsets, and spaces; keep the host selection’s exact UTF-16 offset even when it falls in a gap.
- [x] Suppress extracted text, candidates, and history previews for password/visible-password/web-password and non-text editors. Clear strip state on input finish, editor switch, and session restart before observing the next editor.
- [x] Update `onStartInputView`, `onUpdateSelection`, `onUpdateExtractedText`, `observeEditorSnapshot`, and `refreshSuggestionChips` to publish one fresh `SentenceStripState`. Preserve `InferenceSelectionGuard`: an IME-originated edit must not be treated as an external cursor move.
- [x] Add tests for cursor-at-word-end, cursor-in-word, cursor-in-gap, punctuation, an extracted-text nonzero offset, Hebrew/RTL words, combining marks, preserved IDs after an earlier-span insertion, new IDs after split/join, and password/no-observation state. Assert word spans and selection offsets, not just rendered text.
- [x] Run `./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest`; the focused `SentenceTextModelTest` and full app JVM suite pass.

- [ ] Use Compose `TextLayoutResult.getOffsetForPosition` for screen-x to text-offset mapping. This is implemented with the measured strip gesture work in Task 6.

**Produces:** one immutable state containing the current sentence, stable word IDs and spans, active language, candidate choices, exact selection, and independent undo/redo availability.

### Task 3: Centralize atomic editor edits and multi-step history

**Files:**

- Create: `app/src/main/kotlin/com/iaido/app/SentenceEditHistory.kt`
- Create: `app/src/test/kotlin/com/iaido/app/SentenceEditHistoryTest.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SwipeInferenceTransaction.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/TypingController.kt` only where it needs an edit-group notification
- Test: `app/src/test/kotlin/com/iaido/app/SwipeInferenceTransactionTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/SwipeTypingCoordinatorTest.kt`
- Reuse: `EditorTextChangeDetector.kt`, `InferenceSelectionGuard.kt`

- [x] Define an edit record with source start/end, replaced text, replacement text, selection before/after, and a logical edit kind (`Typing`, `Backspace`, `Correction`, `Split`, `Join`, or `Deletion`). Retain at most 40 logical transactions across the undo/redo history.
- [x] Keep `SessionCorrectionHistory` as candidate/span provenance. Do not use it as the host text undo stack; the new `SentenceEditHistory` owns editor before/after text and selections.
- [x] Add `recordAppliedEdit`, `undoCandidate`, `redoCandidate`, `markExternalEdit`, `undo`, and `redo` to the history module. A failed `InputConnection` operation must not move either history stack.
- [ ] Wire sentence-strip commits and deletion through the shared service method in Task 7, when the measured strip action callbacks are added. The method already validates the observed source span, sets the range selection, commits replacement text once, restores selection, updates `EditorTextChangeDetector`, records one history action, and refreshes `SentenceStripState`.
- [x] Route existing correction and join edits through that method. Strip deletion will record its full contiguous range as one transaction and restore the cursor at the range start when it is integrated in Task 7.
- [x] Record typing through the existing `commitText` path. Coalesce adjacent character insertion until whitespace/punctuation, cursor movement, backspace, a correction, or an external host edit closes the group. Coalesce a repeated backspace gesture as one group.
- [x] Record edits from `replaceInferenceHostSpan` as typing actions too. Coalesce replacements of the same live inference source span into that typing group; close it when `recordFinalizedInferenceWords` finalizes the span or `finalizeAndClear` ends the inference transaction.
- [x] On undo/redo, verify the expected text at the target range before applying the inverse/forward replacement. If the host has changed that span externally, drop the stale entry and refresh state without overwriting the host edit.
- [x] Add unit tests for multiple undo/redo steps, before/after selection restoration, one transaction for split/join/multi-word delete, typing/backspace coalescing, redo clearing after a new edit, 40-entry cap, failed application, and stale external text.
- [x] Run `./gradlew --no-daemon --max-workers=2 :core-engine:test :app:testDebugUnitTest`; the history and service transaction change is ready to commit after the focused and full app JVM tests pass.

**Produces:** one service-owned atomic edit/history seam used by candidate selection, split, join, deletion, typing, and backspace.

### Task 4: Implement shared sentence geometry and a measured Compose strip

**Files:**

- Create: `app/src/main/kotlin/com/iaido/app/SentenceStripGeometry.kt`
- Create: `app/src/main/kotlin/com/iaido/app/SentenceStrip.kt`
- Create: `app/src/test/kotlin/com/iaido/app/SentenceStripGeometryTest.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt`
- Replace: `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt`
- Retire after migration: box-specific geometry from `SuggestionReelMath.kt`, `ReplacementReelLayout.kt`, and `ReplacementJoinAttachment.kt`, plus their obsolete visual assertions. Keep or move candidate-normalization helpers that the sentence model still uses.

- [x] Implement a pure geometry snapshot with measured word widths, glyph bounds, hit bounds, UTF-16 spans, caret anchors, content width, and focus scroll offset. Extend each hit bound halfway through the additional 4 CSS-pixel visual gap.
- [x] Add pure helpers for word hit testing, two-word join unions, midpoint-based reversible deletion ranges, cursor-anchor mapping, LTR/RTL ordering, and clamped focus scrolling. Tighten deletion arming so the finger must enter an adjacent word target before the source word becomes a deletion preview.
- [x] Build the initial measured `SentenceStrip` from Compose text metrics and a keyed horizontal row. The actual text layouts render from the same measured lane widths; gesture-time `TextLayoutResult` mapping is completed in Task 6.
- [x] Keep one geometry snapshot frozen for the pointer lifetime. Candidate preview text, focus color, and animation progress may recompose, but they must not change the active gesture’s word bounds or restart its pointer handler.
- [x] Publish all semantic states consumed by `SentenceStripImeDriver`: strip root, indexed words, upper/lower alternatives, caret, undo/redo, full-sentence preview, join, deletion, history preview, and active edge zones.
- [x] Add geometry tests for half-gap hit targets, short “it” beside long words, midpoint inclusion/exclusion, reversible selection, join union coverage/centering, LTR/RTL coordinates, content clamping, and cursor-offset mapping.
- [x] Run `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest`. The focused Compose semantics test is compiled and will be run with the deferred final device pass.
- [x] Commit the geometry and measured-strip foundation with the visual foundation and the deferred connected-test decision recorded.

**Produces:** one measured geometry source for layout, hit testing, join/deletion overlays, caret placement, and accessibility bounds.

### Task 5: Apply the visual system to the strip and keybed

**Files:**

- Create: `app/src/main/kotlin/com/iaido/app/KeyboardPalette.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStrip.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt`
- Modify only if needed to keep theme boundaries clear: `app/src/main/kotlin/com/iaido/app/IaidoTheme.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/SettingsImeReelE2eTest.kt`

- [x] Add keyboard-scoped color tokens using the exact design-document values. Keep the app’s Settings colors unchanged.
- [x] Render the sentence strip as plain text lanes: current word at 16 CSS px reference size/medium weight, alternatives at 12 CSS px and readable 64% opacity, section labels muted, and focused text/caret in cyan. Keep the current/upper/lower baseline stable when a candidate is absent.
- [x] Render the strip header, focus label, and always-present undo/redo outline icons. Disabled icons remain in layout and independently expose disabled semantics.
- [x] Keep the keyboard surface height and navigation inset stable. Give the 15–16 dp undo/redo glyphs a larger transparent touch target without changing their visible size.
- [x] Render flat keycaps with fine outlines and no resting shadows. Keep QWERTY/Hebrew glyph positions, punctuation rows, number hints, bottom-row weights, and tap bounds unchanged. Use the same `KeyboardLayout` geometry for key drawing and swipe recognition.
- [x] Draw the swipe trail above key faces, keep key labels legible, and fade the trail in about 210 ms after commit/cancel.
- [ ] Match the three embedded image references and the V7 prototype at the baseline emulator viewport. Capture Settings screenshots for resting QWERTY, Hebrew, and active swipe trail; inspect key positions, strip height, text sizes, contrast, caret and clipping.
- [x] Defer the focused Settings IME instrumentation test and screenshot review to Task 9, per user request. Do not claim visual parity from this build-only pass.

**Produces:** the approved graphite/silver/cyan keyboard and sentence strip at rest, with the existing keyboard layout preserved.

### Task 6: Add cursor interaction, focus scrolling, and alternative swapping

**Files:**

- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStrip.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStripGeometry.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

- [x] Attach one stable `pointerInput` detector to the strip. Do not key detector identity on changing preview/state values. Use `rememberUpdatedState` for callbacks so every held gesture survives its own recompositions.
- [x] On a short tap, map text to the nearest legal UTF-16 grapheme boundary and map a gap target to its nearest legal boundary; call `SentenceStripActions.setSelection` immediately.
- [x] On a hold, set the cursor under the initial finger location. While held, horizontal movement updates the host selection at the character below the pointer before it enters an edge zone.
- [x] Handle horizontal strip swipes separately from vertical word gestures. Scroll continuously with clamped content bounds and no wrap. When the cursor or focused word changes, bring it into view quickly; center it when the available preceding/following sentence content permits and clamp at either sentence edge.
- [x] On vertical motion over an alternative, render a full-sentence preview and animate the selected candidate into the current row in 165 ms with an ease-out and no bounce. On release, commit once; place the previous word into the chosen alternative side so the next same-direction swipe swaps it back.
- [x] On pointer cancellation or release with no preview, clear preview without changing host text, selection, or history. Keep the opposite-side option intact and candidates non-wrapping.
- [x] Defer the connected STRIP-02…09, STRIP-20, and STRIP-21 journeys to Task 9, per the user’s request. Keep preview non-mutation and cursor synchronization covered by pure/controller tests.

**Produces:** reliable word/character/gap cursor setting, horizontal browsing, focus-centered scrolling, and reversible upper/lower correction swaps.

### Task 7: Add atomic split/join previews and midpoint deletion

**Files:**

- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStrip.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStripGeometry.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SwipeInferenceTransaction.kt`
- Reuse: `ReplacementOption`, `SentenceStripActions.commitReplacement`
- Test: `app/src/test/kotlin/com/iaido/app/SwipeTypingCoordinatorTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/SwipeInferenceTransactionTest.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`, `ImeInferenceE2eTest.kt`

- [x] Accept an alternative as a split/join only when its `ReplacementOption` source span includes the corresponding source words. Apply a normal one-word choice such as “inside” to only that word and preserve the following “to”.
- [x] Preview `alot` → `a lot` as two ordinary lanes with normal spacing. Preview `in to` → `into` from either source lane as one joined word centered in the union of both source lanes and their gap. Do not add a third lane or shift neighboring geometry.
- [x] Keep `InputConnection` unchanged until release. On release, validate the full option and source IDs/offsets against the latest service state, then commit one range replacement and one history entry.
- [x] Remove host edits from `SwipeTypingCoordinator.previewReplacement`. Preview validates and publishes the candidate without calling `SwipeInferenceTransaction.replaceCurrent` or the host callback; release applies the selected inferred words exactly once; cancel clears preview without restoring text.
- [x] Switch from alternative selection to deletion only after a vertically armed pointer crosses from its source lane into a neighboring lane. Select that neighbor only after its midpoint is crossed. Returning over the original hit target restores the vertical alternative preview.
- [x] While deletion is previewed, strike through only selected word text, hide those words’ alternatives, and draw one red rectangle around the selected contiguous range. Pulling back across a midpoint removes that word from preview. Release deletes the validated range and preserves surrounding punctuation/spaces.
- [x] Existing deterministic `SPLIT_ALOT`, `JOIN_REEL`, and `inside` fixture data already makes each expected choice unique; no fixture edits were needed.
- [x] Defer connected STRIP-10…17, STRIP-13a, and `ImeInferenceE2eTest` to Task 9, per the user’s request. Validate preview and transaction boundaries with JVM tests.

**Produces:** correct split/join preview geometry and a reversible midpoint-based deletion gesture.

### Task 8: Implement continuous edge scrolling and complete undo/redo interaction

**Files:**

- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStrip.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SentenceStripGeometry.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SentenceEditHistory.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`

- [x] Add left/right edge zones with no border and no red. Use a subdued cyan gradient from transparent inward to 32% opacity at the outside, plus a slim chevron. Keep the 44 dp touch zone narrower than the prior 14%-wide prototype zone; the visual overlay does not intercept touches.
- [x] Drive scrolling from elapsed frame time (`withFrameNanos`). For normalized penetration `p` into the edge zone, apply `24 + 780 * p²` CSS pixels/second converted with display density. Clamp frame delta after a pause and clamp content offset at both ends.
- [x] Keep the finger fixed in viewport coordinates while edge-scrolling. Recompute the selected character or crossed word midpoint from shifted geometry every frame; cursor scrubbing and deletion continue in the scroll direction without wrapping.
- [x] Show an edge affordance only during a held cursor/deletion gesture in its zone. Fade in/out in about 110 ms; keep the arrow visually clear and the region subtle.
- [x] Finish the 40-entry history module integration. Tapping an enabled undo/redo icon applies one step immediately. Holding for 380 ms reveals the action and before/after text; release applies that step, while cancellation dismisses it. Disabled controls remain visible, grey, and inert.
- [x] Ensure undo/redo restore editor text, the InputConnection selection, sentence spans, and strip caret together. A fresh edit after undo clears redo only; the independent stack availability and previews are reflected immediately in `SentenceStripState`.
- [x] Defer connected STRIP-18, STRIP-19, STRIP-22…24, and RTL variants to Task 9, per the user’s request. Cover edge-rate math and history state transitions with fast JVM tests.

**Produces:** frame-rate-stable edge scrolling for both cursor and deletion gestures, and usable multi-step undo/redo controls.

### Task 9: Compare native screenshots and close the full acceptance matrix

**Files:**

- Modify: `app/src/androidTest/kotlin/com/iaido/app/SettingsImeReelE2eTest.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeInferenceE2eTest.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/SentenceStripImeDriver.kt`
- Retire: remaining box-only tests and helpers after confirming no other callers

- [ ] Run all connected STRIP-01…STRIP-27 journeys with real Settings/IME pointer injection. Include English and Hebrew, regular/reduced motion, short “it” hit targets, long sentences, both join directions, split, deletion pullback, and both edge zones.
- [ ] Capture native screenshots at rest and while held for: caret/word focus; upper/lower swap; split; forward/backward join; one- and multi-word deletion; left/right edge scroll; undo/redo preview; swipe trail; QWERTY and Hebrew. Store named artifacts through the existing `ArtifactWriter`/`FailureArtifactRule` path.
- [ ] Capture or open V7 at the emulator’s same logical viewport and compare it with the three embedded phone references. Normalize only status/navigation bars and known system insets. Inspect text size/contrast, 4-pixel gap, lane and join centering, caret alignment, deletion borders, edge opacity/width/chevron, keycap borders, and swipe-trail layering. Do not mask content that the contract requires to align.
- [ ] Review paired prototype/native screenshots at 100% and with a 50% opacity overlay. Record any visible mismatch by state and correct it before acceptance; retain the final native captures and the emulator size/density with the test artifacts.
- [ ] Run `./gradlew --no-daemon --max-workers=2 :core-engine:test :app:testDebugUnitTest` and `./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest`.
- [ ] Run the full emulator instrumentation suite with `./gradlew --no-daemon --max-workers=2 :app:connectedDebugAndroidTest`. Report a device or host timeout as unresolved; do not count it as a pass.
- [ ] Re-read both specs against every STRIP row, verify reduced-motion behavior and privacy exclusions, run `git diff --check`, and confirm no old box-reel assertion remains as a target-state requirement.
- [ ] Commit the final acceptance/screenshot updates only after the whole JVM, APK-build, and connected-emulator evidence is recorded.

**Produces:** verified native screenshots and real-IME coverage that show the implemented keyboard matches the approved prototype and supplied references.

## Completion Gate

Keep this checklist current as implementation proceeds. The branch is complete only after the native behavior, visual comparison, JVM tests, APK builds, and connected emulator acceptance matrix above all pass.
