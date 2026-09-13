---
id: 16
title: Automated testing strategy (unit, system, synthetic swipe generation)
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Design a testing strategy that's mostly automatic: unit tests, system/integration tests, and automated swipe-gesture tests that generate synthetic touch events to exercise the gesture-recognition pipeline end-to-end — run on both an emulator and the physical Galaxy S25.

## Resolution

**Unit tests (`core-engine`)**: standard JUnit/Kotlin-test, no Android dependency or device needed. Covers `CandidateGenerator` pruning correctness, `PathScorer` ranking against known paths, dictionary lookups, personal-learning boost math (growth/cap/decay), and n-gram context scoring. Runs on every build, on the dev machine alone.

**Synthetic swipe fixtures**: a test-fixture library in `core-engine` programmatically constructs path-point lists (straight-line interpolation through each word's key centers, matching the real recognizer's expected resampling/normalization input) for known word/path pairs — not real `MotionEvent`s, since `core-engine` has no Android dependency. Unit tests assert the recognizer returns the expected word for each fixture. These same path-point fixtures get converted into real `MotionEvent` sequences for the instrumented tests below, so there's one source of truth rather than duplicated fixtures per test layer.

**Instrumented/system tests (`app`)**: Espresso + Compose's `ComposeTestRule` for in-app/IME-input-view-level gesture tests (dispatching the converted `MotionEvent` sequences to exercise the real end-to-end pipeline on-device); UiAutomator specifically for cross-app cases — verifying committed text lands correctly in a *host* app's text field, since Espresso can't drive interaction across app boundaries the way IME testing sometimes needs.

**Emulator + physical device**: both are just ADB targets of Gradle's standard `connectedAndroidTest` task — no special infrastructure needed. An emulator (AVD) and the physical Galaxy S25 (via USB/wireless ADB) can be targeted together or individually (by device serial) with the same task.

**CI/automation**: a GitHub Actions workflow runs unit tests and emulator-based instrumented tests automatically on every push, for fast automatic feedback. Physical-device testing against the Galaxy S25 stays a manual local step (`./gradlew connectedAndroidTest` targeted at the phone's serial) — CI can't reach a personal physical device — run before considering a milestone genuinely done.

**Test data location**: the curated word+path fixtures (word, ideal key sequence, resulting path-point list) live in `core-engine`'s test sources as the single source of truth, reused by both unit tests and (converted to `MotionEvent`s) the `app` module's instrumented tests.
