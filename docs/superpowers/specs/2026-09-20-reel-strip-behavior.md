# Reel and suggestion-strip behavior contract

Status: Approved for implementation

Implementation choices:

- The sentence-aware context window is `N = 3` words on either side of the changed word.
- One logical multi-path event accepts one to four paths. Within that event, inference evaluates
  the observed assignment and one swapped assignment for every unordered pair of paths.
- Multi-path timing uses the continuous formula `closeness = 1 - clamp(delta / graceWindow, 0, 1)`;
  the resulting value scales only frequency/context evidence, while path-fit evidence is unchanged.
- When the cursor is in whitespace, focus remains on the nearest word on the side from which the
  cursor entered that whitespace; otherwise focus is the word containing the cursor.

This document consolidates the reel and suggestion-strip behavior described during the
debugging sessions. It is the contract that the connected IME end-to-end tests will verify.

## Terms

- A **word reel** is the vertical reel attached to one word in the current sentence.
- A **candidate** is one visible replacement row in that reel. The current word is also a
  candidate when it has no alternatives.
- A **sentence word** is a word with a tracked editor span, whether it was typed, swiped, or
  produced by a segmentation/inference result.
- A **split candidate** is one replacement option containing multiple output words, such as
  `I mo` for the source word `imo`. It is still one candidate row in the source word's reel.

## Behavior contract

### 1. Every sentence word has exactly one addressable reel

- Every tracked sentence word gets one word reel, including a one-character word such as `a`,
  `I`, or `x`.
- Typing a word creates or refreshes its reel after every committed letter. The reel must not wait
  for a space or another delimiter before appearing.
- A one-character word with no alternative candidates still renders a one-row reel containing the
  word itself. It must not disappear merely because its alternative list is empty.
- Adding another typed letter updates that same word's reel and does not create a duplicate reel.
- A word replacement updates the existing sentence word and its reel rather than appending a new
  word or creating a second reel for the same span. It also triggers the sentence-aware updates
  described in section 3.

### 2. Whole-word replacement is atomic

- Selecting a candidate replaces the complete source word span.
- If `hey` is changed to `heyday` and then changed back to `hey`, the result is exactly `hey`.
  No suffix from `heyday` may remain, and the caret is immediately after the replacement.
- Releasing a reel candidate must never insert text at the caret while leaving the old source word
  in place.
- The same rule applies to typed words, swiped words, corrected words, and words selected after a
  cursor move.

### 3. Reel changes are sentence-aware

- A reel update is evaluated in the context of the complete current sentence, not as an isolated
  word correction.
- When a word changes, the candidate lists and ranking for up to `N` neighboring words before and
  after it are recalculated using the updated sentence and the configured frequency/context data.
  `N` is a shared, documented context-window setting rather than an accidental caller-specific
  limit.
- The changed word and every affected neighboring reel refresh in place. The strip keeps one reel
  per sentence word and does not duplicate or reorder reels during the refresh.
- A neighboring reel may change its visible candidate, score, or ordering even when that neighboring
  word was not directly selected. This is intentional: one correction can improve the rest of the
  sentence's suggestions.
- Words outside the `N`-word context window are not recalculated solely because of this update.
- If a currently selected candidate remains valid after recalculation, its word stays selected;
  otherwise the reel falls back to the current sentence word and keeps that word visible.
- The E2E fixture must make the before/after ranking difference observable, including candidate
  text or score labels, so a test cannot pass merely because the strip recomposed.

### 4. Candidates are visible, bounded, and selectable only when visible

- Every selectable candidate row has visible text. A candidate must never be hit-testable while
  rendering as a blank or transparent row.
- Scrolling a reel down four candidate steps must show readable text at the resting position.
- The focused/current candidate is fully visible in its reel viewport; it must not be clipped in
  the middle of the word.
- A reel's height is based on the number of candidates it actually contains, capped by the
  configured maximum visible rows. A one-row reel is one row tall, a two-row reel is two rows tall,
  and so on.
- The strip must not reserve or expose empty space below the last real candidate. There must be no
  invisible options below the visible content that can be selected by swiping.
- Long candidate text may grow into the permitted neighboring visual space, but the reel's layout
  slot remains bounded and must not create a giant standalone reel.

### 5. The strip represents the whole sentence without duplicates

- The strip shows a reel for every currently tracked sentence word, not only the most recently
  typed word or a cursor-local subset.
- Each sentence word appears once. Recomposition, candidate refreshes, cursor movement, and
  replacement previews must not duplicate reels.
- In left-to-right text, the newest sentence word is the rightmost reel. In right-to-left text,
  ordering follows the corresponding reading direction while preserving one reel per word.
- Removing, joining, or replacing words removes only the reels for spans that no longer exist and
  preserves the remaining sentence order.

### 6. Cursor movement controls the focused reel

- Moving the editor cursor into a word focuses that word's reel.
- Moving the cursor from a later word back to an earlier word scrolls the strip back to the
  earlier reel; moving forward scrolls it forward again.
- The focused reel is brought fully into the horizontal viewport, even when the sentence has more
  reels than fit on screen.
- Cursor movement changes focus without reordering or duplicating sentence reels.
- At a word boundary, the focused word is deterministic and consistent in both directions. The
  recommended default is the word containing the cursor; when the cursor is in whitespace, focus
  the nearest word on the side from which the cursor entered that whitespace.

### 7. Multi-word split replacements belong to the source word reel

- A multi-word replacement such as `I mo` for `imo` is one candidate row attached to the `imo`
  word reel.
- It must not appear as a separate two-word reel, a pair of independent reels, or a giant split
  reel beside the source word.
- The candidate text may contain a space, but it is one selectable candidate and one replacement
  action.
- Candidate ordering comes from the configured ranking/frequency data. The UI must not assume that
  `I mo` is more likely than `imo`; the source word remains a normal candidate and the split
  candidate is positioned according to the ranking supplied by the generator.
- If the source word or its span disappears, the attached split candidate disappears with it and
  must not remain as a stale standalone reel.

### 8. Editing a selected split result creates independent word reels

- Selecting a split candidate such as `imo` → `I mo` may initially keep the selected result as one
  composite split reel so the replacement can settle as one action.
- After that split result has been selected, deleting or editing any character in either resulting
  word breaks the composite split relationship.
- Once the relationship is broken, the strip renders one independent word reel per remaining word:
  `I` gets its own reel and `mo` gets its own reel. Each reel has its own span, candidates, focus,
  and replacement behavior.
- Editing one split word must not keep the other word trapped inside a composite reel. The untouched
  word becomes independently addressable at the same time as the edited word.
- Deleting a character that removes a word removes only that word's reel; it must not leave an empty
  reel or silently merge the remaining word back into the composite split reel.
- The independent reels appear in sentence order, are not duplicated, and are included in the
  sentence-aware neighbor recalculation from section 3.
- After the split has been broken, a later correction of one resulting word affects that word and
  its configured neighboring context, not the other split word as if they were still one span.

### 9. Joining adjacent words is offered from both source reels

- For adjacent source words such as `in to`, the candidate `into` appears on both the `in` reel and
  the `to` reel. Both entries represent the same join action and use the same source-word span.
- The join candidate is attached to the source reels; it is not rendered as a separate edge reel,
  a third reel, or a giant two-word chip beside them.
- When the `into` row is centered in the `in` reel, that row expands forward across the `to` reel's
  reserved width and the inter-reel gap, visually uniting the two reels for that row.
- When the same `into` row is centered in the `to` reel, it expands backward across the `in` reel's
  reserved width and gap. The expansion direction is determined by which source reel is displaying
  the candidate, not by a hard-coded left-only or right-only rule.
- The expanded join row is visually one candidate row: one label, one gesture target, one bounded
  union of the two source reel slots. It must not duplicate the text or create an extra blank slot.
- The join candidate is offered only while the source words are adjacent and unchanged. If either
  source word changes, is deleted, or is joined elsewhere, the stale join disappears from both
  source reels.
- Selecting `into` from either source reel atomically replaces the full `in ` + `to` span with
  `into`, removes both source reels, creates one reel for `into`, and recalculates the affected
  neighboring context according to section 3.
- The behavior generalizes to longer contiguous joins: every source reel in the join may expose the
  same candidate, and the visual row spans the complete source run in the direction appropriate to
  the reel displaying it.

### 10. Real IME verification is the acceptance surface

- Connected UI verification uses the real Settings screen with the real Iaido IME selected, types
  text into the live preview, and captures screenshots of that screen.
- A blank Compose-only harness screenshot is not evidence that the Settings/IME behavior works.
- Debug verification may use the separately named `Iaido Debug` IME and `Iaido Debug Settings`, so
  the test can select the intended IME unambiguously.

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
- When a two-finger gesture resolves to a split result, its coordinated split reel behavior follows
  sections 7 and 8: the split candidate is visually structured, and editing either resulting word
  breaks the composite relationship into independent reels.

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

## Planned connected IME E2E coverage

The tests will be added to `app/src/androidTest/kotlin/com/iaido/app/ImeReelE2eTest.kt`, with
Settings-screen screenshot coverage in `SettingsImeReelE2eTest.kt` where the real preview is the
important assertion surface.

| ID | Scenario | Assertions |
| --- | --- | --- |
| E2E-01 | Type `hey`, select `heyday`, then select `hey` | The editor contains exactly `hey`; no residual suffix; caret is after the word; the same reel remains addressable. |
| E2E-02 | Type a multi-letter word one key at a time | One reel is present after each letter; the visible candidate set refreshes; no duplicate reel is created. |
| E2E-03 | Type a single-character word | One addressable reel exists; its one-row viewport contains the readable current character. |
| E2E-04 | Type a sentence containing one-, two-, and multi-character words | Every sentence word has exactly one reel; the newest is at the right in LTR; no reel is duplicated. |
| E2E-05 | Move the cursor from the newest word to an earlier word and back | The focused reel follows the cursor in both directions; the focused reel is fully in the horizontal viewport. |
| E2E-06 | Focus a word whose reel is horizontally off-screen | The strip scrolls to the focused reel; its full current row is visible; neighboring reel order is unchanged. |
| E2E-07 | Scroll one reel four options down | The resting candidate row contains readable visible text and its text bounds are inside the reel viewport. |
| E2E-08 | Compare reels with one, two, and three-plus candidates | Each reel's measured height matches its real visible candidate count up to the cap; no empty rows remain below content. |
| E2E-09 | Exercise an invisible-option regression fixture | Every selectable row exposes visible candidate text; swiping past the last real row cannot select an invisible word. |
| E2E-10 | Use `imo` with the split candidate `I mo` | `I mo` appears as one candidate in the `imo` reel, not as a separate two-word/giant reel; replacement is atomic. |
| E2E-11 | Replace a middle word in a sentence with context-sensitive fixture data | The changed reel and up to `N` reels before and after visibly update their candidate/ranking content; a word outside the window does not change. |
| E2E-12 | Replace the same word twice with different candidates | The second update uses the new sentence context, not the original sentence; affected neighboring reels visibly recalculate again. |
| E2E-13 | Update a word while the strip is horizontally scrolled | Recalculation refreshes existing reel slots in place; no duplicate chips, jumps to an unrelated word, or blank focused row appears. |
| E2E-14 | Offer `into` for the adjacent source words `in to` | `into` is visible on both the `in` reel and the `to` reel; no standalone join reel exists. |
| E2E-15 | Center `into` from the `in` reel | The single `into` row expands forward over the `to` reel and gap; its bounds equal the two-reel union and its text is fully visible. |
| E2E-16 | Center `into` from the `to` reel | The single `into` row expands backward over the `in` reel and gap; it is not clipped or shifted to a separate reel. |
| E2E-17 | Commit `into` from the first source reel | The full `in to` span becomes exactly `into`; both source reels disappear; one `into` reel remains; neighbor context recalculates. |
| E2E-18 | Commit `into` from the second source reel | The same atomic result occurs when the join is released from the `to` reel. |
| E2E-19 | Change or delete one source word after a join is offered | The stale `into` candidate disappears from both source reels and no stale standalone reel remains. |
| E2E-20 | Exercise join expansion at the beginning and end of a sentence | Forward and backward expansion work at strip edges without clipping, invisible space, or an off-screen hit target. |
| E2E-21 | Move the cursor across a sentence after a context update and join | Focus follows the correct updated reels; the strip remains ordered and the focused candidate is fully visible. |
| E2E-22 | Select `imo` → `I mo`, then edit a character in one split word | The composite split reel breaks into separate `I` and `mo` reels; both are visible, independently bounded, and ordered. |
| E2E-23 | Select `imo` → `I mo`, then delete a character in the other split word | The deleted word's reel updates or disappears according to its remaining text; the untouched split word gets its own independent reel; no composite or empty reel remains. |
| E2E-24 | Correct one word after a split result has broken apart | Only the selected independent word is replaced; the other split word remains separate; sentence-aware neighbors recalculate from the new sentence. |
| E2E-25 | Type and move the cursor in the real Settings preview | The screenshot shows the typed preview, all expected reels, readable candidate rows, and no blank harness-only result. |

## Test implementation rules

- Prefer deterministic debug fixtures for candidate lists and frequencies so the tests assert the
  behavior rather than a changing production dictionary.
- Use the real `ImeScenario` editor and IME gestures for text, cursor, and reel actions. Do not
  replace the IME path with direct calls to `SuggestionStrip` for these acceptance tests.
- Add polling/settling helpers that wait for the actual strip state before reading bounds or
  screenshots; do not use fixed sleeps as the only synchronization.
- Assert both accessibility-visible candidate text and screenshot artifacts when a test concerns
  visual visibility. A screenshot is retained as a diagnostic artifact on failure.
- Treat strip geometry as a first-class assertion, not only as a screenshot artifact. Each reel and
  visible candidate row must expose stable test semantics containing its word/reel identity and
  candidate text, so the E2E test can assert visible bounds, text bounds, and containment in the
  strip viewport.
- For a normal word reel, assert that the focused row's bounds are fully contained by that reel's
  bounds. For a join row, assert that its bounds cover the union of the two source reel bounds in
  the expected direction, without creating a third reel item.
- For each visual scenario, retain a screenshot after the state settles and assert the structural
  geometry/text first. Screenshots are diagnostic evidence and visual review artifacts; they are
  not the only assertion because device rendering can differ by density and theme.
- Context-update tests must enable candidate-score display or use fixture words whose candidate
  text changes, so the test proves that neighboring reels recalculated rather than merely
  recomposed.
- Split-result tests must capture both states: the initially selected composite split reel, and the
  post-edit state with one independent reel per resulting word. They must assert separate reel
  bounds and candidate semantics for both words, not only the final editor text.
- Infer-space and multitouch tests must capture the strip after every boundary decision that changes
  the editor text. Each assertion must compare the expected output-word count, reel order, reel
  shape, visible candidate text, and focused bounds; final editor text alone is insufficient.
- Type-before/type-after-two-finger tests must assert the hard boundary explicitly: the typed word's
  reel remains separate unless the fixture intentionally chooses a join, and no pending gesture
  transaction may rewrite across it.
- Two-finger tests must validate both the merged and boundary-preserving outcomes, including the
  number and geometry of visible reels, and must use touch-down order to identify the source parts.
- Reordering tests must run identical multi-swipe paths with swapped touch-down assignments for
  every pair within the same gesture and assert the selected language result plus the visible reel
  structure, not just the raw pointer order. A three-or-more-path fixture is required to prove
  that internal pairs are not skipped.
- Timing-weight tests must use the same paths with at least one near-simultaneous timestamp pair and
  one deliberately separated pair. They must assert both the selected text segmentation and the
  corresponding number/geometry of reels, proving that timing changes the score without making
  unrelated gestures reorder.
- When accessibility publication is stale, the test helper must first trigger a real strip/editor
  state update and then re-read the actual node; it must not silently pass using a guessed screen
  coordinate.
- Run the suite on the Android emulator. A connected phone run is additional evidence, but a
  phone launch timeout is reported separately rather than counted as a passing IME result.

## Sign-off questions

Please confirm these points before I implement the E2E tests:

1. Is a one-character word with no alternatives correctly represented by a one-row reel containing
   only that character?
2. Is the recommended whitespace-boundary rule acceptable, or should whitespace always focus the
   previous word / next word instead?
3. Is `I mo` correctly specified as one spaced candidate row inside the `imo` reel, with ranking
   determined by the fixture/data rather than a hard-coded preference?
4. Should `N` be the existing configured context window, or do you want a specific numeric value
   for the before/after neighbor recalculation?
5. Is the `in` + `to` → `into` join behavior correct when the row expands over both reels in
   either direction, and should the join candidate be offered from every source reel for joins of
   more than two words?
6. Is the proposed visual-test contract correct: stable reel/candidate semantics plus bounds and
   containment assertions, with screenshots retained as evidence rather than relying on pixel
   equality alone?
7. Is the split-result rule correct: after editing or deleting any character in either word of
   `I mo`, the composite split reel breaks into independent reels for the remaining words?
8. Should typed input always finalize and clear a pending infer-space/two-finger transaction, so
   later swipes cannot rewrite across the typed word?
9. Are the merged and boundary-preserving two-finger outcomes, plus the `Space after swipe`
   exactly-one-space rule, the combinations you want covered?
10. Is the reorder search correct as specified: for every pair of swipe paths within one gesture,
    evaluate observed and swapped assignments, including all `previous-current` and
    `current-next` positions, then weight the language score by the measured time closeness of the
    two paths so accidental touchdown order can be corrected?
