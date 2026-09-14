# NinjaKeys end-to-end IME testing design

## Goal

Create a repeatable emulator test system that uses NinjaKeys as a real Android
input method, injects user-like touch and mouse/pointer events, verifies text
in a separate host editor, exercises mistakes/deletions/edits/language and
keyboard switching, and preserves enough diagnostics to fix every surfaced
failure.

## Scope

Included:

- A debug-only host activity with an instrumentable editor and status surface.
- A debug-only reference input method used to make keyboard switching
  deterministic in CI and on local emulators.
- AndroidX instrumentation helpers that enable/select IMEs, locate the IME
  window, inject precise single- and multi-pointer `MotionEvent` sequences,
  and assert host text/cursor/language state.
- Scenario data and a Kotlin DSL for normal-user journeys involving taps,
  swipes, spaces, punctuation, mistakes, deletions, edits, suggestions,
  Hebrew/English switching, keyboard switching, lifecycle changes, and
  cancellation/recovery.
- Failure screenshots, logcat, input-method diagnostics, action traces, and
  CI artifact upload.
- A local PowerShell runner that builds, installs, configures, executes, and
  collects the emulator suite.

Excluded:

- Replacing unit tests for the pure `core-engine` algorithms.
- Making production builds expose the test host or reference IME.
- Treating a particular OEM keyboard, Gboard, or SwiftKey as a CI dependency.
- Claiming physical Galaxy S25 coverage in hosted CI; the same test APK may be
  run against an explicitly selected physical ADB target.

## Existing baseline

`core-engine` already publishes `SwipeFixtures` through Java test fixtures.
`app` already has a small `connectedDebugAndroidTest` smoke test and GitHub
Actions emulator job. The new system extends those seams rather than copying
word paths into Android tests. `NinjaKeysInputMethodService` remains the real
service under test, and the host editor receives text through the Android IME
connection.

## Architecture

### Debug host

`ImeTestHostActivity` is compiled only from `app/src/debug`. It renders one
multiline `EditText` with stable resource IDs, an explicit input type, and
accessible status labels. It provides deterministic clear/reset and cursor
placement controls for setup and assertions. The activity must not call
NinjaKeys internals or bypass `InputConnection`; all text mutations in E2E
tests come from injected user actions or Android editor key events.

### Reference keyboard

`ReferenceInputMethodService` is also debug-only and has a unique label and
accessibility marker. It commits a known token on a tap and exposes a visible
marker so tests can prove that the active IME changed. It exists solely to
provide a second keyboard on every CI emulator. Tests may additionally select
an installed system keyboard when a device profile declares one, but those
tests are optional and report the discovered IME package in their artifacts.

### System-window harness

Instrumentation uses `UiAutomation`/UiAutomator for cross-window control:

1. Launch and focus the debug host editor.
2. Enable and select NinjaKeys with the `ime` shell commands through
   `UiAutomation.executeShellCommand`, then poll the focused IME until the
   expected marker appears.
3. Discover the keyboard root bounds and stable key markers through the
   accessibility tree. The harness never assumes a fixed 1080p coordinate or
   a fixed navigation-bar height.
4. Convert a shared `KeyboardLayout` path into absolute display coordinates.
5. Inject `MotionEvent.obtain` down/move/up sequences through
   `UiAutomation.injectInputEvent(..., true)` with monotonic timestamps.
6. Wait for the host editor text/cursor state to settle, then assert the
   scenario expectation.

Single-pointer injection covers normal swipe/tap/flick actions. A reusable
`MultiPointerInjector` emits `ACTION_POINTER_DOWN`, move, pointer-up, and
cancel sequences for split typing and command gestures. `UiDevice.swipe` is
reserved for coarse smoke probes; the main tests use explicit events so timing,
jitter, pointer IDs, and cancellation are reproducible.

The keyboard view gets test-only accessibility markers for its root, language,
and control keys. The injection layer depends on those markers and geometry,
not on Compose implementation details or screenshot pixel matching.

## Scenario model

The test DSL records every action and expected state:

```kotlin
scenario("English edit recovery") {
    focusEditor()
    clearText()
    swipeWord("there")
    tapSpace()
    swipeWord("world")
    pressBackspace()
    tapKey("w")
    assertText("There w")
}
```

Each action has a timeout, a bounded retry/poll policy, and an event trace
entry. The scenario runner exposes `assertText`, `assertSelection`,
`assertActiveIme`, `assertLanguage`, `assertKeyboardVisible`, and
`assertSuggestion`. It fails on unexpected extra text, stale cursor position,
wrong IME, or wrong language even if the final sentence happens to match.

Word paths come from `SwipeFixtures` and dictionary-backed fixture validation.
Scenario data selects words that are present in the active dictionary and
records the intended result separately from intentionally wrong paths. Every
mistake case declares both the immediate expected text and the later expected
correction, so delayed flow correction cannot hide an input loss.

## Required scenario suites

### Input and editing

- English taps, swipe words, spaces, punctuation, double-space period, and
  sentence capitalization.
- Multi-word sentences with left-edge, right-edge, diagonal, repeated-letter,
  short, and long paths.
- Wrong/near-word paths followed by suggestion-chip selection and flow
  correction.
- Backspace at the end, repeated backspace, deleting a space, deleting a word,
  backspace on empty text, delete-and-retype, and manual replacement.
- Cursor movement and replacement in the middle of a sentence.

### Bilingual and RTL

- Hebrew-only sentence entry and deletion.
- Globe-key and two-finger language switching.
- English-to-Hebrew and Hebrew-to-English transitions with existing text.
- RTL suggestion ordering, cursor assertions, punctuation, and keyboard
  relayout after switching.

### Keyboard/system behavior

- NinjaKeys → reference keyboard → NinjaKeys while the editor is focused.
- Switching after text, deletion, language change, and keyboard hide/show.
- Visible system input-method picker when the emulator exposes a stable
  selector; shell selection remains the deterministic setup path.
- Input view recreation, activity background/foreground, editor refocus,
  cancellation, rapid repeated gestures, and configuration/lifecycle restart.

### Geometry and responsiveness

- Keyboard root and navigation-inset bounds are recorded on every run.
- Assert the keyboard and control keys remain inside the IME window and do not
  overlap the bottom system switch/navigation affordance.
- Capture a screenshot while a swipe is active and assert the trail is present
  through a stable semantic marker or a narrowly scoped pixel-region check.
- Run a small timing matrix: slow, normal, fast, jittered, and paused swipes.

## Diagnostics and artifacts

`FailureArtifactRule` runs on every failing test and saves:

- Full display screenshot and cropped IME screenshot.
- Host accessibility tree and IME accessibility tree.
- `logcat -d` filtered for NinjaKeys, InputMethodManager, Compose, and fatal
  runtime messages.
- `dumpsys input_method`, focused-window information, and package/ABI/API data.
- JSONL action/event trace with coordinates, pointer IDs, timestamps, expected
  state, and observed state.
- Final host text, selection, active IME ID, and detected language marker.

The local runner pulls artifacts into
`artifacts/ime-e2e/<timestamp>/<test-class>/<test-name>/`. GitHub Actions
uploads that directory with `if: always()`, including test reports on success
and failure. The harness redacts no user data because the host text is fully
synthetic and must never include personal-device clipboard contents.

## Local and CI execution

Local usage is one command after an AVD is available:

```powershell
.\tools\run_ime_e2e.ps1 -AvdName NinjaKeysApi35
```

The script builds the debug APK/test APK, installs them, resets the selected
IME, runs `connectedDebugAndroidTest` for the selected serial, and pulls
artifacts even when tests fail. `-DeviceSerial` targets a physical device or a
specific emulator. CI keeps the existing unit/build job and adds the full
scenario suite to the emulator job with fixed API/profile/ABI settings.

The first setup failure is classified separately from product failures:
missing SDK/AVD, inability to enable/select an IME, inability to locate the IME
window, event-injection permission failure, and assertion failure each get a
distinct diagnostic message.

## Determinism and acceptance criteria

- No test calls production callbacks directly to simulate user input.
- Every E2E gesture reaches the real Compose keyboard view as Android input.
- Every committed character is verified in a separate host editor.
- The reference keyboard makes keyboard-switch tests runnable on a clean CI
  emulator.
- Scenarios are seedable; jitter and timing use a recorded seed.
- A failing test leaves enough evidence to reproduce the exact event stream.
- Unit tests cover the pure DSL/state/coordinate/event builders; connected
  tests cover actual IME windows and `InputConnection` behavior.
- Production release manifests contain neither debug host nor reference IME.
- The suite passes repeatedly on the pinned emulator profile before claiming
  the corresponding keyboard behavior fixed.

## Rollout order

1. Debug host, reference IME, IME selection/reset, and one tap/swipe smoke
   journey.
2. Coordinate discovery and explicit pointer injection, including cancellation.
3. Sentence, mistake, deletion, edit, and suggestion suites.
4. Hebrew/RTL and language-switch suites.
5. Keyboard-switch, lifecycle, geometry, and responsiveness suites.
6. Artifact collector, local runner, CI matrix, and repeated stability runs.
