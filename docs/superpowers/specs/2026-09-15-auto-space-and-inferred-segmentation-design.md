# Auto-space and inferred segmentation design

## Goal

Add three mutually exclusive spacing modes to Iaido:

- **Manual spacing**: preserve the current behavior; spaces are entered explicitly.
- **Space after swipe**: after a recognized swipe gesture is complete and all fingers in that gesture have been released, append one space.
- **Infer spaces**: infer boundaries between recent swipe gestures using gesture recognition, word frequency, and n-gram context. The inference may join multiple gestures into one word, split one gesture into multiple words, and revise recent decisions after each later swipe.

The default for new installations and missing preferences is **Infer spaces**.

## Existing constraints and integration points

The pure Kotlin `core-engine` owns gesture recognition, dictionary candidates, n-gram scoring, and two-finger split-word recognition. The Android `app` owns `InputConnection`, DataStore, Compose rendering, and connected IME tests.

`SplitGestureSession` already tracks two concurrent paths through a bounded grace window and orders them by touch-down order. A two-finger gesture can therefore produce both a merged candidate and a boundary-preserving candidate for the inference scorer. A physical finger lift is not itself a committed space.

The existing correction history is session-only and can replace words through `InputConnection`. The existing suggestion reel is string-based and must be extended to represent replacement groups whose source and replacement word counts differ.

## Behavior

### Gesture units and spacing

Single-finger swipes remain standalone gesture units. Two concurrent swipes are evaluated as a split-word gesture: the default interpretation concatenates the two parts in touch-down order, while inference retains the boundary-preserving interpretation when its dictionary and contextual score is stronger.

The space-after-swipe mode adds one trailing space only after the complete gesture unit has been recognized. Two concurrent fingers produce at most one space. Failed, cancelled, punctuation, command, tap, and externally edited input do not trigger automatic spacing.

Inference maintains a mutable run of at most six recent swipe gesture units. It reanalyzes the run after every completed swipe. The run is finalized and cleared when the user performs a non-swipe action, explicitly inserts space or punctuation, moves the cursor, or the run reaches its bound and the oldest unit is frozen.

Inference may produce up to three dictionary words from one gesture unit or a contiguous group of units. One-letter dictionary words such as `a` and `I` are valid. If no alternative clears the configured confidence margin, the current top-word interpretation remains unchanged.

External host edits are a hard boundary: the current best interpretation is frozen, the active inference run is cleared, and the external action proceeds.

### Scoring and reanalysis

The core engine will expose a bounded segmentation operation based on dynamic programming. It combines:

1. ranked letter candidates from each gesture path;
2. merged and boundary-preserving candidates for a two-finger gesture;
3. dictionary validity and word frequency;
4. path-fit scores; and
5. the existing configurable n-gram context score.

The search is bounded to six gesture units, at most three output words per replacement group, and the existing top candidate limits. Reanalysis replaces the active run as one logical transaction, so a later gesture can change `inthe` into `in the`, or change two words into one, without accumulating offset errors.

## Replacement transaction

The app will track an active inference transaction containing:

- source gesture-unit IDs and their paths;
- the current output segmentation;
- ranked segmentation alternatives;
- the exact host-editor span occupied by the current output; and
- enough original state to cancel or finalize the transaction.

Each reanalysis uses `InputConnection` to replace the transaction span. Once finalized, its words enter the existing session correction history and suggestion strip. If the user changes a reel selection, the transaction is finalized with that replacement and is no longer automatically rewritten.

All automatic replacements are limited to text inserted by Iaido during the current input session. Cursor movement, external edits, and lifecycle/session changes clear the active transaction.

## Settings

Add a single mutually exclusive `Spacing mode` control to the existing Settings activity, persisted through the existing DataStore. Labels:

- `Manual spacing`
- `Space after swipe`
- `Infer spaces`

The default for a missing preference is `Infer spaces`. The selected mode must be read by the IME service and applied without requiring an app reinstall.

## Reel UI

Segmentation alternatives are structured replacement options rather than plain strings. A normal one-word option renders as one reel. A joined option that replaces two words renders as one wide reel spanning both source slots. A split option that replaces one word with two or three words renders as coordinated adjacent reels, widening or horizontally scrolling the strip when necessary.

The full replacement group previews while dragging and commits on release. Cancelling restores the original transaction. Accessibility descriptions identify the replacement shape and candidate position so connected tests can assert joined and split options without relying on pixels.

## Testing

### Core-engine tests

Add deterministic tests for:

- single gesture spacing decisions;
- two-finger merged versus boundary-preserving candidates;
- one gesture split into two or three dictionary words;
- multiple gestures joined into one word;
- contextual reanalysis changing an earlier boundary;
- margin-gated fallback;
- six-unit run bounding; and
- transaction alternatives and finalization.

### Android unit tests

Cover DataStore mode defaults and persistence, mode propagation into the IME, replacement span/cursor math, external-edit invalidation, and structured reel layout/selection state.

### Connected E2E tests

Use the existing real-IME test host and pointer injector. Add separate journeys for all three modes:

- Manual spacing: sequential swipes do not add spaces automatically.
- Space after swipe: single-finger swipes add spaces; a two-finger merged swipe adds exactly one space.
- Infer spaces: sequential gestures remain separate when that scores best, join when a dictionary/context score favors one word, one gesture can split into multiple words, a later swipe can revise an earlier boundary, and a two-finger gesture can resolve to one word or separate words.

Each mode is selected through the real Settings screen and verified after keyboard restart. E2E tests also assert the structured reel accessibility labels, joined/split replacement text, cancellation behavior, no-confidence fallback, and cursor position. Deterministic debug dictionary and n-gram fixtures make expected choices stable while all input still reaches the real Compose IME through Android pointer events.

Completion requires JVM tests, debug APK and instrumentation APK assembly, repeated connected E2E success on the pinned emulator profile, and a clean `git diff --check`.

## Worktree

Implementation will be performed in the isolated worktree at:

`.worktrees/auto-space` on branch `feature/auto-space`
