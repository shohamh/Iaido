# NinjaKeys Stage 8: Context Scoring and Flow Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable two/three-word context scoring, bounded margin-gated flow correction, and an RTL-aware suggestion/correction strip with replacement and undo state.

**Architecture:** `NgramContextScorer` owns a configurable context window and normalized n-gram scores. `FlowCorrectionEngine` evaluates only the recent window, records original/current words, and applies at most the configured cascade depth. `SuggestionStripState` is platform-neutral state for chips and slot-reel selection; Compose renders it and the IME performs the actual `InputConnection` replacement.

**Tech Stack:** Kotlin/JVM, JUnit 5, Compose Material 3.

**Spec:** `wayfinder/tickets/011-flow-correction-and-undo-gestures.md`, `wayfinder/tickets/010-theming-baseline.md`, and Stage 8 in the roadmap.

## Global Constraints

- Flow correction only considers the immediately preceding 2–3 words.
- An alternate replaces a word only when it exceeds the current candidate by the configured margin.
- Cascading is bounded by default depth 2.
- Correction history is session-only and tracks original/current text for undo.
- Suggestion chips are the only correction surface; direct host-text touch is unsupported.
- Hebrew chip order is RTL; plain chip taps do not commit changes.

---

### Task 1: Context scoring contract

**Files:** Modify `NgramContextScorer.kt` and tests.

- [ ] Test configurable window sizes, weighted bigram/trigram scores, and empty-context behavior.
- [ ] Implement normalized scoring with explicit context-window configuration.
- [ ] Integrate the scorer into recognizer construction without changing the shape API.

### Task 2: Bounded flow correction and session history

**Files:** Create `FlowCorrectionEngine.kt` and tests.

- [ ] Test margin gating, two-step cascade limit, original/current history, and undo.
- [ ] Implement deterministic correction over the recent context window with configurable margin/depth.
- [ ] Expose replacement and undo events for the app’s InputConnection adapter.

### Task 3: Suggestion/correction strip state and UI

**Files:** Create `SuggestionStripState.kt` and tests; modify keyboard Compose/service.

- [ ] Test centered reel selection, resume-at-last-pick, no-op plain taps, corrected highlighting, and RTL ordering.
- [ ] Implement platform-neutral strip state and a Compose chip/reel view.
- [ ] Wire replacement/undo callbacks and session cursor refresh.

### Task 4: Stage gate

- [ ] Run all core/app tests, debug assembly, and `git diff --check`.
- [ ] Review the complete Stage 8 diff against the ticket before committing.
