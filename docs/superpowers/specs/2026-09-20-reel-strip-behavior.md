# Sentence-first reel-strip behavior contract

Status: Draft for review. This replaces the previous box-style reel-strip behavior contract before implementation.

The strip is a clear, horizontally scrollable sentence. The currently committed word stays in line; autocorrect alternatives sit above and below it. This contract reflects the behavior requested for the interactive prototype and is the behavioral source for the native Jetpack Compose design.

The existing infer-space, multi-path recognition, and typing-boundary rules are retained below. This document changes their presentation and the word-level interactions, not their recognition scoring.

## Terms

- A **word lane** is one sentence word and its inline alternatives. It has no enclosing candidate box at rest.
- A **word reel** is the candidate state associated with one word lane. The word reel is interaction state, not a drawn box or chip.
- A **current word** is the text currently committed to the editor at a tracked word span.
- An **alternative** is a distinct autocorrect result placed above or below a current word.
- A **preview** is the temporary sentence shown while a gesture is held. It does not mutate editor text until release.
- A **compound preview** is a split or join preview that affects more than one output word.
- A **deletion preview** is a reversible, in-gesture selection of one or more words for deletion.
- The **IME cursor** is the host editor selection offset reported through InputConnection. It is authoritative.

## Behavior contract

### 1. The strip reads as a sentence

- Render the current sentence as ordinary inline text in reading order. Do not draw a separate box or chip around each word at rest.
- Keep the selected/current word larger and brighter than its alternatives. Show at most one alternative above and one below each current word, with lower opacity that remains comfortably readable.
- Offer autocorrect word suggestions, including split and join results. Do not offer capitalization-only variants or duplicate alternatives. De-duplicate case-insensitively using the active language locale while preserving the chosen display casing.
- Keep every current sentence word addressable, including one-character words. A correction updates the same sentence span; it does not append a second copy.
- Preserve punctuation and surrounding spaces unless a split, join, or deletion explicitly changes that span.
- Keep the strip's content and focus tied to the active editor selection, not to the last word typed.

### 2. Up and down are reversible alternative swaps

- A vertical swipe starts on a current word and moves toward its upper or lower alternative. While held, show the whole sentence as it would look after release.
- Preview is non-mutating: the editor text, selection, and committed history stay unchanged until pointer release.
- Releasing over a valid alternative atomically replaces the source span and puts the displaced current word in that same alternative position. Swiping in the same direction again can therefore swap back to the previous word.
- The opposite-side alternative remains available if it is still valid and distinct. Never show the current word as its own alternative or duplicate an option across the current/upper/lower entries for the same source.
- A missing alternative or a drag beyond the available option clamps at that end; ordinary alternative selection never wraps around the candidate list.
- Releasing outside a valid alternative or cancelling the pointer stream leaves the editor unchanged and clears the preview.
- A committed correction places the editor cursor immediately after the resulting text span.

### 3. Splits and joins preview as sentence text

- A split suggestion such as “alot” -> “a lot” previews both output words with normal sentence spacing. Releasing commits the whole split atomically and creates two independently addressable word lanes.
- A join suggestion such as “in to” -> “into” is reachable from either source word: forward from “in” and backward from “to”.
- A normal one-word alternative such as “inside” for “in” replaces only the “in” span and preserves the following “to”. A candidate may consume adjacent words only when its source span explicitly includes those words as a join.
- During a join preview, show one thin muted-cyan outline around the union of both source word areas and their gap. Center “into” and its alternatives in that union so the label does not clip or overlap neighboring text.
- Do not add a third word lane for a join. Keep surrounding sentence words in place and readable.
- Releasing a join replaces the complete source span and its inter-word separator with the joined word in one edit; both source words disappear and one “into” lane remains.
- A source change, deletion, cursor edit, or competing correction invalidates stale split/join suggestions. A stale option must not remain visible or selectable.

### 4. The strip cursor always mirrors the IME cursor

- Render a distinct, steady caret in the sentence strip at the same logical text offset as the host editor cursor. Update it immediately for typing, external selection changes, correction commits, deletion, and undo/redo.
- Tapping a whole word places the cursor at the end of that word, as requested. Tapping a character places it at that character boundary. Tapping the whitespace gap places it at the corresponding insertion boundary.
- Holding on a word places the cursor at the character under the initial touch. Moving horizontally while still held scrubs the cursor through the sentence immediately, before entering an edge-scroll zone.
- Word focus and cursor position are related but distinct: the strip may focus the containing or nearest word while the caret remains at the exact character/gap offset.
- Use the same UTF-16 selection offsets as InputConnection. Text measurement and hit-testing must account for variable-width glyphs, grapheme clusters, punctuation, and RTL runs; never infer a caret from a fixed character-width estimate.
- Cursor focus at whitespace is deterministic: use the nearest adjacent word, retaining the previous focus only for an exact tie. The caret itself remains at the requested whitespace boundary.

### 5. Horizontal navigation and word hit areas

- A left/right swipe across the strip scrolls the sentence horizontally without selecting a correction. Scrolling is continuous, clamps at either end, and never wraps.
- When cursor focus changes to a word outside the viewport, move it into view quickly and smoothly. Aim to place it near the viewport center when there is enough sentence content before and after it; clamp naturally near the first and last words.
- Do not recenter repeatedly when the cursor is already visible and stable. Avoid layout jumps when suggestion text or preview state changes.
- Increase the visible horizontal word spacing by exactly 4 prototype pixels over the prior strip spacing, not 5. In Compose, scale this from the reference screenshot density and preserve the same visual gap.
- Expand each word's gesture hit area into half of its neighboring spacing. For the added 4-pixel gap, each adjacent hit area reaches 2 pixels into that gap; text bounds and visible spacing do not change.
- Small words such as “it” must be as easy to target as longer words. A miss on a word must not accidentally start deletion of a neighbor.

### 6. Deletion is a reversible horizontal extension of a word gesture

- A gesture begins on a word. Vertical movement first previews that word's upper/lower alternative, as in section 2.
- Moving horizontally into another word changes the gesture into deletion-preview mode. Crossing into that neighboring word's hit area activates deletion mode; its word is included only when the pointer crosses that word's midpoint.
- The starting word is included once deletion mode activates. Further words in the same direction are added one at a time only as their midpoints are crossed.
- Moving back across a selected word's midpoint removes that word from the preview. Returning over the original word cancels deletion mode and restores the vertical alternative preview instead.
- Show one red outline around the contiguous deletion range and strike through every word currently selected for deletion. Hide all alternatives for selected words while they are marked.
- The visible editor text remains unchanged during preview. Releasing commits the selected contiguous deletion as one history action; cancellation restores the original sentence with no edit.
- Preserve natural spacing at the deletion boundary and place the cursor at the deletion start. Do not delete punctuation or neighboring words outside the preview range.

### 7. Edge scrolling extends cursor movement and deletion

- Show edge-scroll affordances only during an eligible held cursor or deletion gesture near the left/right limits of the sentence viewport.
- Each zone is a small, soft gradient rectangle with no border and no red fill. Fade from transparent at its inner edge toward no more than 50% opacity at its outer edge. Use a slim, clearly visible directional chevron: larger than the too-small prototype iteration, visually narrow rather than wide.
- Keep the zones smaller and more transparent than the earlier version. The overlay must not obscure the word or caret underneath.
- Holding in an edge zone scrolls the strip continuously in that direction. It must continue updating the caret during cursor scrubbing and continue adding/removing words during deletion preview as newly visible word midpoints pass the fixed finger position.
- Scroll speed increases continuously with normalized finger penetration into the zone. Use a smooth nonlinear curve (the prototype reference is 24 + 780 * depth^2 CSS pixels/second, clamped by content bounds); tune the equivalent Compose dp/second values against the reference device.
- Update the gesture once per animation frame using elapsed frame time, not a fixed number of pixels per timer tick. Keep pointer ownership and hit geometry stable throughout the gesture.
- Pulling back toward the inner edge reduces speed and reverses/unselects words naturally. Reaching the sentence end clamps; edge scrolling never wraps to the other end.

### 8. Undo and redo are visible, multi-step actions

- Keep small icon-only undo and redo buttons visible beside the strip header/focus label.
- Keep the controls independently disabled and greyed out when their respective history stack is empty. A disabled control cannot be activated or show a misleading preview.
- Maintain multiple logical edit actions, not a one-action toggle. Undo moves the most recent edit to redo; redo moves it back. A new edit after undo clears the redo stack.
- Treat a correction, split, join, or committed multi-word deletion as one logical action. Group rapid character typing and repeated backspaces into sensible typing/deletion transactions rather than one history entry per key.
- A quick tap applies undo/redo immediately. Holding an enabled button shows a small tooltip with the action and a before/after sentence preview; releasing applies that action. Moving away or cancelling dismisses the preview without applying it.
- Keep cursor/selection restoration in history so undo and redo restore the corresponding editor text and cursor position.
- Preserve the prototype's 40-entry history limit as the initial implementation target; make the limit explicit and test that older history is pruned without corrupting redo.

### 9. Sentence-aware candidate updates remain stable

- Candidate generation continues to use the current sentence and configured language data. The strip renders the result; it does not invent casing candidates or alter ranking.
- When a correction changes sentence context, refresh affected neighboring options in place using the existing configured context window. Keep unaffected words, ordering, cursor, and scroll position stable.
- De-duplicate options after merging live and stored suggestions. A committed/current word never appears as an alternative to itself.
- If the focused option disappears after refresh, restore the current word as selected and keep the caret synchronized.

### 10. Real IME verification is the acceptance surface

- Connected UI verification uses the real Settings screen with the real Iaido IME selected and types into the live preview. Capture the editor and keyboard in the same screenshot.
- A blank Compose-only harness screenshot is not evidence that Settings/IME integration works.
- Debug verification may use the separately named Iaido Debug IME and Iaido Debug Settings, selecting them unambiguously.

### 11. Infer-space and two-finger swipe/type interactions

- In `Infer spaces` mode, each completed swipe reanalyzes the bounded recent run using dictionary
  frequency, path fit, and sentence context. The resulting text segmentation is the source of
  truth for the strip: one output word produces one reel, and multiple output words produce one
  reel per output word.
- Sequential single-finger swipes remain separate when the fixture's score favors separate words,
  join when context and frequency favor one word, and may revise an earlier boundary after a later
  swipe. The strip must visibly follow each reanalysis without accumulating duplicate reels or
  stale source spans.
- A single gesture may split into two or three words when the segmentation score favors that
  result. The resulting word reels are ordered in sentence order and are independently addressable.
- Two concurrent swipe paths are one logical gesture unit, but their observed finger touch-down
  order is only one hypothesis. Inference must score the observed order and the relevant reordered
  hypotheses before committing the result.
- For every pair of swipe paths within one multi-swipe gesture, the reorder search must evaluate
  the observed assignment and the swapped assignment. This includes every pair that occupies a
  `previous-current` position and every pair that occupies a `current-next` position in the
  gesture's local path order; it is not limited to one pair, one end of the gesture, or only
  adjacent pairs when more paths are present. The search must preserve the actual gesture paths;
  it changes only their candidate ordering/assignment hypothesis.
- The pairwise reorder search applies only within that single gesture event. Separate completed
  gesture events are handled by infer-space reanalysis and are never silently reordered as if they
  were fingers in the same gesture.
- A reorder is preferred when its merged/boundary-preserving words have stronger language evidence,
  not merely because those fingers arrived first. Evidence combines dictionary frequency, path fit,
  and n-gram sentence context.
- Frequency/context evidence for a two-finger interpretation is weighted by how close in time the
  two paths were. Near-simultaneous paths receive strong two-finger/reordering evidence; paths with
  a larger time gap receive less, so unrelated sequential gestures are not incorrectly reordered
  into one word.
- The timing weight is continuous or uses documented deterministic buckets, and the same timing
  value is used for all competing order hypotheses for that gesture. It must not be tuned after
  seeing the expected answer for an individual fixture.
- After scoring, inference may choose the merged word, the boundary-preserving words, or the best
  locally reordered interpretation. The selected interpretation becomes the one logical transaction
  used by the strip and editor.
- A physical finger lift is not itself a committed space. In `Space after swipe` mode, a complete
  two-finger gesture adds at most one trailing space; in `Infer spaces` mode, the inferred
  segmentation decides the boundaries.
- Typed input, an explicit space or punctuation, cursor movement, cancellation, or an external host
  edit finalizes and clears the active inference run. Typed characters must not be silently absorbed
  into a pending swipe transaction or cause a later two-finger gesture to rewrite across that hard
  boundary.
- A typed word before or after a two-finger gesture remains a separate sentence word unless the
  configured inference result explicitly joins it. Its reel appears in the correct sentence order
  and is not duplicated when the gesture transaction finalizes.
- When a two-finger gesture resolves to a split result, the output follows section 3: each
  resulting word is independently addressable in sentence order, and the inferred edit remains one
  transaction.

## Infer-space and multitouch E2E coverage

These journeys use the real `ImeScenario` pointer injector and deterministic auto-space fixtures.
They assert both editor text/caret state and the resulting reel strip after every reanalysis or
typed boundary action.

| ID | Scenario | Assertions |
| --- | --- | --- |
| INF-01 | Infer spaces with the `SEPARATE` fixture | `in` and `the` remain separate; the strip shows exactly two corresponding word reels in order. |
| INF-02 | Infer spaces with the `JOIN_REEL` fixture | `in` + `to` resolves to `into`; the join candidate/reel is visible before release and one `into` reel remains after release. |
| INF-03 | Infer spaces with the `SPLIT_REEL` fixture | `inthe` resolves to `in the`; the strip shows two output word reels and no stale `inthe` reel after commit. |
| INF-04 | Add a later swipe with the `CONTEXT_REVISION` fixture | The earlier `in` + `to` boundary revises to `into`; the strip updates the affected reels without duplicates or stale spans. |
| INF-05 | Use the `LOW_CONFIDENCE` fixture | The top recognition remains unchanged; the strip shows the unchanged word reel and no speculative split/join reel. |
| INF-06 | Resolve a two-finger gesture with `TWO_FINGER_MERGE` | `some` + `thing` becomes one `something` word; the strip shows one merged reel with the correct replacement shape. |
| INF-07 | Resolve a two-finger gesture with `TWO_FINGER_BOUNDARY` | `some` and `thing` remain two words; the strip shows two independently bounded reels and no merged reel. |
| INF-08 | Present the same two-finger paths in both touch-down orders | The chosen output follows the stronger language score rather than blindly following first-touch order; the strip looks the same when the semantic answer is the same. |
| INF-09 | Use a multi-swipe gesture with at least three constituent paths | Every pair of paths is evaluated in both observed and swapped assignment order, including each `previous-current` and `current-next` pair; the selected output and reel structure reflect the best score. |
| INF-10 | Exercise all pairwise swaps within the same gesture | Swapping one pair does not prevent evaluating any other pair; no pair is skipped because it is not at the gesture edge, and no separate gesture event is reordered. |
| INF-11 | Compare near-simultaneous and widely separated two-finger paths | Near-simultaneous paths receive the stronger two-finger/reordering weight; a wide time gap does not incorrectly merge sequential words. |
| INF-12 | Type after a pending inference gesture | Typed input finalizes the pending run; the typed word is separate, has its own reel, and a later swipe cannot rewrite across the typed boundary. |
| INF-13 | Type before a two-finger gesture | The typed word remains in sentence order; the two-finger result follows it with the correct spacing and reel count. |
| INF-14 | Type after a two-finger merged result | The merged word remains one reel, the typed suffix word gets a new reel, and no duplicate transaction/source reel appears. |
| INF-15 | Move the cursor during an inference run | The run finalizes, the cursor remains at the reported position, and the strip focuses the correct resulting word without reordering reels. |
| INF-16 | Cancel a two-finger or inferred replacement | Editor text, caret, and reel structure return to the pre-transaction state; no temporary composite reel remains. |
| INF-17 | Exercise the six-unit inference bound | The oldest unit freezes at the bound, only the allowed recent units reanalyze, and the strip preserves the frozen reel plus the new result reels. |
| INF-18 | Use `Space after swipe` with a two-finger gesture | The complete gesture adds exactly one trailing space; the strip shows the gesture's resulting word reels and no per-finger duplicate. |
| INF-19 | Screenshot every inference state transition | Each screenshot is from the real Settings/IME surface and visibly shows the expected word count, ordering, replacement shape, and candidate text. |

## Sentence-first strip E2E coverage

These cases use the real IME editor and deterministic debug suggestion fixtures. Each gesture case asserts the editor state, exact selection offset, visible strip state, and gesture preview before release.

| ID | Scenario | Assertions |
| --- | --- | --- |
| STRIP-01 | Type a sentence with short and long words | One inline word lane per current word; no boxes, duplicate suggestions, or capitalization-only options; text order matches the editor. |
| STRIP-02 | Swipe up on a word with an upper autocorrect option | The complete sentence preview changes while editor text remains unchanged; release commits the correction and places the caret after it. |
| STRIP-03 | Swap up twice on the same word | First release commits the upper option and puts the previous word above; second release restores the original word. |
| STRIP-04 | Repeat STRIP-02/03 for the lower option | Lower-side preview and reversible swap work independently of the upper option. |
| STRIP-05 | Drag beyond the final available option or start with no option | Selection clamps without wrapping; release outside a valid option leaves text unchanged. |
| STRIP-06 | Tap a whole word, a character, and a gap | Whole word selects its end; character/gap selects its exact UTF-16 boundary; strip caret and host selection match after each tap. |
| STRIP-07 | Hold a word and scrub horizontally | Initial cursor lands under the held character; moving left/right updates the host selection before reaching either edge zone. |
| STRIP-08 | Move the cursor in the host editor, then type/correct | Strip caret tracks every external selection/text change; no stale word focus remains. |
| STRIP-09 | Scroll the strip left/right and focus an off-screen word | Scroll clamps without wrapping; focused word is revealed quickly and centered when room exists. |
| STRIP-10 | Preview “alot” -> “a lot” | Preview shows two normally spaced words; editor remains unchanged until release; release commits a single split transaction. |
| STRIP-11 | Preview “in to” -> “into” from “in” | One centered join preview spans both words and the gap; label and alternatives fit inside the union; no third word appears. |
| STRIP-12 | Preview “in to” -> “into” from “to” | Backward join preview has the same bounds/centering and commits the same atomic result on release. |
| STRIP-13 | Invalidate a split/join source during editing | Stale alternatives disappear immediately and are not hit-testable. |
| STRIP-13a | Choose “inside” for “in” in “in to” | Only “in” changes; “to” remains present. A normal autocorrect cannot consume a neighboring word. |
| STRIP-14 | Drag vertically, then enter an adjacent word | Deletion mode starts on entering the neighbor; the neighbor is not selected until its midpoint is crossed. |
| STRIP-15 | Return to the original word before release | Deletion preview disappears and the corresponding vertical alternative preview returns. |
| STRIP-16 | Sweep across two more word midpoints and pull back | Each crossing adds/removes exactly that word; selected words are struck through, alternatives hidden, and one red outline covers the range. |
| STRIP-17 | Release a multi-word deletion | Only previewed words are deleted as one action; surrounding punctuation/spacing and cursor start are correct. |
| STRIP-18 | Hold a deletion gesture at the left/right edge zone | Strip scrolls in that direction; speed rises with zone penetration; words continue to be selected by midpoint without wrapping. |
| STRIP-19 | Hold cursor scrubbing into either edge zone | Sentence scrolls while the cursor remains attached to the finger's visual character position; host and strip offsets stay equal. |
| STRIP-20 | Tap a short word such as “it” beside longer words | Hit area includes half the adjacent gap; gesture selects the intended short word and does not delete its neighbor. |
| STRIP-21 | Focus words at the start, middle, and end of a long sentence | Middle focus centers when possible; first/last focus clamps cleanly; no jump or blank strip frame. |
| STRIP-22 | Apply three edits, undo twice, then redo twice | Each action restores text and cursor; undo/redo buttons enable independently; a new edit clears redo only. |
| STRIP-23 | Hold an enabled undo/redo button, then release | Tooltip and before/after preview identify the pending action; release applies it; cancellation does not. |
| STRIP-24 | Exhaust undo history and separately exhaust redo history | Each icon remains visible and independently greyed when disabled; the other stack remains usable. |
| STRIP-25 | Compare edge-zone, join, deletion, swap, caret, and keyboard states | Screenshots come from real Settings/IME; geometry, clipping, text, color, and motion state are compared with V7 at the same viewport/density. |
| STRIP-26 | Repeat core selection/deletion journeys in Hebrew/RTL | Sentence order, cursor offsets, left/right deletion, focus scrolling, join bounds, and alternative positions follow reading direction without wrap. |
| STRIP-27 | Enable reduced motion and perform swap/scroll/delete | Gestures and previews still work; nonessential transitions shorten or snap without losing state. |

## Test implementation rules

- Use deterministic fixtures for alternatives, split/join options, and sentence length. Fixtures must include short words (“it”), duplicates, casing-only decoys, and words at both scroll edges.
- Use the real ImeScenario/editor and IME pointer injector. Do not substitute direct calls into SuggestionStrip or a Compose-only test harness for acceptance tests.
- Publish stable semantics for the strip, word identity/span, current/upper/lower text, caret offset, active preview mode, join source span, deletion range, and undo/redo enabled state. Include visible bounds so tests can assert clipping, hit areas, and the two-word join union.
- Wait for semantic state and layout bounds to settle through polling. Do not use fixed sleeps as the only synchronization.
- For each gesture preview, hold the pointer down at an intermediate path point, assert preview state and unchanged editor text/selection, then inject release and assert the committed text/selection.
- Retain screenshot artifacts for at-rest sentence, upper/lower preview, split, both join directions, deletion range, both edge zones, undo/redo preview, QWERTY, and Hebrew. Assert structure and text before screenshot comparison.
- Compare native screenshots with keyboard-reel-inline-v7.html at the same device viewport and effective density. Normalize only system bars/insets; do not mask word alignment, font size, caret, gradients, clipping, or key geometry.
- Keep the existing infer-space/multitouch journeys below. They continue to assert recognition output and transaction boundaries, while their strip assertions use inline word lanes rather than chip/reel boxes.
- Do not treat an IME launch timeout or stale accessibility tree as a pass. If semantics are stale, wait for a real editor/strip update and read the actual node; never fall back to a guessed coordinate for a negative assertion.

## Retired box-reel assertions

The implementation should replace tests that require a candidate chip/reel box, a separate edge-anchored join slot, vertical candidate-list scrolling, or one shared reel widget for a split. Those visuals and interaction assumptions are superseded by the sentence-first strip above. Recognition, atomic replacement, infer-space, and real-IME integration assertions remain in scope.

Pure tests for the currently used SuggestionReelMath and ReplacementReelLayout helpers may stay
while that legacy code remains. They are not acceptance tests for the new visual behavior; replace
them with sentence geometry, midpoint, caret mapping, and edge-scroll math tests when the new
geometry/state helpers are introduced.
