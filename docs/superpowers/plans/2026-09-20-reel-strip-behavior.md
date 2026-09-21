# Reel and Suggestion-Strip Behavior Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved reel/strip behavior contract so every tracked sentence word has one visible, bounded, cursor-following reel; word replacement is atomic; split and join candidates have the specified geometry and lifecycle; sentence context updates neighboring reels; and infer-space/two-finger ordering and timing are deterministic and tested through the real IME.

**Architecture:** Keep gesture recognition and language scoring pure in `core-engine`. Extend the gesture event model to preserve path order and touch-down timestamps, then make `InferenceSegmenter` enumerate the observed order plus every required one-pair swap within one multi-path event. Keep sentence/reel identity in `SessionCorrectionHistory` and add an explicit composite-reel state for selected split replacements. Make the app service the single owner of editor spans, cursor focus, context-window recalculation, and hard-boundary finalization. Keep `SuggestionStrip` responsible only for rendering the normalized sentence-reel model, with stable semantics and geometry helpers used by connected tests. Use deterministic debug fixtures and real `ImeScenario` Settings/IME tests for acceptance.

**Tech Stack:** Kotlin, Android IME `InputConnection`, Compose UI, Compose semantics, JUnit 5 core/app tests, Android instrumentation, UiAutomator, the existing `ImeScenario` pointer injector, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-20-reel-strip-behavior.md`

## Global Constraints

- Treat the approved behavior contract as the source of truth. Do not weaken a failing test by asserting only final editor text when the contract requires reel count, candidate text, focus, or geometry.
- Preserve `.ci-art/` and all unrelated working-tree changes. Do not reset, clean, or stage unrelated files.
- Keep production package identity and the debug identity requirements explicit: the debug IME must remain visibly `Iaido Debug`, and debug Settings must remain `Iaido Debug Settings`.
- Use one shared context-window constant for neighboring reel recalculation. The initial implementation value is `N = 3`, matching the existing `aroundCursor(..., maxWords = 3)`/recent-context behavior; expose it from one documented configuration point rather than duplicating literal `3`s.
- Bound multi-path gesture enumeration. Accept one to four paths in a single logical gesture, preserve the real paths, and generate the observed assignment plus one swapped assignment for every unordered path pair. Do not generate unbounded permutations or reorder separate gesture events.
- Define timing weight once, independently of fixtures: normalize pair touch-down delta against the split-session grace window, `closeness = 1 - clamp(delta / graceWindow, 0, 1)`, and use that value only to scale frequency/context evidence for multi-path hypotheses. Path-fit evidence remains unchanged.
- Every newly exposed UI semantic must contain stable reel identity and candidate text. Tests may use screenshots as evidence, but structural assertions must come from semantics and bounds.
- Synchronize connected tests by polling actual editor/strip state. Fixed sleeps may be used only as a bounded retry interval, never as the only synchronization mechanism.
- Run unit/core tests before connected tests. A connected test is reported as passed only with an actual instrumentation result; emulator and phone results are reported separately.

## Files and Responsibilities

### Core gesture and inference files

Modify:

- `core-engine/src/main/kotlin/com/iaido/core/recognition/GestureUnit.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/SplitGestureSession.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/InferenceSegmenter.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/SegmentationOption.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/SessionCorrectionHistory.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/SessionCorrectionHistorySnapshot.kt`

Create:

- `core-engine/src/main/kotlin/com/iaido/core/recognition/MultiPathOrderHypothesis.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/SentenceCandidateReranker.kt`
- `core-engine/src/main/kotlin/com/iaido/core/recognition/ReelGroup.kt`

Tests:

- `core-engine/src/test/kotlin/com/iaido/core/recognition/SplitGestureSessionTest.kt`
- `core-engine/src/test/kotlin/com/iaido/core/recognition/InferenceSegmenterTest.kt`
- `core-engine/src/test/kotlin/com/iaido/core/recognition/SessionCorrectionHistoryTest.kt`
- `core-engine/src/test/kotlin/com/iaido/core/recognition/SentenceCandidateRerankerTest.kt` (new)
- `core-engine/src/test/kotlin/com/iaido/core/recognition/MultiPathOrderHypothesisTest.kt` (new, if the production seam is extracted)

### App coordination, editor spans, and debug fixtures

Modify:

- `app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt`
- `app/src/main/kotlin/com/iaido/app/SwipeInferenceTransaction.kt`
- `app/src/main/kotlin/com/iaido/app/SplitTypingController.kt`
- `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- `app/src/main/kotlin/com/iaido/app/TypedWordTracker.kt`
- `app/src/main/kotlin/com/iaido/app/TypedWordCandidates.kt`
- `app/src/main/kotlin/com/iaido/app/HistoryJoinCandidates.kt`
- `app/src/debug/kotlin/com/iaido/app/DebugAutoSpaceFixtures.kt`
- `app/src/debug/assets/auto-space-fixtures.txt`
- `app/src/androidTest/kotlin/com/iaido/app/ImeScenarioData.kt`

Tests:

- `app/src/test/kotlin/com/iaido/app/SwipeTypingCoordinatorTest.kt`
- `app/src/test/kotlin/com/iaido/app/SwipeInferenceTransactionTest.kt`
- `app/src/test/kotlin/com/iaido/app/SplitTypingControllerTest.kt`
- `app/src/test/kotlin/com/iaido/app/TypedWordTrackerTest.kt`
- `app/src/test/kotlin/com/iaido/app/HistoryJoinCandidatesTest.kt`

### Strip rendering and test observability

Modify:

- `app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt`
- `app/src/main/kotlin/com/iaido/app/SuggestionReelMath.kt`
- `app/src/main/kotlin/com/iaido/app/ReplacementJoinAttachment.kt`
- `app/src/main/kotlin/com/iaido/app/ReplacementReelLayout.kt`
- `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt`

Tests:

- `app/src/test/kotlin/com/iaido/app/SuggestionReelMathTest.kt`
- `app/src/test/kotlin/com/iaido/app/ReplacementJoinAttachmentTest.kt`
- `app/src/test/kotlin/com/iaido/app/ReplacementReelLayoutTest.kt`
- `app/src/androidTest/kotlin/com/iaido/app/SuggestionStripVisibilityTest.kt`

### Real IME acceptance tests

Modify or create:

- `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`
- `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt` (new if absent)
- `app/src/androidTest/kotlin/com/iaido/app/ImeInferenceE2eTest.kt`
- `app/src/androidTest/kotlin/com/iaido/app/SettingsImeReelE2eTest.kt`
- `app/src/androidTest/kotlin/com/iaido/app/FailureArtifactRule.kt` only if screenshot naming or state capture needs a narrowly scoped extension

## Implementation Tasks

## Task 1: Freeze the contract and establish test seams

- [ ] Update the approved spec header from `Draft for sign-off` to an approved status and record the implementation choices: `N = 3`, one-to-four paths per logical multi-path event, pairwise one-swap enumeration, continuous timing formula, and whitespace focus rule. Leave the behavioral tables unchanged except where a test ID needs an exact fixture name.
- [ ] Add a small test-only `ReelStripSnapshot`/semantic parsing helper in `ImeScenario` that reads the real suggestion-strip node tree, extracts ordered reel IDs, candidate text, `stateDescription`, and bounds, and fails if a reel node exists without candidate text. Keep this helper independent of guessed coordinates.
- [ ] Add a shared `awaitImeState` polling helper that checks editor text, cursor, ordered reel IDs, and expected candidate text; have it capture a named screenshot after the state is reached. Make timeout failures include the last observed state and screenshot path.
- [ ] Run the existing core/app unit suite and the four existing `SuggestionStripVisibilityTest` cases as the baseline. Record the exact commands and results in the execution notes, but do not claim the new contract is implemented yet.

## Task 2: Preserve multi-path metadata and implement pairwise ordering

- [ ] Extend `SplitGestureSession.ActivePart` and `CompletedPart` with `touchDownAtMs`. Extend `SplitWordParts` with a timestamp list aligned one-to-one with `parts` and `paths`; validate equal lengths and nondecreasing touch-order alignment. Keep the existing sorted touch-down order as the observed assignment.
- [ ] Replace the current two-path-only validation in `GestureUnit`/`SwipeTypingCoordinator` with a bounded one-to-four-path multi-path event model. Keep single swipes on the existing fast path. Reject malformed candidate/path/timestamp lengths at the boundary with a deterministic failed-gesture outcome.
- [ ] Implement `MultiPathOrderHypothesis` as the pure helper that returns the observed path/candidate assignment, one swapped assignment for every unordered path pair, the pair identity, and the measured touch-down delta. Deduplicate identical candidate/order hypotheses while retaining the best score provenance. Explicitly cover non-edge pairs in a three-path event and do not combine paths from separate events.
- [ ] Refactor `InferenceSegmenter.rawCandidatesFor` so it no longer assumes exactly two concurrent paths or only `rankedPaths[0]`/`[1]`. For each `MultiPathOrderHypothesis`, build both the merged-word and boundary-preserving word alternatives using the existing dictionary/frequency and path candidates, then score with path fit plus the timing-weighted frequency/context contribution.
- [ ] Add a documented timing helper with the global formula from `Global Constraints`. Pass the actual grace-window value through the event rather than reading wall-clock time again during ranking, so tests and production use the same measured delta.
- [ ] Preserve `sourceGestureIds` and add enough hypothesis metadata to `SegmentationOption` for tests/diagnostics to identify observed versus swapped pair decisions without changing the visible candidate contract.
- [ ] Thread timestamps from `IaidoInputMethodService.onSplitEnd` through `SplitTypingController.pollParts`, `SwipeTypingCoordinator.onRecognizedTwoFingerResult` (rename to a multi-path API), and `SwipeInferenceTransaction`. Keep a compatibility wrapper only where existing tests or callers need it, and make the new API the production path.
- [ ] Add RED tests before implementation for: two-path observed-versus-swapped language preference; all three pair swaps in a three-path event; a non-adjacent/internal pair; no cross-event reordering; near versus wide touch-down timing; malformed metadata; and deterministic ties. Run the focused core tests to prove they fail for the old behavior, implement the model, then run them green.

## Task 3: Normalize sentence words, atomic replacement, and composite split lifecycle

- [ ] Make `SessionCorrectionHistory` the normalized source of sentence reels: every word entry has one stable ID/span/current text/candidate list, including one-character typed words. Add APIs to update candidates in place by ID, replace a range with independently addressable words, and query all entries in sentence order without the old cursor-local truncation.
- [ ] Add explicit composite split metadata rather than inferring it from a transient `ReplacementOption`. A composite group must identify its source span, output word IDs, and source/replacement words. Expose a method to break a group into independent reels while preserving the current editor spans and candidate lists.
- [ ] Version and validate the correction-history snapshot if the new metadata is persisted. Update `KeyboardStateJsonCodec` and snapshot tests so restoring state cannot create duplicate IDs, overlapping composite spans, or empty groups. If the metadata is deliberately session-only, document that choice and clear it on session reset rather than silently serializing partial state.
- [ ] Change `IaidoInputMethodService.refreshSuggestionChips` to build the strip from the full ordered sentence history, not only a cursor-local subset. Apply the deterministic whitespace focus rule and use the history entry ID as the stable reel key. Keep `visibleWordIds` in the same canonical LTR order and only reverse at rendering time for RTL.
- [ ] Route every typed character through one update-or-replace operation for the current typed span. The operation must refresh candidates after each letter, preserve the same reel ID, and retain the one-letter current word even when there are no alternatives. Add a stale-span check before recording so external edits cannot create a duplicate entry.
- [ ] Route ordinary replacement through one atomic `replaceSessionWord` path that sets the exact source selection, commits the full replacement, updates the same history entry, fixes the caret to `start + replacement.length`, and triggers context recalculation. Add assertions/logging in debug builds for a commit that would leave a stale suffix.
- [ ] When an inference replacement produces multiple words, record the output as one composite group for the initial selected state while retaining per-word spans internally. On any typed edit, deletion, explicit cursor move, or external host edit intersecting one member, call `breakCompositeGroup`, refresh independent chips for every surviving word, remove deleted-word entries, and never leave a zero-length reel.
- [ ] Add RED unit tests for `hey -> heyday -> hey`, one-letter typing, same-span typed refresh without duplicate IDs, split selection followed by edit/delete, exact caret placement, and stale external edit handling. Implement the history/service changes and run the focused app/core tests green.

## Task 4: Sentence-aware neighboring reel recalculation

- [ ] Implement `SentenceCandidateReranker` as a pure operation over an ordered `SessionWord` snapshot. Given a changed word ID, rebuild candidate ordering for indices `[changedIndex - N, changedIndex + N]` using current sentence words, dictionary frequency, n-gram context, and each entry's existing candidate pool plus valid typed/join/split candidates. Do not scan or mutate words outside the window.
- [ ] Define deterministic tie-breaking: current word first when scores tie, then original candidate order, then lexical fallback. Preserve the currently selected/current word if still valid; otherwise insert the current word as the selected fallback. Return affected IDs so the UI can refresh in place rather than reconstructing duplicate chips.
- [ ] Call the reranker after manual reel replacement, join release, split release/break, infer-space reanalysis, flow correction, and any typed-letter candidate refresh that changes the active word. Ensure one update does not recursively trigger another editor replacement or move the cursor unexpectedly.
- [ ] Update `FlowCorrectionEngine` integration so its correction result and the strip's candidate refresh use the same sentence snapshot. The displayed candidate score/text must reflect the post-change sentence before the next user gesture.
- [ ] Add RED tests proving a middle-word change updates exactly N neighbors on each side, changes a fixture-visible candidate/ranking, leaves an outside word unchanged, preserves valid selection, and falls back when the selected candidate disappears. Implement and run the core/app tests green.

## Task 5: Render exactly one bounded reel per sentence word

- [ ] Refactor `SuggestionStrip` input preparation so normal sentence chips, live inference options, historical joins, and split groups are normalized before rendering. Deduplicate by stable sentence-word/group identity, not by display text. Do not append live inline reels when their source word already has the sentence reel.
- [ ] Make the reel candidate list always contain the current word. Calculate `visibleSlotCount` from the actual candidate count and cap it at `MAX_REEL_VISIBLE_SLOTS`; calculate the strip height from rendered reels only. Remove any layout path that reserves a fixed number of rows or a replacement slot after its last real candidate.
- [ ] Keep the reel column clipped to its bounded viewport while ensuring the selected/current row is translated into the center slot and its text is fully contained. Clamp drag offsets to the actual last candidate, and make a drag past the final candidate settle on the last real row rather than an empty hit target.
- [ ] Add stable semantics to the reel root and each visible candidate: reel ID, source word, candidate index, candidate text, selected index, and total candidate count. Expose the displayed row's text even when it is not the selected row so UiAutomator can assert the four-step scroll result.
- [ ] Add `ReelBounds`/union helpers to `SuggestionReelMath` and make the join renderer use them. A two-word join row must have one semantic target whose bounds are the union of both source reel slots plus one gap, with direction determined by the centered source reel. Keep the union bounded by the strip viewport.
- [ ] Make cursor focus use the history ID, not chip position. On focus changes, scroll the `LazyRow` to the matching ID in the current LTR/RTL order and reset only the affected reel's vertical state when its candidate identity changes.
- [ ] Add RED unit/Compose tests for one-row/two-row/three-row heights, four-option visibility and bounds containment, no selectable blank rows, deduplication, full-sentence ordering, cursor back/forward focus, split attached candidate, join forward/backward union geometry, and removal of stale join/split attachments. Implement and run the focused tests green.

## Task 6: Deterministic fixtures and real IME test helpers

- [ ] Extend `AutoSpaceFixture` and `auto-space-fixtures.txt` with explicit fixture data for: typed one-character and typed multi-letter reels; `hey/heyday`; `imo/I mo`; join from both source sides; context-neighbor score changes; split-edit/delete; three-path pairwise ordering; near/far timing; typed-before/after multi-path; cursor movement; and no-invisible-row candidates. Keep frequencies and bigrams distinct enough that the expected choice is observable in candidate text, not just score.
- [ ] Add `ImeScenario` methods to: select a debug fixture; inject one or more paths with explicit touch-down timestamps/order; type letters one at a time; move the cursor by host selection; scroll a named reel exactly four candidate steps; find a reel/candidate by semantics; assert ordered reel IDs; assert text/bounds/containment; and capture a settled Settings screenshot.
- [ ] Make the helper fail loudly when the strip node is missing or stale. It must re-read after a real editor/strip update and include the current node descriptions/bounds in the failure message; it must not fall back to a hard-coded coordinate for a semantic assertion.
- [ ] Add a real Settings-screen launch path that selects the separately named debug IME, types into the live preview, waits for the expected preview text and reel semantics, and captures screenshots from that screen. Keep the existing Compose-only visibility tests as fast layout tests, not acceptance evidence.
- [ ] Add RED tests for helper parsing against the current semantics, then update production semantics and fixtures until the helper tests pass before writing the full E2E matrix.

## Task 7: Implement the contract E2E matrix

- [ ] Add `ImeReelE2eTest` coverage for E2E-01 through E2E-09: atomic replacement, per-letter typed reels, one-character reel, full sentence/no duplicates, cursor back/forward, off-screen focus, four-option visibility, bounded height, and the invisible-option regression.
- [ ] Add E2E-10 through E2E-13: `imo` with one `I mo` candidate attached to one source reel; middle-word context updates; repeated replacement using new context; and refresh while horizontally scrolled. Assert editor text/caret plus exact ordered reel semantics and changed candidate text.
- [ ] Add E2E-14 through E2E-21: join candidate on both source reels, forward/backward visual union, atomic release from either reel, stale join removal, sentence-edge joins, cursor traversal after updates, and real Settings screenshots for each visual state.
- [ ] Add E2E-22 through E2E-25: split selected then edited/deleted, independent remaining reels, later correction of one split word, and typed/cursor interaction on the real Settings preview. Capture both pre-break composite and post-break independent states.
- [ ] Expand `ImeInferenceE2eTest` with INF-01 through INF-19. For INF-08 through INF-11, use identical path geometry with swapped touchdown assignments, a three-path event proving every pair is tested, an internal/non-edge pair, and near/far timestamps. Assert selected text segmentation and visible reel geometry after each reanalysis.
- [ ] Add explicit hard-boundary assertions for INF-12 through INF-16: typing, space/punctuation, cursor movement, cancellation, and external edit finalize/clear the inference run; the next gesture cannot rewrite across the boundary; temporary composite reels disappear on cancel.
- [ ] Keep INF-17's six-unit window bounded and verify the oldest reel freezes while only the allowed recent units update. Keep INF-18's after-swipe assertion at exactly one trailing space for a complete multi-path gesture. INF-19 must retain screenshots from the real Settings/IME surface for every state transition.

## Task 8: Verification, review, and delivery

- [ ] Run formatting/static checks applicable to changed Kotlin files.
- [ ] Run core and app unit tests with:
  `./gradlew --no-daemon --max-workers=2 :core-engine:test :app:testDebugUnitTest`
- [ ] Build debug APK and test APK with:
  `./gradlew --no-daemon --max-workers=2 :app:assembleDebug :app:assembleDebugAndroidTest`
- [ ] Install the debug APK under the intended `Iaido Debug` package/name, enable it in the emulator's IME selector, and run the focused connected suites first: `SuggestionStripVisibilityTest`, `ImeReelE2eTest`, `ImeInferenceE2eTest`, and `SettingsImeReelE2eTest`.
- [ ] Re-run the full Android instrumentation suite on the emulator. Collect logcat, `dumpsys input_method`, screenshots, and the test report directory. Report any physical-device timeout separately; do not convert a timeout into a pass.
- [ ] Inspect every generated screenshot for the actual Settings preview, visible candidate text, reel count/order, focused word, join union, and split-break state. Delete only newly generated temporary artifacts that are inside the task output directory; preserve `.ci-art/`.
- [ ] Perform a final diff review against the approved spec: every numbered contract section has at least one production assertion and one regression test; no placeholder text remains; no test depends on a guessed coordinate or fixed sleep alone; no duplicate/stale span path remains.
- [ ] Commit coherent groups only after verification: (1) core gesture/inference model, (2) sentence/reel state and service coordination, (3) strip geometry/semantics, (4) fixtures and connected tests. Verify each commit and the remote branch state before any push requested by the user.

## Expected Verification Matrix

| Layer | Required proof | Command / artifact |
| --- | --- | --- |
| Core scoring | Pairwise observed/swapped hypotheses, every pair in 3-path event, timing weighting, no cross-event reorder | `:core-engine:test`; focused `InferenceSegmenterTest` report |
| History/service | Exact-span replacement, per-letter typed reel, composite split break, neighbor rerank, cursor focus | `:app:testDebugUnitTest`; focused service/coordinator tests |
| Compose layout | Actual row count/height, no blank hit rows, focused-row containment, join union geometry | `:app:connectedDebugAndroidTest --tests ...SuggestionStripVisibilityTest` |
| Real IME | Text/caret plus ordered reels, candidate text, bounds, split/join lifecycle, typed/two-finger boundaries | Emulator instrumentation reports and failure screenshots |
| Settings acceptance | Screenshots show live Settings preview with the real debug IME and visible reels | `SettingsImeReelE2eTest` screenshots, logcat, `dumpsys input_method` |

## Self-Review Checklist Before Execution

- [ ] Every source file named in a task exists or is explicitly marked as a new file.
- [ ] Every new production API has a corresponding unit test before its implementation step.
- [ ] Every E2E ID in the approved contract is assigned to a concrete test class/helper.
- [ ] The plan never relies on an unbounded permutation search, a cursor-local reel subset, or a fixed three-row layout.
- [ ] The plan distinguishes diagnostic screenshots from semantic/geometry assertions.
- [ ] The plan preserves existing debug naming and does not touch unrelated `.ci-art/` content.
