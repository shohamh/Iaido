# Iaido IME End-to-End Testing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Build a deterministic emulator harness that drives the real Iaido input method with Android pointer events, verifies text in a separate host editor, exercises bilingual editing and keyboard switching, and collects actionable failure artifacts.

**Architecture:** Add debug-only host and reference-IME components to the app, while instrumentation owns system-window setup, event injection, scenario state, and diagnostics. Shared word paths remain in core-engine test fixtures; a Kotlin scenario DSL converts them into screen-space MotionEvents and checks the host editor through UiAutomator. PowerShell and CI standardize emulator setup and artifact collection.

**Tech Stack:** Kotlin/JUnit4 Android instrumentation, AndroidX Test Runner, UiAutomator/UiAutomation, Compose semantics, debug source sets, Gradle, PowerShell, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-09-14-iaido-ime-e2e-testing-design.md

## Global Constraints

- No E2E test may call a production keyboard callback directly to simulate input.
- Every primary gesture must reach the real KeyboardInputView as injected Android input.
- Every committed character must be asserted in the separate debug host editor.
- Reference keyboard and host activity are debug-only and absent from release manifests.
- Shared swipe paths come from core-engine test fixtures.
- Tests use display/window bounds and semantic markers, never fixed 1080p coordinates.
- Tests record a deterministic seed, actions, coordinates, pointer IDs, and timestamps.
- Failures preserve screenshots, accessibility trees, filtered logcat, IME dumps, and state.
- Physical-device execution is explicit local ADB targeting; hosted CI uses a pinned emulator.
- Preserve unrelated WIP and stage only files belonging to this subsystem.

---

### Task 1: Debug-only host and reference IME

**Files:**
- Modify: app/build.gradle.kts
- Create: app/src/debug/AndroidManifest.xml
- Create: app/src/debug/kotlin/com/iaido/app/ImeTestHostActivity.kt
- Create: app/src/debug/kotlin/com/iaido/app/ReferenceInputMethodService.kt
- Create: app/src/debug/res/layout/ime_test_host.xml
- Create: app/src/debug/res/xml/reference_input_method.xml

**Interfaces:**
- Host IDs: ime_test_editor, ime_test_status, ime_test_clear, ime_test_move_cursor_left.
- Host editor is multiline with inputType textCapSentences|textMultiLine.
- Reference service has content description Iaido reference keyboard and a marked commit key that commits reference.

- [ ] Step 1: Add AndroidX test dependencies.

Add androidx.test:rules and androidx.test.uiautomator:uiautomator under androidTestImplementation, using repository-compatible versions. Do not add test libraries to production runtime dependencies.

- [ ] Step 2: Implement the host.

Render the editor, status, clear, and cursor-left controls. Update status with text length and selection. Clear is setup-only; tested text mutations must come from the IME or Android editor key events.

- [ ] Step 3: Implement the reference IME.

Declare a debug-only InputMethodService with BIND_INPUT_METHOD metadata. Its marked key calls currentInputConnection?.commitText("reference", 1); its root marker proves active-IME changes.

- [ ] Step 4: Verify debug/release isolation.

Run:
~~~
.\gradlew.bat :app:assembleDebug :app:assembleRelease
~~~
Inspect merged manifests. Debug contains the host/reference components; release contains neither.

- [ ] Step 5: Commit.
~~~
git add app/build.gradle.kts app/src/debug
git commit -m "Add debug IME test surfaces"
~~~

### Task 2: Semantic markers and IME-window geometry

**Files:**
- Modify: app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/KeyboardWindowLocator.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/KeyboardWindowLocatorTest.kt

**Interfaces:**
- Root description: Iaido keyboard root.
- Language descriptions: Iaido language ENGLISH and Iaido language HEBREW.
- Key descriptions: Iaido key <value>, including space, backspace, and globe.
- KeyboardWindowLocator.locate(device: UiDevice): KeyboardWindow returns root Rect, language, and key bounds.

- [ ] Step 1: Add markers without behavior changes.

Mark the root, swipe surface, language, and control keys while preserving visible labels and callbacks.

- [ ] Step 2: Implement locator polling and validation.

Wait for the root marker, read bounds, locate required keys, and validate positive bounds inside the root and display. Missing markers throw an error containing the accessibility tree.

- [ ] Step 3: Add geometry tests.

Test root-relative key centers, navigation-inset handling, missing-marker diagnostics, and bounds that would otherwise inject below the keyboard surface.

- [ ] Step 4: Verify and commit.
~~~
.\gradlew.bat :app:testDebugUnitTest
git add app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt app/src/androidTest/kotlin/com/iaido/app/KeyboardWindowLocator.kt app/src/androidTest/kotlin/com/iaido/app/KeyboardWindowLocatorTest.kt
git commit -m "Expose stable IME test geometry markers"
~~~

### Task 3: Real pointer and editor drivers

**Files:**
- Create: app/src/androidTest/kotlin/com/iaido/app/PointerInjector.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeEditorDriver.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/PointerInjectorTest.kt

**Interfaces:**
- PointerInjector.injectSwipe(points, startTimeMs, stepMs, jitterSeed, cancel).
- PointerInjector.injectMultiPointer(paths, startTimeMs).
- ImeSystemController.enableAndSelect(imeId).
- ImeEditorDriver.focus(), clear(), text(), selection(), pressBackspace(), pressKey(), tapMarkedKey(), waitForText().

- [ ] Step 1: Write pointer-builder tests first.

Assert valid down/move/up and cancel sequences, monotonic times, two-pointer IDs/action indices, and deterministic seeded jitter.

- [ ] Step 2: Implement UiAutomation injection.

Construct PointerProperties and PointerCoords explicitly, transform fixture-local points using discovered bounds, inject synchronously, and recycle events.

- [ ] Step 3: Implement IME selection/reset.

Run ime enable and ime set through UiAutomation, poll secure default_input_method and the Iaido marker, and include focused-window plus dumpsys input_method output on setup failure.

- [ ] Step 4: Implement editor operations.

Use UiAutomator resource IDs and UiDevice.pressKeyCode for editor navigation/backspace. Do not access the host Activity directly from E2E tests.

- [ ] Step 5: Verify and commit.
~~~
.\gradlew.bat :app:testDebugUnitTest
git add app/src/androidTest/kotlin/com/iaido/app/PointerInjector.kt app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt app/src/androidTest/kotlin/com/iaido/app/ImeEditorDriver.kt app/src/androidTest/kotlin/com/iaido/app/PointerInjectorTest.kt
git commit -m "Add real IME pointer and editor drivers"
~~~

### Task 4: Scenario DSL and first English journey

**Files:**
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeScenarioData.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeEnglishE2eTest.kt
- Modify: core-engine/src/testFixtures/kotlin/com/iaido/core/testing/SwipeFixtures.kt

**Interfaces:**
- DSL methods: focusEditor(), clearText(), swipeWord(word), tapKey(description), tapSpace(), pressBackspace(count), switchLanguage(), switchKeyboard(imeId), assertText(expected), run().
- ImeScenario records action name, expected text, selection, IME, language, and observed state.
- ImeScenarioData.englishSmoke contains only dictionary-backed words and explicit expected text.

- [ ] Step 1: Validate fixture words.

Reject empty/non-letter words and expose exact layout paths from shared fixtures.

- [ ] Step 2: Implement the state machine.

After each operation, poll stable state, assert declared expectations, and append an event record. Timeout errors include action index/name and expected versus observed text, cursor, IME, and language.

- [ ] Step 3: Implement setup/teardown.

Launch the host, select Iaido, focus/clear the editor, reset to English, hide the keyboard at teardown, and restore the pre-test IME only when the test changed it.

- [ ] Step 4: Add the first real sentence journey.

Swipe at least three English words with actual space taps, assert text after every word, and include one punctuation action.

- [ ] Step 5: Run and commit.
~~~
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
git add app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenarioData.kt app/src/androidTest/kotlin/com/iaido/app/ImeEnglishE2eTest.kt core-engine/src/testFixtures/kotlin/com/iaido/core/testing/SwipeFixtures.kt
git commit -m "Add first real IME sentence journey"
~~~

### Task 5: Mistakes, deletion, editing, cancellation, and multi-pointer

**Files:**
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeEditingE2eTest.kt
- Modify: app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt
- Modify: app/src/androidTest/kotlin/com/iaido/app/PointerInjector.kt
- Extend: app/src/androidTest/kotlin/com/iaido/app/ImeScenarioData.kt

**Interfaces:**
- swipePath(word, transform), waitForCorrection(expected), tapSuggestion(index), injectCancelledSwipe(word), injectSplitWords(words), and longPressKey(description).
- PathTransform includes seeded jitter, pause, reverse segment, and cancel-after-point.

- [ ] Step 1: Add deterministic mistake paths.

Use overlapping dictionary words. Assert immediate candidate, visible alternatives, and intended result after suggestion selection. Record the jitter seed.

- [ ] Step 2: Add deletion/retype journeys.

Cover end backspace, repeated backspace, deleting a space, deleting a word, empty-buffer backspace, and delete-and-retype with text/selection assertions after each action.

- [ ] Step 3: Add middle editing.

Use the host cursor-left control and DPAD events to replace text in the middle of a sentence and assert the final cursor and manual-edit state where exposed.

- [ ] Step 4: Add cancellation and split gestures.

Assert cancelled swipes do not mutate text. Inject split paths with distinct pointer IDs and assert merged result/preview. Include one non-destructive command gesture.

- [ ] Step 5: Run and commit.
~~~
.\gradlew.bat :app:connectedDebugAndroidTest --tests '*ImeEditingE2eTest' --no-daemon --console=plain
git add app/src/androidTest/kotlin/com/iaido/app/ImeEditingE2eTest.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt app/src/androidTest/kotlin/com/iaido/app/PointerInjector.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenarioData.kt
git commit -m "Cover IME mistakes deletion editing and cancellation"
~~~

### Task 6: Hebrew/RTL and keyboard switching

**Files:**
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeBilingualE2eTest.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeKeyboardSwitchE2eTest.kt
- Modify: app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt
- Modify: app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt

**Interfaces:**
- assertLanguage(language), switchToReferenceKeyboard(), switchBackToIaido(), and optional trySystemKeyboardSwitch().
- Optional system-picker probing reports a structured skip only when the profile lacks a stable accessibility selector; deterministic reference-keyboard coverage always runs.

- [ ] Step 1: Add Hebrew-only journeys.

Swipe Hebrew dictionary words, tap spaces/punctuation, delete from the RTL end, and assert text, selection, language marker, and rediscovered layout bounds.

- [ ] Step 2: Add mid-sentence switching.

Enter English, globe-switch to Hebrew, enter Hebrew, switch back, and continue English. Repeat with the two-finger horizontal gesture; assert prior text is preserved.

- [ ] Step 3: Add deterministic keyboard switching.

Select the reference IME, assert its marker, commit its token, select Iaido, and continue the sentence. Repeat after deletion and language change.

- [ ] Step 4: Add optional visible picker probing.

Discover stable switcher descriptions, record the available IME list, and execute the UI picker only on supported profiles; CI must not depend on OEM wording.

- [ ] Step 5: Run and commit.
~~~
.\gradlew.bat :app:connectedDebugAndroidTest --tests '*ImeBilingualE2eTest' --tests '*ImeKeyboardSwitchE2eTest' --no-daemon --console=plain
git add app/src/androidTest/kotlin/com/iaido/app/ImeBilingualE2eTest.kt app/src/androidTest/kotlin/com/iaido/app/ImeKeyboardSwitchE2eTest.kt app/src/androidTest/kotlin/com/iaido/app/ImeSystemController.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt
git commit -m "Test bilingual input and keyboard switching"
~~~

### Task 7: Lifecycle, geometry, responsiveness, and artifacts

**Files:**
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeLifecycleE2eTest.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ImeGeometryE2eTest.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/FailureArtifactRule.kt
- Create: app/src/androidTest/kotlin/com/iaido/app/ArtifactWriter.kt
- Modify: app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt

**Interfaces:**
- FailureArtifactRule is a JUnit4 TestWatcher calling ArtifactWriter.capture(testName, device, scenarioState).
- ArtifactWriter writes screenshot, IME screenshot, windows XML, IME dump, logcat, event-trace JSONL, and state JSON.
- ImeScenario exposes an immutable trace to the rule.

- [ ] Step 1: Add lifecycle tests.

Cover host restart, keyboard hide/show, focus transfer, background/foreground, and input-view recreation. Re-locate the root and assert text continuity after each transition.

- [ ] Step 2: Add geometry tests.

Assert root/key bounds are positive, inside display, above navigation inset, and separate from the system bottom area. Capture an active-trail screenshot during a swipe and assert the trail diagnostic signal.

- [ ] Step 3: Add timing matrix.

Run slow, normal, fast, jittered, and paused versions of one smoke word. Record gesture and settle durations; functional timeouts remain explicit.

- [ ] Step 4: Implement failure capture.

Collect screenshots, accessibility XML, dumpsys input_method, focused-window output, filtered logcat -d, package/API metadata, and expected/observed state. Use synthetic text only.

- [ ] Step 5: Run and commit.
~~~
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
git add app/src/androidTest/kotlin/com/iaido/app/ImeLifecycleE2eTest.kt app/src/androidTest/kotlin/com/iaido/app/ImeGeometryE2eTest.kt app/src/androidTest/kotlin/com/iaido/app/FailureArtifactRule.kt app/src/androidTest/kotlin/com/iaido/app/ArtifactWriter.kt app/src/androidTest/kotlin/com/iaido/app/ImeScenario.kt
git commit -m "Capture IME lifecycle geometry and failure diagnostics"
~~~

### Task 8: Local runner, CI artifacts, and release gates

**Files:**
- Create: tools/run_ime_e2e.ps1
- Create: tools/collect_ime_e2e_artifacts.ps1
- Modify: .github/workflows/android.yml
- Modify: app/src/androidTest/kotlin/com/iaido/app/ArtifactWriter.kt
- Modify: docs/superpowers/specs/2026-09-14-iaido-ime-e2e-testing-design.md only when the approved contract materially changes

**Interfaces:**
- run_ime_e2e.ps1 -AvdName <name> [-DeviceSerial <serial>] [-Class <filter>] [-KeepArtifacts].
- collect_ime_e2e_artifacts.ps1 -DeviceSerial <serial> -Destination <path>.
- CI uploads artifacts/ime-e2e and connected-test reports with if: always().

- [ ] Step 1: Implement the local runner.

Resolve root from PSScriptRoot, require adb, accept one serial when multiple devices exist, build/install debug and test APKs, reset/select Iaido, run the filtered connected test, collect artifacts, and return the Gradle exit code.

- [ ] Step 2: Implement safe artifact collection.

Use explicit device paths under the test app external-files directory and local artifacts/ime-e2e. Preserve failures by default; only remove an explicit run directory when requested.

- [ ] Step 3: Extend CI.

Keep the existing unit/build job. Pin API 35, google_apis, x86_64, and Pixel_2 for the emulator job, run the deterministic suite, collect reports on success/failure, and upload them.

- [ ] Step 4: Document local/physical execution.

Document:
~~~
.\tools\run_ime_e2e.ps1 -AvdName IaidoApi35
.\tools\run_ime_e2e.ps1 -DeviceSerial <adb-serial>
~~~
State that physical results apply only to the selected device and hosted CI cannot reach the Galaxy S25.

- [ ] Step 5: Run the complete gate.

~~~
python -m unittest discover -s tools -p "test_*.py" -v
.\gradlew.bat :core-engine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --console=plain
~~~
Verify release manifest isolation, emulator artifacts, one successful trace, and one forced-failure artifact.

- [ ] Step 6: Final review.

Run git diff --check, inspect full status, and rerun the complete gate. Report unavailable physical/emulator checks separately from source/test results. Do not merge or push without explicit integration approval.

