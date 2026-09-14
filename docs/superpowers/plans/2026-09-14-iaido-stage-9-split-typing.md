# Iaido Stage 9: Two-Handed Split Typing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement concurrent two-pointer partial gestures with touch-down-order merging, a 350ms grace window, doubled-letter taps only during an active split session, and single-finger fallback.

**Architecture:** `SplitGestureSession` is a pure Kotlin state machine that owns pointer order, active paths, grace expiry, and in-session taps. `SplitWordMerger` consumes the ordered partial letter candidates and validates concatenations against the dictionary. The Compose keyboard only collects pointer paths and delegates timing/commit decisions to an app controller, preserving existing two-finger command gestures when the first touch is on the space key.

**Tech Stack:** Kotlin/JVM, JUnit 5, Android `MotionEvent`, Compose `pointerInteropFilter`.

**Spec:** `wayfinder/tickets/013-two-handed-split-word-typing.md`, `wayfinder/tickets/012-nintype-multitouch-research.md`, and Stage 9 in `docs/superpowers/plans/2026-09-13-iaido-roadmap.md`.

## Global Constraints

- Partial gestures are ordered by touch-down time, not keyboard geography.
- A new touch within the 350ms grace window extends the in-progress word.
- A split word commits only after all fingers are lifted and the grace window expires.
- A quick tap becomes a doubled letter only while a word gesture is active or in its grace window.
- A single active path remains the ordinary single-finger swipe path.
- Existing space-key two-finger command gestures remain available.

---

### Task 1: Pure split gesture session

**Files:**
- Create: `core-engine/src/main/kotlin/com/iaido/core/recognition/SplitGestureSession.kt`
- Test: `core-engine/src/test/kotlin/com/iaido/core/recognition/SplitGestureSessionTest.kt`

**Interfaces:**
- `begin(pointerId: Int, point: GesturePoint, atMs: Long)` starts a partial path and records touch order.
- `move(pointerId: Int, point: GesturePoint)` appends to an active path; unknown pointers are ignored.
- `end(pointerId: Int, atMs: Long, letters: String)` closes one path and returns `SplitSessionState`.
- `tap(pointerId: Int, letter: Char, atMs: Long)` records a doubled-letter tap only while active or within grace.
- `poll(atMs: Long)` returns a ready `SplitWordParts` only after grace expiry.
- `cancel()` discards all partial state.

**Steps:**

- [x] Write failing tests for touch-down order, path capture, grace-window expiry, single-path fallback, and tap rejection outside a session.
- [x] Run `./gradlew :core-engine:test --tests '*SplitGestureSessionTest'`; expect compilation failure because the session type is absent.
- [x] Implement the state machine with a 350ms default, ordered parts, and `SplitWordParts(parts: List<String>)` output.
- [x] Add tests proving a tap during grace appends the repeated letter and a tap after expiry is ignored.
- [x] Run the focused tests and require PASS.
- [x] Commit `Add split gesture session state machine`.

### Task 2: Ordered merge and app controller seam

**Files:**
- Modify: `core-engine/src/main/kotlin/com/iaido/core/recognition/SplitWordMerger.kt`
- Modify: `core-engine/src/test/kotlin/com/iaido/core/recognition/NgramAndSplitTest.kt`
- Create: `app/src/main/kotlin/com/iaido/app/SplitTypingController.kt`
- Create: `app/src/test/kotlin/com/iaido/app/SplitTypingControllerTest.kt`

**Interfaces:**
- `SplitWordMerger.mergeParts(parts: List<String>, dictionary: List<WordEntry>): List<WordEntry>` validates concatenation in session order.
- `SplitTypingController` accepts completed `GesturePath`s plus their touch order, converts each to a letter sequence, calls the session/merger, and commits the best dictionary result after grace expiry.

**Steps:**

- [x] Write failing tests for three-part touch-order merge, dictionary rejection, and the 350ms boundary.
- [x] Run focused core/app tests and record the expected failures.
- [x] Implement `mergeParts` while preserving the existing two-list API as a compatibility wrapper.
- [x] Implement the controller with deterministic clock input so unit tests do not sleep.
- [x] Add a test proving one path delegates to the existing swipe commit callback and a second path merges `th` + `ere` into `there`.
- [x] Run focused tests and require PASS.
- [x] Commit `Add split typing merge controller`.

### Task 3: MotionEvent integration and Stage 9 gate

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Add/modify: `app/src/test/kotlin/com/iaido/app/*`

**Interfaces:**
- The keyboard emits split paths through one callback carrying the completed `SplitWordParts`/paths and event timestamp.
- The service owns the controller and commits only after the controller reports grace expiry; it leaves space-started command gestures on the existing command dispatcher.

**Steps:**

- [x] Write/extend tests for pointer-down ordering, pointer-up path finalization, command-vs-split routing, cancellation, and no doubled tap outside a split session.
- [x] Implement pointer maps keyed by Android pointer ID; preserve each pointer’s complete `GesturePath`.
- [x] Start split mode only when a second pointer lands on the letter surface; retain current multi-finger command behavior for space gestures.
- [x] Schedule grace expiry through an injectable callback/clock seam rather than blocking the Compose event handler.
- [x] Verify single-finger `ACTION_UP` still reaches the existing swipe/flick/tap branches.
- [x] Run `:core-engine:test :app:testDebugUnitTest :app:assembleDebug` and `git diff --check`.
- [x] Review the full Stage 9 diff against tickets 012/013, mark this plan and the roadmap Stage 9 checkbox complete, and commit `Complete Stage 9 split-word typing`.

## Self-review coverage

- Touch-down-order merge: Task 1 and Task 2.
- Grace timing and delayed commit: Task 1 and Task 3.
- Doubled-letter taps and single-finger fallback: Task 1 and Task 3.
- Android pointer lifecycle and command compatibility: Task 3.
- Dictionary validation and existing recognizer compatibility: Task 2.
