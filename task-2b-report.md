# Task 2B core segmenter report

## Status

Implemented the remaining core `InferenceSegmenter` seam for event-local multi-path ordering.
No app production or test file was edited or staged as part of this task.

## Implementation

- Concurrent `GestureUnit` values with two to four paths now use
  `MultiPathOrderHypothesis.forEvent`, so inference evaluates the observed assignment and every
  one-pair swap within that event.
- Each hypothesis uses the top candidate from each reordered path list. Two-path candidates retain
  `SplitWordMerger` as the exact merged-word lookup; larger events use exact concatenated dictionary
  lookup and the normal DP fallback.
- The existing dictionary segmentation DP continues to produce merged and boundary-preserving
  interpretations. Its per-group word limit expands only to the largest event path count, allowing
  a four-path event to preserve four dictionary-word boundaries without changing ordinary
  single-swipe behavior.
- Path-fit scores remain unscaled. The hypothesis timing weight scales only dictionary-frequency
  and context evidence.
- Hypothesis metadata is carried through `RawCandidate`, `GroupOption`, and `Partial` into each
  returned `SegmentationOption`. Separate `GestureUnit` IDs and event boundaries remain intact.

## TDD evidence

RED command:

```powershell
.\gradlew.bat :core-engine:test --tests "com.iaido.core.recognition.InferenceSegmenterTest" --tests "com.iaido.core.recognition.MultiPathOrderHypothesisTest" --no-daemon --max-workers=2 --console=plain
```

Result before implementation: failed as expected with 21 tests executed and one failure,
`InferenceSegmenterTest.kt:106` (`near simultaneous paths can prefer a swapped language hypothesis
while wide paths preserve observed order`).

Compile command after implementation:

```powershell
.\gradlew.bat :core-engine:compileKotlin --no-daemon --max-workers=2 --console=plain
```

Result: `BUILD SUCCESSFUL`.

GREEN command: the same focused test command used for RED. A final verification added
`--rerun-tasks` so Gradle executed every focused task instead of reusing cached test output.

Result: `BUILD SUCCESSFUL`; `InferenceSegmenterTest` passed 17/17 and
`MultiPathOrderHypothesisTest` passed 4/4 (21/21 total).

`git diff --check -- core-engine` also completed without whitespace errors; Git reported only the
repository's existing LF-to-CRLF conversion warnings.

## Spec ruling

No focused test expectation conflicted with existing single-swipe or DP behavior. The approved
spec therefore required no exception or test adjustment. The implementation keeps ordinary units
on the prior candidate/DP path and broadens only concurrent multi-path handling.

## Concerns and boundaries

- The app RED test was intentionally left untouched and was not run, per task scope.
- If one DP group contains multiple reordered multi-path events, their accumulated timing weights
  compose multiplicatively. The focused tests cover event isolation but do not distinguish other
  multi-event weight-composition policies.
- Frequency values in the shipped dictionary are probabilities, so their log-frequency terms are
  negative. Applying the approved timing multiplier moves a down-weighted negative term toward
  zero; the focused near/wide fixture uses positive synthetic frequency evidence. A production
  fixture should explicitly confirm the intended ranking with shipped-frequency-scale values.

## Review fix: shared event timing and ranked candidate combinations

The Task 2B review findings were addressed without changing the app RED test or the protected docs
and `.ci-art` artifacts.

### Implementation

- `MultiPathOrderHypothesis.forEvent` now measures one event-level touch-down span as
  `lastTouchDown - firstTouchDown`, normalizes it with
  `1 - clamp(eventSpan / graceWindow, 0, 1)`, and assigns the same span and language-evidence
  weight to the observed order and every pair swap. `swappedPair` remains separate provenance.
- `InferenceSegmenter.rawCandidatesFor` now enumerates the Cartesian product of every hypothesis's
  bounded ranked path-candidate lists. This retains lower-ranked merged and boundary-preserving
  alternatives instead of selecting only the first candidate from each path.
- Candidate path-fit scores are still summed without timing scaling. The shared timing weight still
  applies only to dictionary-frequency and context evidence.
- Focused coverage now includes event-level near/wide timing, probability-scale negative
  log-frequency behavior, lower-ranked merged and boundary alternatives, and deterministic lexical
  ordering across all equal-scoring path-candidate combinations. Existing three-path pairwise,
  internal-pair, and cross-event coverage remains intact.

### RED evidence

Command:

```powershell
.\gradlew.bat :core-engine:test --tests "com.iaido.core.recognition.InferenceSegmenterTest" --tests "com.iaido.core.recognition.MultiPathOrderHypothesisTest" --rerun-tasks --no-daemon --max-workers=2 --console=plain
```

Result against unchanged production code: `BUILD FAILED`; 24 tests executed with six expected
failures. The failures covered both shared event-span assertions, near/wide negative-frequency
timing, the lower-frequency swap guard, lower-ranked merged/boundary alternatives, and complete
deterministic candidate combinations.

### GREEN and compile evidence

The same focused command completed with exit code 0. Fresh JUnit XML reported:

- `InferenceSegmenterTest`: 20 tests, 0 failures, 0 errors, 0 skipped.
- `MultiPathOrderHypothesisTest`: 4 tests, 0 failures, 0 errors, 0 skipped.

Compile command:

```powershell
.\gradlew.bat :core-engine:compileKotlin --rerun-tasks --no-daemon --max-workers=2 --console=plain
```

Result: exit code 0.

`git diff --check -- core-engine` also exited 0; Git emitted only the repository's existing
LF-to-CRLF conversion warnings.
