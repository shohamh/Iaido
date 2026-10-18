# Context-aware correction polish plan

## Task 1: Slot-aligned structured alternatives ✓

- Add source-slot-aligned display tokens for equal-cardinality replacements.
- Carry the structured option identity with the displayed alternative so choosing one token still previews/commits the full sequence.
- Keep split and join options on their structured preview/commit path.
- Add model and preview regressions for aligned candidates and atomic selection.

## Task 2: Bounded right-context scoring and confidence margin ✓

- Add a scorer API that includes following words up to the n-gram context window.
- Use it in sentence reranking and flow correction.
- Keep the current candidate first unless an alternative exceeds the configured margin.
- Add tests where right context changes the winner and weak alternatives do not displace current text.

## Task 3: Explicit correction learning ✓

- Extend personal n-gram reinforcement to explicit suggestion picks and confirmed manual edits, retaining delete-and-retype behavior.
- Restore persisted n-gram overrides into the in-memory dictionary at startup.
- Apply a bounded personal-pair log boost in context scoring.
- Capture previous/next words at correction sites; do not learn automatic corrections.
- Add core and Room repository regressions for signal policy, scoring, restore, forget, and reset.

## Task 4: Verify and integrate

- Run the full core-engine and app unit suites, generator tests, and diff whitespace checks.
- Review the final diff and integrate to `main` only after the current remote tip and verification are confirmed.

## Execution status

- [x] Baseline core/app Gradle unit suites passed (clean run, 2026-10-18).
- [x] Baseline Python generator tests passed (11 tests).
- [x] Tasks 1-3 implementation and regression coverage.
- [x] Clean core-engine and app unit suites passed.
- [x] Python generator tests passed (11 tests).
- [ ] Final diff review, remote-tip check, and integration.
