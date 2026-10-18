# Context-aware swipe decoding Implementation Plan

> **For agentic workers:** Execute inline with test-first steps. Each behavior change starts with a failing test.

**Goal:** Rerank noisy swipe choices across a revisable six-gesture window in all spacing modes.

**Architecture:** Reuse `InferenceSegmenter` with a one-word-per-gesture policy and exact suffix-state retention. Generalize the existing `SwipeInferenceTransaction` to render mode-specific text, track exact owned text, and report actual per-word spans. Keep app text mutation behind the service adapter.

**Tech Stack:** Kotlin, JUnit 5, Android `InputConnection`, existing NKG2 assets.

**Spec:** `docs/superpowers/specs/2026-10-18-context-aware-swipe-decoding-design.md`

## Global Constraints

- Work only in the existing `nkg2-integration` worktree.
- Preserve inherited NKG2 changes and do not push or release.
- Keep the six-gesture window and current candidate/scoring inputs fixed.
- Normal modes must not introduce swipe splits or joins.
- Do not mutate editor text unless the current selection and exact owned text match.

## Review Focus

- A context-favored candidate must be retained even when its prefix was not globally top-ranked.
- Single-gesture and multi-path candidates must remain one output word in normal modes.
- Manual spacing must preserve contiguous text and correct word offsets.
- After-swipe mode must preserve one trailing space without including it in word history spans.
- Cursor movement, external edits, and failed host replacements must stop stale-span rewriting.

---

### Task 1: Add sequence-decoding policy to the core segmenter

**Files:** `core-engine/src/main/kotlin/com/iaido/core/recognition/InferenceSegmenter.kt`, `core-engine/src/test/kotlin/com/iaido/core/recognition/InferenceSegmenterTest.kt`.

- [x] Add tests proving normal policy keeps one word per unit and retains a trigram-optimal prefix beyond the existing global top-16 beam.
- [x] Run focused tests and observe the expected failures.
- [x] Add policy-specific transitions and best-prefix-per-last-two-context retention; preserve spacing-inference defaults.
- [x] Run focused tests, then the full core-engine suite.

### Task 2: Generalize owned-text transaction and word spans

**Files:** `app/src/main/kotlin/com/iaido/app/SwipeInferenceTransaction.kt`, `core-engine/src/main/kotlin/com/iaido/core/recognition/SessionCorrectionHistory.kt`, corresponding transaction/history tests.

- [x] Add tests for exact expected-source checks, mode-specific separators, trailing-space ownership, and contiguous word-history spans.
- [x] Run focused tests and observe the expected failures.
- [x] Implement expected-text replacement, separator/trailing rendering, window-slide span updates, and separator-aware history offsets.
- [x] Run transaction and history tests.

### Task 3: Enable rolling sequence correction in normal modes

**Files:** `app/src/main/kotlin/com/iaido/app/SwipeTypingCoordinator.kt`, `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`, `app/src/test/kotlin/com/iaido/app/SwipeTypingCoordinatorTest.kt`.

- [x] Add tests for later context revising an earlier manual/after-swipe word, six-unit sliding/finalization, and failure/cursor lifecycle.
- [x] Run focused coordinator tests and observe the expected failures.
- [x] Route normal modes through the one-word policy; render per mode, lock the oldest word from the current best sequence, and update app history using exact word spans.
- [x] Validate caret and owned source text in the service adapter; abandon after stale-span or replacement failure.
- [x] Run focused app/core tests, the full core suite, generator tests, and `git diff --check`.

### Task 4: Complete sequence-decoding evaluation metrics

**Files:** `core-engine/src/test/kotlin/com/iaido/core/perf/SegmentationBenchmarkTest.kt`, `core-engine/src/test/kotlin/com/iaido/core/perf/SequenceEvaluationMetrics.kt`, corresponding metric tests.

- [x] Add unit tests for candidate recall, harmful/recovered revisions, and changes to previously correct high-confidence words.
- [x] Run the focused metric tests and observe the expected failure before implementation.
- [x] Integrate the metrics into the rolling held-out phrase replay without changing runtime scoring.
- [x] Run the shipped-weight English and Hebrew held-out benchmarks, then the full app/core suites, generator tests, and `git diff --check`.

### Task 5: Close review findings in decoding, metrics, and asset generation

**Files:** `core-engine/src/main/kotlin/com/iaido/core/recognition/InferenceSegmenter.kt`, `core-engine/src/main/kotlin/com/iaido/core/recognition/NgramContextScorer.kt`, sequence benchmark/tests, `tools/generate_ngrams.py` and generator tests.

- [x] Add regressions for split candidates crowding out whole-word candidates, sentence capitalization leaking into context lookup, positional sequence accuracy, and stale aggregation cache identity.
- [x] Generate one-word-policy groups with a one-word bound before candidate pruning and keep the decoding policy in the group cache key.
- [x] Normalize displayed capitalization at the n-gram lookup boundary and compare sequence predictions at their swipe positions.
- [x] Fingerprint ordered dictionary IDs, allowed/context/trigram vocabulary membership, and conversational-source membership in aggregation cache settings.
- [x] Rerun English and Hebrew sequence benchmarks, full app/core suites, generator tests, and `git diff --check`.

Editor replacement failure behavior remains covered by the app transaction/coordinator tests. The synthetic core benchmark does not claim to measure real `InputConnection` replacement latency.
