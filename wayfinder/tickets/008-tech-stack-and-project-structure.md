---
id: 8
title: Tech stack & project structure
type: grilling
status: closed
assignee: agent
blocked_by: [7]
---

## Question

Decide the concrete tech stack and project structure: language/build tooling (Kotlin + Gradle version), UI rendering approach for the keyboard view (custom View vs Jetpack Compose), module layout (e.g. separate modules for gesture engine, dictionary/prediction, IME service, settings UI), minimum tooling (Android Studio version, target/compile SDK), and testing setup (unit tests, instrumented tests, emulator config).

## Resolution

- **Language**: Kotlin, current stable version.
- **UI rendering**: Jetpack Compose for the IME input view and settings UI; raw `MotionEvent`/`Modifier.pointerInput` handling beneath Compose for capturing the gesture trace (Compose's own gesture APIs don't expose raw per-point touch data needed for path matching).
- **Module structure**: two modules —
  - `core-engine` — pure Kotlin, no Android framework dependency, unit-testable off-device. Houses the pluggable gesture-recognition interface + initial algorithm, dictionary access, and prediction/learning logic.
  - `app` — the `InputMethodService`, Compose UI (keyboard input view + settings screens), and Room/DataStore persistence. Single app module for now; splitting `ime`/`settings` further is premature for a solo v1.
- **Persistence**: Jetpack DataStore for settings/preferences; Room (SQLite) for the dictionary and on-device learned-word data (indexed lookups matter for real-time gesture matching at dictionary scale).
- **Build tooling**: pin to current stable Kotlin, current stable AGP/Gradle, `compileSdk`/`targetSdk` = current latest Android API, `minSdk` = 31 (Android 12, per the map's Notes). No reason to target older tooling on a greenfield project.
- **Testing**: unit tests (JUnit) for `core-engine` logic (gesture matching, scoring, dictionary), instrumented tests for the `app` module where IME/Compose behavior needs on-device verification; manual testing on the Galaxy S25 plus an Android 12+ emulator for anything instrumented tests don't cover well (real touch feel, IME switching UX).
