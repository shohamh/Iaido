# Task 6 report: automatic spacing in real IME journeys

## Scope

- Added connected-test coverage for manual spacing, space-after-swipe, and
  inferred spacing.
- Added real Settings-screen persistence/default checks.
- Added deterministic debug dictionary/ngram fixtures for split, join,
  reanalysis, two-finger, bounded-window, and low-confidence journeys.
- Added connected drag preview/cancel/release assertions for replacement reels.

## Verification

Bounded compile/assemble check:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --no-daemon --console=plain
```

Result: exit 0; `BUILD SUCCESSFUL in 8s`.

`git diff --check`

Result: exit 0.

## Connected E2E status

Connected emulator execution is unverified and setup-blocked for this patch.
The available Pixel_10_Pro run was started previously but did not return, with
emulator/ADB and Gradle processes remaining. Per the bounded-check request, no
further connected execution was attempted. The tests are compiled into the
debug Android-test artifact, but their runtime behavior is not claimed here.

## Commit

`test: cover automatic spacing in real IME journeys`
