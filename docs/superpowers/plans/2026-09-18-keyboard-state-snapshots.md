# Keyboard State Snapshots Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a lossless, versioned keyboard profile/session snapshot seam that supports phone migration and reliable E2E restoration without serializing Android runtime handles.

**Architecture:** Pure Kotlin snapshot models own validation and round-trip semantics. Android adapters translate DataStore/Room/runtime state into those models, apply a complete snapshot transactionally, and publish a monotonic readiness revision after rebinding the IME. The E2E runner consumes that readiness seam; it does not infer lifecycle state from accessibility nodes.

**Tech Stack:** Kotlin/JVM, Android Kotlin, DataStore Preferences, Room, kotlinx.serialization JSON, JUnit 5 core tests, Android instrumentation tests, existing UiAutomator harness.

**Spec:** `docs/superpowers/specs/2026-09-18-keyboard-state-snapshots-design.md`

## Global Constraints

- Do not serialize `InputConnection`, Compose views, lifecycle owners, handlers, executors, Activity/accessibility objects, or pointer events.
- Do not include current editor text in the normal portable profile.
- Validate the complete snapshot before mutating any live or persistent state.
- Preserve the existing uncommitted E2E optimization diff; stage only files belonging to the current task when committing.
- Production code is written only after a focused failing test has demonstrated the missing behavior.
- Existing standard verification remains `./gradlew.bat :core-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon`.

---

### Task 1: Add lossless core snapshot models

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/dictionary/PersonalDictionarySnapshot.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/state/KeyboardStateSnapshot.kt`
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/SessionCorrectionHistorySnapshot.kt`
- Modify: `core-engine/src/main/kotlin/com/iaido/core/dictionary/PersonalDictionary.kt`
- Modify: `core-engine/src/main/kotlin/com/iaido/core/recognition/SessionCorrectionHistory.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/dictionary/PersonalDictionaryTest.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/recognition/SessionCorrectionHistoryTest.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/state/KeyboardStateSnapshotTest.kt`

**Interfaces:**
- `PersonalDictionary.snapshot(): PersonalDictionarySnapshot`
- `PersonalDictionary.restore(snapshot: PersonalDictionarySnapshot)`
- `SessionCorrectionHistory.snapshot(): SessionCorrectionHistorySnapshot`
- `SessionCorrectionHistory.restore(snapshot: SessionCorrectionHistorySnapshot)`
- `KeyboardProfileSnapshot` contains exact settings, bindings, language preference, and dictionary snapshot.
- `TypingSessionSnapshot` contains language, correction history snapshot, cursor position, and pending candidates.

- [ ] Write a failing dictionary test that restores exact word boost/use counts, custom words, and n-gram boosts into a fresh dictionary.
- [ ] Run `./gradlew.bat :core-engine:test --tests com.iaido.core.dictionary.PersonalDictionaryTest` and confirm the new test fails because no exact snapshot API exists.
- [ ] Write a failing history test that preserves `nextId`, corrected flags, candidate lists, and shifted ranges after restore.
- [ ] Run the focused history test and confirm the expected missing-method failure.
- [ ] Add immutable snapshot DTOs with schema version `1` and constructor validation for duplicate IDs, invalid ranges, negative cursor positions, and invalid boost/use values.
- [ ] Implement dictionary snapshot/restore by copying internal override state rather than reconstructing from effective frequencies.
- [ ] Implement history snapshot/restore while preserving the next ID allocator.
- [ ] Add `KeyboardStateSnapshot` and `TypingSessionSnapshot` validation.
- [ ] Run the two focused test classes and the new state test; confirm GREEN.
- [ ] Run `./gradlew.bat :core-engine:test --no-daemon`.
- [ ] Commit only the core snapshot files and tests with `feat: add lossless keyboard state models`.

### Task 2: Add a versioned JSON codec

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardStateJsonCodec.kt`
- Test: `app/src/test/kotlin/com/iaido/app/KeyboardStateJsonCodecTest.kt`

**Interfaces:**
- `KeyboardStateJsonCodec.encode(snapshot: KeyboardStateSnapshot): String`
- `KeyboardStateJsonCodec.decode(json: String): KeyboardStateSnapshot`

- [ ] Write failing tests for a full profile/session JSON round trip and rejection of unsupported schema versions.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.KeyboardStateJsonCodecTest` and confirm RED.
- [ ] Implement deterministic JSON field names and arrays for settings, command bindings, dictionary overrides, n-grams, custom words, history, and pending candidates.
- [ ] Reject missing fields, duplicate dictionary keys, invalid enum values, malformed numbers, and unsupported schema versions before constructing the snapshot.
- [ ] Run the codec test GREEN and add a malformed-input regression case.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --no-daemon`.
- [ ] Commit with `feat: add versioned keyboard state codec`.

### Task 3: Make profile persistence authoritative and lossless

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardSettings.kt`
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardProfileStore.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SettingsActivity.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/PersonalDictionaryDatabase.kt`
- Test: `app/src/test/kotlin/com/iaido/app/KeyboardProfileStoreTest.kt`

**Interfaces:**
- `KeyboardProfileStore.read(): KeyboardProfileSnapshot`
- `KeyboardProfileStore.replace(snapshot: KeyboardProfileSnapshot)`
- `KeyboardSettings` is the single resolved settings object used by Settings, service, export, and tests.

- [ ] Write failing store tests proving all settings and exact Room rows are represented in the profile snapshot.
- [ ] Run the focused store tests and confirm RED.
- [ ] Move DataStore keys/defaults into `KeyboardSettings.kt` without changing their names or defaults.
- [ ] Wire flow-correction depth, split grace duration, and command bindings into runtime construction/observation so exported values are real behavior, not dead settings.
- [ ] Implement profile reads from DataStore plus exact Room entities, including `uses` and n-grams.
- [ ] Implement replacement using validated staged values; if the Room/DataStore write fails, restore the previous values and report failure without partial success.
- [ ] Run focused store tests GREEN and the existing Room repository tests.
- [ ] Run app unit tests and `git diff --check`.
- [ ] Commit with `feat: make keyboard profile persistence authoritative`.

### Task 4: Add safe runtime session restore and readiness revision

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardRuntimeState.kt`
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardRuntimeReadiness.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SplitTypingController.kt`
- Test: `app/src/test/kotlin/com/iaido/app/KeyboardRuntimeStateTest.kt`

**Interfaces:**
- `KeyboardRuntimeState.snapshot(): TypingSessionSnapshot`
- `KeyboardRuntimeState.restore(snapshot: TypingSessionSnapshot): Long`
- `KeyboardRuntimeReadiness.currentRevision(): Long`
- `KeyboardRuntimeReadiness.awaitRevision(revision: Long)` is represented to instrumentation through a debug-only observable marker, not a production broadcast.

- [ ] Write failing runtime tests proving restore rejects in-flight/non-quiescent state and accepts a safe correction-history checkpoint.
- [ ] Run focused runtime tests and confirm RED.
- [ ] Extract model-owned state from `IaidoInputMethodService` behind one runtime-state module; leave Android handles in the service adapter.
- [ ] Add a restore operation that runs on the main thread, cancels stale delayed work, restores model state, recomputes chips/replacement options, and increments one revision.
- [ ] Publish readiness only after `currentInputConnection` is available and the input view has rendered.
- [ ] Ensure `onStartInputView` and `onFinishInputView` invalidate pending restore revisions instead of silently reusing stale state.
- [ ] Run focused runtime tests and existing app unit tests.
- [ ] Commit with `feat: add revisioned IME runtime restore seam`.

### Task 5: Replace E2E heuristic reuse with snapshot restore

**Files:**
- Create: `app/src/debug/kotlin/com/iaido/app/DebugKeyboardStateAdapter.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeSuiteSessionState.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeSuiteSessionStateTest.kt`

**Interfaces:**
- `DebugKeyboardStateAdapter.saveBaseline(): String`
- `DebugKeyboardStateAdapter.restoreBaseline(id: String): Long`
- `ImeSystemController.waitForRuntimeReady(revision: Long)`

- [ ] Write failing suite-state tests showing a saved baseline is invalid after a service/host lifecycle generation changes.
- [ ] Run the focused Android test class and confirm RED.
- [ ] Add a debug-only adapter that uses the same profile/session restore interface as production but stores only an in-process baseline identifier.
- [ ] Make suite setup restore the model once per fixture and wait for readiness revision; retain host relaunch as a fallback when the real editor is absent.
- [ ] Remove the reference-IME round trip from behavior setup only after the readiness marker proves the new spacing value is active.
- [ ] Keep environment-changing tests responsible for explicit IME selection and host focus.
- [ ] Run focused connected E2E classes and inspect artifacts for real text/selection verification.
- [ ] Run the full connected suite once; record setup, restore, host-launch, readiness, and checkpoint timings.
- [ ] Commit with `test: restore E2E keyboard state through runtime seam`.

### Task 6: Add user export/import and final reliability measurement

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SettingsActivity.kt`
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardProfileFileTransfer.kt`
- Test: `app/src/test/kotlin/com/iaido/app/KeyboardProfileFileTransferTest.kt`
- Modify: `README.md`

**Interfaces:**
- File export writes the versioned JSON envelope to a caller-provided SAF `Uri`.
- File import decodes and validates fully before calling `KeyboardProfileStore.replace`.

- [ ] Write failing file-transfer tests for complete round trips and no mutation after invalid input.
- [ ] Run focused tests and confirm RED.
- [ ] Implement SAF read/write with a checksum and app/core-engine version metadata.
- [ ] Add Settings buttons for Export profile and Import profile; do not export editor text.
- [ ] Show clear status for invalid/unsupported files and successful restore.
- [ ] Document the privacy behavior and debug/test restore seam in `README.md`.
- [ ] Run the complete unit/build verification command.
- [ ] Run ten full E2E suite repetitions or the maximum available device repetitions, recording flake count and timing percentiles.
- [ ] Run `git diff --check`, inspect the final diff, and report remaining unrelated optimization changes separately.
- [ ] Commit with `feat: add keyboard profile export and import`.
