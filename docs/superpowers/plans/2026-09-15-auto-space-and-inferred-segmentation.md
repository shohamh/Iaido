# Auto-space and Inferred Segmentation Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Add manual, release-auto-space, and context-inferred spacing modes, including two-finger Nintype-style input, mutable recent segmentation, structured split/join reels, and real IME E2E coverage.

**Architecture:** Keep recognition and bounded segmentation in the pure Kotlin core-engine. Add an app-side inference transaction that owns the exact InputConnection span and replaces it when later swipes change the best segmentation. Represent reel alternatives as structured replacement groups so one option can replace one source word with several words or several source words with one word.

**Tech Stack:** Kotlin/JVM, Android InputMethodService, Jetpack Compose, DataStore Preferences, JUnit 5, AndroidX instrumentation, UiAutomator, and the existing pointer-injection E2E harness.

**Spec:** docs/superpowers/specs/2026-09-15-auto-space-and-inferred-segmentation-design.md

## Global Constraints

- Default missing preference: Infer spaces.
- Modes are mutually exclusive: Manual spacing, Space after swipe, Infer spaces.
- Two concurrent swipe paths support the existing Nintype-style merged word and a boundary-preserving alternative.
- A physical finger lift is not itself a committed space; a two-finger gesture produces at most one trailing space.
- Inference is bounded to six recent gesture units and at most three output dictionary words.
- Inference uses dictionary validity, frequency, path fit, and n-gram context.
- Low-confidence alternatives do not rewrite the current interpretation.
- Non-swipe input, explicit space/punctuation, cursor movement, external edits, and lifecycle changes finalize and clear inference.
- Reel selection previews a complete replacement and commits on release; cancellation restores prior text.
- Automatic replacements only affect Iaido text from the current IME session.
- Connected tests drive the real Compose IME and verify the separate debug host editor.
- Do not stage unrelated WIP or generated caches.

---

### Task 1: Rebase onto the merged dev baseline

**Files:** none initially; inspect Git only.

**Produces:** feature/auto-space based on the current dev tip after the other worktree has been merged.

- [ ] Confirm the other worktree landed: run git fetch --all --prune, git log --oneline --decorate --max-count=12 dev, and git worktree list. Record the merged commit.
- [ ] In .worktrees/auto-space run git status --short, git merge-base HEAD dev, and git rebase dev. Preserve approved design commits and resolve only relevant conflicts.
- [ ] Run .\gradlew.bat :core-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain and git diff --check. Stop if the merged dev baseline fails.
- [ ] If conflict resolution changed tracked files, commit only those files with message chore: rebase auto-space work onto dev; if there were no conflicts, create no empty commit.

### Task 2: Add the spacing mode contract and persistence

**Files:**
- Create core-engine/src/main/kotlin/com/iaido/core/typing/SpacingMode.kt.
- Modify app/src/main/kotlin/com/iaido/app/SettingsActivity.kt.
- Modify app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt.
- Test app/src/test/kotlin/com/iaido/app/SpacingModeSettingsTest.kt.

**Interfaces:**
- SpacingMode is an enum with MANUAL, AFTER_SWIPE, and INFER_SPACES.
- spacingModeFromStoredValue(value: String?): SpacingMode maps missing/unknown values to INFER_SPACES.
- spacingModeStoredValue(mode: SpacingMode): String returns stable values manual, after_swipe, and infer_spaces.

- [ ] Write tests for round trips, missing/unknown default, labels, and mutually exclusive selection.
- [ ] Run .\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.SpacingModeSettingsTest --no-daemon --console=plain; verify the new test fails before implementation.
- [ ] Add one DataStore key spacing_mode, the enum/mapping, and a Settings segmented/radio control labeled Manual spacing, Space after swipe, and Infer spaces.
- [ ] Load the same key at IME input-view/session startup and on recreation; pass the resolved mode to the typing coordinator.
- [ ] Run the focused test and commit with message feat: add persisted spacing modes.

### Task 3: Implement bounded core segmentation

**Files:**
- Create core-engine/src/main/kotlin/com/iaido/core/recognition/GestureUnit.kt.
- Create core-engine/src/main/kotlin/com/iaido/core/recognition/SegmentationOption.kt.
- Create core-engine/src/main/kotlin/com/iaido/core/recognition/InferenceSegmenter.kt.
- Modify core-engine/src/main/kotlin/com/iaido/core/recognition/SplitWordMerger.kt.
- Test core-engine/src/test/kotlin/com/iaido/core/recognition/InferenceSegmenterTest.kt.

**Interfaces:**
- GestureUnit retains a stable ID, one or two paths, ranked recognized candidates, and concurrency.
- SegmentationOption contains words: List<String>, score: Double, and source gesture IDs.
- InferenceSegmenter.rank(units, previousWords, dictionary) returns ranked SegmentationOption values.

- [ ] Write failing deterministic tests for sequential split, sequential join, one-gesture split, two-finger merge, two-finger boundary preservation, context preference, margin fallback, six-unit bound, and three-word bound.
- [ ] Run .\gradlew.bat :core-engine:test --tests com.iaido.core.recognition.InferenceSegmenterTest --no-daemon --console=plain; verify failure is caused by missing interfaces.
- [ ] Implement dynamic programming over bounded units. Include ranked path candidates, SplitWordMerger merged candidates, boundary-preserving two-finger candidates, dictionary validity, frequency, path score, and NgramContextScorer.
- [ ] Apply deterministic tie-breaks: score descending, then fewer output words, then dictionary order. Retain only bounded top alternatives.
- [ ] Run the focused test and commit with message feat: rank bounded swipe segmentations.

### Task 4: Add the app inference transaction and coordinator

**Files:**
- Create app/src/main/kotlin/com/iaido/app/SwipeInferenceTransaction.kt.
- Create app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt.
- Modify app/src/main/kotlin/com/iaido/app/SwipeCommitController.kt.
- Modify app/src/main/kotlin/com/iaido/app/SplitTypingController.kt.
- Modify app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt.
- Tests app/src/test/kotlin/com/iaido/app/SwipeTypingCoordinatorTest.kt and SwipeInferenceTransactionTest.kt.

**Interfaces:**
- Transaction exposes units, currentWords, sourceSpan, alternatives, append, replaceCurrent, finalize, and clear.
- Coordinator exposes onSingleSwipe(path, layout), onTwoFingerResult(parts, layout), onNonSwipeInput(), onCursorMoved(), onExternalEdit(), and poll(atMs).
- Coordinator callbacks cover recognition, previous context, exact-span replacement, trailing-space append, and finalized-word recording.

- [ ] Write failing tests for all three modes, one trailing space after a two-finger unit, exact-span replacement, one-to-two and two-to-one rewrites, cursor delta, six-unit freeze, invalidation, and failed/cancelled input.
- [ ] Run both focused test classes and verify they fail before implementation.
- [ ] Implement one replaceable host-text span. Resolve SplitGestureSession grace-window output before deciding spacing; never insert a space on individual pointer-up events.
- [ ] Finalize before taps, punctuation, explicit space, backspace, commands, cursor movement, external edits, and session changes. Guard async results by session ID and language.
- [ ] Run focused tests and commit with message feat: coordinate automatic swipe spacing.

### Task 5: Extend reel state for structured split/join replacements

**Files:**
- Create core-engine/src/main/kotlin/com/iaido/core/recognition/ReplacementOption.kt.
- Modify core-engine/src/main/kotlin/com/iaido/core/recognition/SuggestionStripState.kt.
- Modify app/src/main/kotlin/com/iaido/app/SuggestionStrip.kt and SuggestionReelMath.kt.
- Modify app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt and androidTest/kotlin/com/iaido/app/ImeScenario.kt.
- Tests for core SuggestionStripState, app SuggestionReelMath, and app ReplacementReelLayout.

**Interfaces:**
- ReplacementOption contains sourceWords, replacementWords, score, and stable ID.
- Strip state preserves source/replacement cardinality and selection without joining words into an opaque string.
- Compose release returns a complete ReplacementOption; cancellation returns no mutation.

- [ ] Write tests for one-to-one, two-to-one wide joined reel, one-to-two and one-to-three coordinated reels, RTL order, release, cancellation, accessibility labels, and cursor/span updates.
- [ ] Run focused core/app tests and verify they fail before implementation.
- [ ] Preserve ordinary one-to-one correction chips; add replacement-group state for inference alternatives.
- [ ] Render joined options as one wide reel spanning both source slots and split options as adjacent coordinated reels in a horizontally scrollable LazyRow.
- [ ] Add accessibility descriptions containing source count, replacement count, current words, and candidate position; add ImeScenario drag/release/cancel helpers.
- [ ] Run focused tests and commit with message feat: render split and joined inference reels.

### Task 6: Add deterministic connected E2E coverage for every mode

**Files:**
- Create app/src/androidTest/kotlin/com/iaido/app/ImeSpacingModesE2eTest.kt.
- Create app/src/androidTest/kotlin/com/iaido/app/ImeInferenceE2eTest.kt.
- Modify ImeScenario.kt, ImeScenarioData.kt, PointerInjector.kt, and SettingsUpdateE2eTest.kt.
- Modify debug ImeTestHostActivity.kt only if a deterministic fixture hook is required.

- [ ] Add debug-only dictionary/n-gram fixtures for separate words, joinable words, splittable words, context revision, and two-finger ambiguity; keep production assets unchanged.
- [ ] Add a real Settings journey for each mode, then recreate/restart the keyboard and verify persistence.
- [ ] Add Manual spacing journey: sequential swipes produce no automatic spaces.
- [ ] Add Space after swipe journey: single-finger swipes add spaces and a two-finger merged swipe adds exactly one.
- [ ] Add Infer spaces journeys: separate, join, split, later reanalysis, two-finger merge, two-finger boundary preservation, six-unit freeze, and no-confidence fallback.
- [ ] Add reel assertions for joined/split accessibility labels, release-to-commit, cancellation, final text, and cursor position.
- [ ] Run the focused connected suite with .\tools\run_ime_e2e.ps1 -AvdName IaidoApi35 -Class com.iaido.app.ImeSpacingModesE2eTest and then with -Class com.iaido.app.ImeInferenceE2eTest. Preserve artifacts and classify setup failures separately.
- [ ] Commit with message test: cover automatic spacing in real IME journeys.

### Task 7: Full verification and dev handoff

**Files:** review all feature files; modify none unless a scoped verification failure requires a fix.

- [ ] Run .\gradlew.bat :core-engine:test --no-daemon --console=plain.
- [ ] Run .\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain.
- [ ] Run .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain.
- [ ] Run .\tools\run_ime_e2e.ps1 -AvdName IaidoApi35 repeatedly on the pinned emulator; retain artifacts for failures.
- [ ] Run git diff --check, git status --short, and git log --oneline --decorate --max-count=12. Confirm no caches or unrelated WIP are staged.
- [ ] Review every spec requirement: default infer mode, two-finger merge/boundary candidates, six-unit and three-word bounds, transaction invalidation, structured reels, settings persistence, and all-mode connected tests.
- [ ] After explicit authorization, merge feature/auto-space into dev, rerun the full gates on the resulting dev tip, and report exact commit IDs. Do not merge to master or push without separate authorization.
