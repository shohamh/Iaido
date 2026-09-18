# E2E Host Reuse, Atomic Reset, and Editor Snapshot Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce connected IME E2E setup and checkpoint cost while preserving real editor, selection, IME, and lifecycle verification.

**Architecture:** Keep one debug host activity leased across ordinary scenarios, with explicit invalidation for tests that intentionally relaunch or change fixtures. Add a debug-only host reset seam that atomically resets editor text/selection and pair it with the existing keyboard snapshot restore. Make editor observation a deep test adapter that returns one consistent snapshot per accessibility read, with fallback retries for stale nodes.

**Tech Stack:** Kotlin/JVM, Android debug source set, Android instrumentation, UiAutomator, existing `ImeScenario` and `ImeSystemController` harness.

**Spec:** `docs/superpowers/specs/2026-09-14-iaido-ime-e2e-testing-design.md`

## Global Constraints

- Keep all host reset and host lease controls debug/test-only; production release manifests remain unchanged.
- Do not weaken real `InputConnection` verification: every scenario still verifies actual host text and selection.
- Lifecycle, keyboard-switch, settings, and fixture-changing tests retain explicit relaunch/rebind behavior.
- Fast paths must fall back to the existing UiAutomator/shell path when the expected marker, generation, or status is absent.
- Do not include host editor text in the portable user keyboard profile snapshot.
- Run focused unit/instrumentation tests before each implementation step, then the full connected suite and profiler.

---

### Task 1: Add host lease and generation-aware reuse

**Files:**
- Modify: `app/src/debug/kotlin/com/iaido/app/ImeTestHostActivity.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeSuiteSessionState.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeSuiteSessionStateTest.kt`

**Interfaces:**
- `ImeTestHostActivity` publishes a debug-only host generation marker in its status accessibility node.
- `ImeSystemController.ensureHostVisible(fixture, expectedGeneration)` returns the observed host generation and relaunches only when the host is missing, stale, or the fixture changed.
- `ImeSuiteSessionState` records the leased host generation and active fixture, and invalidates them explicitly.

- [ ] Add a failing suite-state test proving a matching host generation and fixture can be reused while a changed generation or fixture requires relaunch.
- [ ] Run `./gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.ImeSuiteSessionStateTest` and confirm the new expectation fails for the missing generation/fixture contract.
- [ ] Add a monotonically increasing debug host generation to `ImeTestHostActivity`, expose it alongside existing length/selection status, and preserve it across ordinary focus operations.
- [ ] Implement generation-aware `ensureHostVisible` with a `host_reuse`/`host_launch` perf event and a fallback to `launchHost` when the marker or editor is absent.
- [ ] Update `ImeScenario.setup()` to reuse the lease for ordinary tests while marking it invalid around `relaunchHost`, `backgroundAndForeground`, fixture changes, and explicit IME transitions.
- [ ] Run the suite-state unit test GREEN and build the debug APK/test APK.
- [ ] Run focused connected classes covering ordinary setup plus lifecycle and auto-space fixture changes; verify intentional lifecycle tests still relaunch.

### Task 2: Add an atomic debug editor reset seam

**Files:**
- Modify: `app/src/debug/kotlin/com/iaido/app/ImeTestHostActivity.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeEditorDriver.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeEditorDriverTest.kt` or the smallest existing Android test seam that exercises reset acknowledgment

**Interfaces:**
- `ImeTestHostActivity` accepts a debug-only reset request carrying a request ID and responds with the same ID after text and selection are updated.
- `ImeEditorDriver.resetAtomically()` uses the request/acknowledgment seam and verifies the resulting empty editor snapshot; it falls back to `UiObject2.setText("")` if the host marker is unavailable.
- `ImeScenario.setup()` calls one keyboard baseline restore followed by one editor reset, rather than independent accessibility mutations.

- [ ] Add a failing test for reset acknowledgment: a reset request must not be considered complete until text is empty and selection is `0..0`.
- [ ] Run that focused test and confirm RED because no reset request/acknowledgment seam exists.
- [ ] Implement a debug-only host reset request using the existing host status/preferences channel, updating the `EditText` and status on the activity main thread.
- [ ] Implement `ImeEditorDriver.resetAtomically()` with bounded polling, stale-node retry, and fallback to the existing clear path.
- [ ] Replace scenario setup's clear call with the atomic reset, retaining one explicit real-editor verification.
- [ ] Run the focused reset test GREEN and the editing/lifecycle connected classes.
- [ ] Compare `editor_clear` and `scenario_setup` phase totals against the committed baseline.

### Task 3: Collapse editor observation into one snapshot read

**Files:**
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeEditorDriver.kt`
- Modify: `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ImeEditorDriverTest.kt` or a focused Android instrumentation seam

**Interfaces:**
- `ImeEditorDriver.EditorSnapshot` contains `text: String`, `selection: IntRange`, and the host status generation/length metadata.
- `ImeEditorDriver.snapshot()` reads the editor and status with one retryable UiAutomator transaction.
- `waitForText`, `waitForTextChange`, `text`, and `selection` delegate to the snapshot seam; checkpoint assertions consume one snapshot where possible.

- [ ] Add a failing test proving a checkpoint observation returns text and selection from the same status generation, rather than independent reads.
- [ ] Run the focused test and confirm RED for the missing snapshot interface.
- [ ] Implement the immutable `EditorSnapshot` value and one stale-safe read transaction.
- [ ] Refactor waits and checkpoint observation to use the snapshot while preserving exact text/selection mismatch errors.
- [ ] Run focused editor/scenario tests GREEN and inspect failure artifacts for unchanged text/selection diagnostics.
- [ ] Run the full connected suite, extract checkpoint counts/times, and compare three-run medians with the committed baseline.

### Task 4: Commit and regression gate

**Files:**
- Modify: only the files above and this plan if status updates are recorded

- [ ] Run `./gradlew.bat :core-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon`.
- [ ] Run `git diff --check` and inspect staged paths so unrelated changes are not included.
- [ ] Run the full connected E2E suite with install reuse and record 47-test pass/fail, setup, host launch/reuse, editor reset, snapshot, and checkpoint timings.
- [ ] Commit the three optimizations in logical commits: host lease, atomic reset, and editor snapshot observation.
- [ ] Report reliability evidence separately from wall-clock improvements, including any fallback usage.
