# Suggestion reel redesign design

## Goal

Fix four problems in the suggestion strip / correction reel UI reported against the shipped
`SuggestionStrip` composable:

1. Releasing a drag to commit a word feels delayed.
2. The strip doesn't auto-scroll to keep the newest chip in view, and chips don't animate into
   place when added or removed.
3. Chips are a fixed size regardless of their word's length, and are generally oversized.
4. The joined-word suggestion (e.g. "wh" + "at" -> "what") only appears as a separate reel
   anchored to the edge of the strip, not when scrolling either of the two chips it would
   replace.

## Existing constraints and integration points

`SuggestionStrip.kt` renders two kinds of reel: per-word `SuggestionChipView`s (one per
`SuggestionChip`, vertical drag changes `chip.alternatives`) and a separate
`ReplacementReelGroup` slot for `ReplacementOption`s (joins and splits), anchored to one edge of
the strip. Both already share `REEL_STEP_DP`/`MAX_REEL_VISIBLE_SLOTS` from `SuggestionReelMath.kt`
for vertical step size and slot counting, and `ReplacementReelLayout.kt` for the
join/split-specific width and selection-state logic.

`SwipeTypingCoordinator.previewReplacement(ReplacementOption)`,
`.releaseReplacement(ReplacementOption)`, and `.cancelReplacement()` already perform the actual
multi-word `InputConnection` replacement and are wired end-to-end from
`IaidoInputMethodService.kt` to `SuggestionStrip`'s `onReplacementPreview`/`onReplacementRelease`/
`onReplacementCancel` callbacks. This plumbing is reused unchanged; only where in the UI these
callbacks are invoked from changes.

`SuggestionChip` (`core-engine`) has `word`, `alternatives`, `selectedIndex`, `corrected`, `id:
Int?`. `ReplacementOption` has `sourceWords`, `replacementWords`, `score`, `id`. Neither currently
carries the source `GestureUnit` id used inside `InferenceSegmenter`/`SegmentationOption` — the
app layer only ever sees resolved word lists, so correlating a `ReplacementOption` to specific
chips must go by matching `sourceWords` against a contiguous run of chips' `.word` values (see
below).

Scope: this redesign covers **join** options only (`sourceWords.size > 1 &&
replacementWords.size == 1`). **Split** options (`sourceWords.size == 1 && replacementWords.size >
1`) keep today's separate `ReplacementReelGroup` edge-slot rendering, unchanged. Folding splits
into the same inline mechanism is a separate future change.

## Behavior

### 1. Immediate commit

`SuggestionChipView.onDragEnd` currently calls `onRelease(targetIndex)` only after
`reelOffset.animateTo(targetValue, spring(...))` completes. Move the `onRelease` call to fire as
soon as the drag ends (immediately after computing `targetIndex`/`shouldSelect`), before starting
the settle animation. The animation still plays, purely as a cosmetic snap to the new resting
position; it no longer gates the actual word commit.

### 2. Auto-scroll and reflow animation

Replace the strip's `Row` + `Modifier.horizontalScroll(rememberScrollState())` with `LazyRow` +
`rememberLazyListState()`:

- Each chip item (and the attached join slot, see below) gets `Modifier.animateItem()` so
  insertions/removals animate other items sliding to their new position instead of jumping.
- A `LaunchedEffect` keyed on the ordered list of chip ids calls
  `listState.animateScrollToItem(targetIndex)`, where `targetIndex` is `0` when the newest chip
  renders first (RTL) or the last index when it renders last (LTR) -- matching the existing
  `ordered = if (rtl) chips.asReversed() else chips` convention. This keeps the newest chip in
  view without the caller having to reason about pixel offsets.
- Chip removal (backspace/undo) uses the same mechanism: the list shrinks, `animateItem()` slides
  the remaining chips, and the same `LaunchedEffect` re-scrolls to keep the (new) newest chip in
  view.

### 3. Word-width-based sizing

Each chip's **reserved slot width** — the width it occupies in the `LazyRow` and reports to
siblings — is measured from its own currently-selected word (`chip.alternatives[chip.selectedIndex]`
or `chip.word`) using `rememberTextMeasurer()` with the same `TextStyle` used to render it, plus
fixed horizontal content padding. This replaces the flat `160.dp` (with unused `widthIn(104.dp,
184.dp)`). Clamp to a `MIN_CHIP_WIDTH`/`MAX_CHIP_WIDTH` range (exact dp values tuned against the
real keyboard during implementation, not guessed here) so a one-letter word still has a usable tap
target and a very long word doesn't consume the whole strip at rest. `REEL_STEP_DP` (vertical step,
currently 36dp) and the strip's pinned height (currently `REEL_STEP_DP * MAX_REEL_VISIBLE_SLOTS =
116dp`) are reduced together; exact values tuned live against the running keyboard, not guessed
here.

A chip's reserved width recalculates whenever its selected word changes (including after
committing a wider alternative or a join), so at rest a chip never overflows its own reserved
slot.

### 4. Unified overflow-grow rendering

One rendering primitive covers both "an alternative in this chip's own vertical reel is wider than
the chip's reserved slot" (e.g. previewing a long word while dragging) and "there's a join
candidate wider than a single chip's slot" (section 5): the currently *displayed* row (the one
centered in the vertical reel, whether mid-drag or settled) is measured at its **natural** width.
If that's wider than the chip's reserved slot width, the row is drawn at its natural width instead
of being constrained to the slot, anchored at the chip's leading edge, with elevated `zIndex` so it
paints over the adjacent chip's content -- while the chip's own **reported layout width** to the
`LazyRow` stays at the reserved slot width, so neighboring items never reflow because of this
purely-visual overflow. Implemented as a custom `Modifier.layout { measurable, constraints -> ... }`
that measures the content at its natural (possibly wider) size but reports the reserved size to the
parent.

- Growth direction: toward the adjacent chip that is more-recently-typed (the "next" chip in
  underlying, non-reversed order) when one exists on that side; otherwise toward whichever single
  neighbor exists; otherwise (no neighbor, e.g. the newest chip at the strip's leading edge)
  capped to the available space up to the strip's own padding.
- Growth is capped at one neighbor's reserved width (own reserved width + one neighbor's reserved
  width + inter-chip spacing). Content still wider than that is truncated with an ellipsis
  (`maxLines = 1`, standard Compose text overflow).
- This state is transient: once a row stops being the displayed one (drag moves on, or the drag
  ends without selecting it), it returns to being clipped/hidden like any other non-centered reel
  row. Once a wider row *is* committed as the chip's selected word, section 3 recalculates the
  chip's reserved width to fit it, so it's no longer "overflowing" at rest.

### 5. Join candidates as an attached reel slot

For each join `ReplacementOption` (`sourceWords.size > 1`, `replacementWords.size == 1`), find the
contiguous run of chips (in canonical, non-RTL-reversed order) whose `.word` values equal
`option.sourceWords` in order. If found:

- Append one virtual candidate to the **first** chip in that run's own alternatives, past its last
  real alternative (so dragging `wh` past its last real word reveals `what`).
- Append the same virtual candidate to the **last** chip in that run's own alternatives, past its
  last real alternative in the opposite drag direction (so dragging `at` past its last real word
  also reveals `what`).
- If no matching contiguous run is found (the option is stale relative to the current chip list),
  the option is simply not attached anywhere this render -- it will reappear once
  `updateReplacementOptions` produces one that matches again.

Rendering the virtual candidate row uses section 4's overflow-grow primitive: its natural width
(from `replacementReelLayout`'s already-existing joined/split width math) is almost always wider
than a single chip's reserved slot, so it grows into the neighbor(s) spanned by
`option.sourceWords`, elevated in z-index, exactly like an oversized same-chip alternative.

Selecting it (drag-release past the threshold) calls the existing `onReplacementPreview` /
`onReplacementRelease` / `onReplacementCancel` callbacks with the matched `ReplacementOption` --
identical to today's behavior, just invoked from the attached chip's own drag gesture instead of
the separate `ReplacementReelSlot`. On release, the source chips collapse into the single
replacement chip the same way the existing replacement-commit path already handles (via
`SwipeTypingCoordinator.releaseReplacement`, already wired).

The standalone `ReplacementReelSlot`/`ReplacementReelGroup` rendering at the edge of the strip is
removed for join options; it remains for split options only (out of scope, see above), so
`ReplacementReelLayout.replacementReelLayout` and friends stay in use but are only reached for
`isJoined == false` groups going forward. `SuggestionStrip`'s top-level parameters
(`replacementOptions`, `onReplacementPreview`, `onReplacementRelease`, `onReplacementCancel`) are
unchanged; only which composable renders join entries moves.

## Testing

- `SuggestionReelMathTest.kt`: add coverage for word-width-based slot sizing (given a measured
  text width, the clamped reserved width) and for the overflow-grow math (given a row's natural
  width vs. a chip's reserved width and its neighbor's reserved width, the resulting drawn width
  and truncation point) as pure functions extracted into `SuggestionReelMath.kt`, independent of
  Compose.
- Add a pure-function test file (or extend `ReplacementReelLayoutTest.kt`) for the new
  chip-word-to-`ReplacementOption` correlation logic (contiguous-run matching), covering: exact
  match, no match (stale option), and a run at either end of the chip list.
- `ImeReelE2eTest.kt`: extend with connected-test coverage for: immediate commit (no animation
  wait needed before the committed text appears in the editor), auto-scroll keeping a newly typed
  chip in frame after several words, and dragging `wh` (or an equivalent fixture pair) past its
  last alternative to commit the joined word, asserting the editor text and that both source chips
  are gone afterward.

## Worktree

Implementation will be performed directly on `dev` (matching this repo's existing branch policy),
not in an isolated worktree, since it's UI-only and low-risk to iterate on live.
