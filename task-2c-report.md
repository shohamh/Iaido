# Task 2C report

## 2026-09-20

- Added a split gesture generation and scheduled-chain guard in `IaidoInputMethodService`.
  A fresh split invalidates old callbacks, each generation schedules at most one poll chain, and
  session, language, and generation guards now protect both polling and async recognition delivery.
  Cancellation invalidates the generation, clears callbacks, cancels the controller, and finalizes
  the active inference transaction.
- Added `SwipeTypingCoordinator` behavior coverage for rejected out-of-order timestamps and a
  negative grace window. Both cases finalize the preceding inference transaction without accepting
  malformed multi-path input.
- Added a fixture-backed propagation test showing that the same two recognized paths commit `ba`
  when touch-downs are near and `ab` when they are wide apart, proving that supplied multi-path
  timing metadata reaches language interpretation.
- Verified with:
  `./gradlew :app:testDebugUnitTest --tests com.iaido.app.SwipeTypingCoordinatorTest --tests com.iaido.app.SplitTypingControllerTest --no-daemon --max-workers=2 --console=plain`
  (BUILD SUCCESSFUL; includes `:app:compileDebugKotlin`), and `git diff --check`.
- Preserved existing core commits and did not modify the pre-existing `.ci-art/` or docs plan
  worktree entries.
