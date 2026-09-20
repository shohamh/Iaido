# Task 1 Report: Freeze the contract and establish test seams

Status: complete; no further build was started after the user requested an immediate report.

## Scope and changes

- The approved contract is recorded in `docs/superpowers/specs/2026-09-20-reel-strip-behavior.md`.
  Its header is `Status: Approved for implementation`, and it records `N = 3`, one-to-four
  paths, pairwise one-swap enumeration, the continuous timing formula, and the whitespace focus
  rule. Behavioral tables were left unchanged.
- Preserved and completed the scoped test-only additions in
  `app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt`:
  - `ReelStripEntry` and `ReelStripSnapshot` capture ordered reel IDs, candidate text,
    state descriptions, and bounds from the real suggestion-strip accessibility tree.
  - Reel nodes without candidate text fail explicitly.
  - `awaitImeState` polls editor text/cursor and ordered reel/candidate state, captures the named
    settled screenshot, and includes the last observed state plus screenshot path on timeout.
  - Semantic parsing uses the published node tree and bounds, not guessed coordinates.
- The unrelated untracked `.ci-art/` directory and the task plan were not staged.

## Verification

1. `git diff --check`
   - Passed before the baseline runs.
2. `./gradlew.bat --no-daemon --max-workers=2 :core-engine:test :app:testDebugUnitTest --console=plain`
   - `BUILD SUCCESSFUL in 9s`; both requested unit tasks completed with exit code 0.
3. Initial connected command without quoting the dotted Gradle property:
   `./gradlew.bat --no-daemon --max-workers=2 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.iaido.app.SuggestionStripVisibilityTest --console=plain`
   - Failed before test execution because Gradle parsed the property suffix as task
     `.testInstrumentationRunnerArguments.class=...`.
4. Corrected connected baseline:
   `./gradlew.bat --no-daemon --max-workers=2 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.iaido.app.SuggestionStripVisibilityTest' --console=plain`
   - `BUILD SUCCESSFUL in 1m 12s`; emulator `IaidoApi35Work(AVD) - 15`, device serial
     `emulator-5558`; `SuggestionStripVisibilityTest` ran 4 tests with 0 failures, 0 errors,
     and 0 skipped. The result exit-code file contained `0`.

The connected build reported instrumented Kotlin compilation as up-to-date. No additional long
build or connected suite was started after the interruption.

## Review fix: replacement-reel candidate extraction

- Addressed the P1 review finding in `ImeScenario.reelStripSnapshot`: `candidateText` now comes
  only from the reel node's displayed text/descendants. The accessibility `stateDescription` is
  retained separately on `ReelStripEntry` and is never considered candidate text.
- Root cause: replacement reels publish the instructional state description
  `Swipe vertically to preview; release to commit`, while their actual candidate words are rendered
  as descendant text. The former state-description-first extraction could therefore falsely satisfy
  a candidate assertion with that instruction.
- A focused behavioral test is not practical at this helper seam yet: the Task 1 helper is not
  exercised by the existing E2E tests, and replacement nodes are known to be absent from the
  accessibility tree until directly touched. The targeted compile check covers the changed helper.

### Review-fix verification

1. `./gradlew.bat --no-daemon --max-workers=2 :app:compileDebugAndroidTestKotlin --console=plain`
   - Passed with exit code 0.
2. `git diff --check`
   - Passed after the scoped change.

## Self-review and concerns

- The scoped diff contains only the Task 1 helper, approved spec artifact, and this report.
- No production source, `.ci-art/`, or unrelated task-plan content was changed or staged.
- The helper intentionally fails on a reel node whose current candidate text is not published;
  replacement-node semantics that omit candidate text remain an explicit concern for later tasks,
  rather than being hidden by coordinate fallbacks.
- Connected baseline evidence covers the four existing visibility tests only; it does not claim
  the broader reel contract or new E2E matrix is implemented.
