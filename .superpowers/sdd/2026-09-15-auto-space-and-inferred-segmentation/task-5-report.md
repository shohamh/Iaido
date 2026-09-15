# Task 5 report: structured split/join replacement reels

## Delivered

- Added the core `ReplacementOption` model with source words, replacement words, score, and a stable boundary-safe ID.
- Extended suggestion-strip state and the swipe coordinator to expose, preview, cancel, and release whole replacement groups without flattening their word boundaries.
- Added grouped Compose reels: a two-to-one choice is one wide reel; one-to-two and one-to-three choices are coordinated adjacent slots inside a horizontally scrollable `LazyRow`.
- Added RTL visual ordering, accessibility descriptions, and exact replacement-span cursor coverage while preserving ordinary correction chips.

## Verification

`./gradlew.bat :core-engine:test --tests com.iaido.core.recognition.SuggestionStripStateTest :app:testDebugUnitTest --tests com.iaido.app.SuggestionReelMathTest --tests com.iaido.app.ReplacementReelLayoutTest --tests com.iaido.app.SwipeTypingCoordinatorTest --rerun-tasks --no-daemon --console=plain`

Result: exit 0 (43.3 seconds).

`git diff --check`

Result: exit 0.

## Review follow-up

- Refreshed replacement options now retain the selected replacement when the refreshed candidate has the same stable ID, rebinding to the refreshed value so its latest score is retained.
- Added a deterministic regression that selects a split replacement, refreshes it with the same structured words and a new score, and verifies the refreshed candidate remains selected and displayed.
- The regression was observed failing before the state fix, then the complete focused Task 5 command passed with `--rerun-tasks` (exit 0, 44.4 seconds).
- Production replacement reels now retain a saveable selected stable ID and resolve it against refreshed candidates instead of always starting at index zero. A JVM state regression verifies the refreshed option remains at its selected index; the complete focused Task 5 command passed again with `--rerun-tasks` (exit 0, 54 seconds).

## Caveat

Connected IME drag/release/cancel journeys remain Task 6; Task 5 covers the structured state, coordinator behavior, and pure layout/accessibility contracts with JVM tests.
