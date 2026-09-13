---
id: 7
title: Research Android IME architecture patterns
type: research
status: closed
assignee: agent
blocked_by: []
---

## Question

Research current best practices for building an Android `InputMethodService`-based keyboard from scratch on API 31+: recommended module structure, lifecycle/state management, input-view rendering approaches (custom View vs Compose), settings-app integration, and any relevant Jetpack libraries. Use this to inform the project's tech stack and scaffolding decisions.

## Resolution

- **Core class**: extend `InputMethodService`, override `onCreateInputView()` to inflate the keyboard UI and `onStartInputView()`/`onFinishInputView()` for lifecycle. Communicate with the host app via the `InputConnection` given in `onStartInput` (commitText, setComposingText, deleteSurroundingText, etc.).
- **Rendering approach**: Jetpack Compose is now well-supported inside an IME via `ComposeView` set as the input view (Google's own `Showkase`/sample IMEs and community keyboards like FlorisBoard and Heliboard have moved to Compose). Compose gives easier theming and state management than custom View + Canvas, at a small perf cost that's negligible on modern hardware (Galaxy S25 target). Gesture-path capture itself should still use raw `MotionEvent` touch handling (via `pointerInteropFilter` or a custom `Modifier.pointerInput`), not Compose gestures, since a swipe trace needs every raw point.
- **Module structure pattern** used by comparable open-source keyboards (FlorisBoard, Heliboard/OpenBoard lineage): separate the *IME service/UI* module from a *core engine* module (dictionary + gesture/prediction logic, pure Kotlin, no Android framework dependency) so the engine is unit-testable off-device and swappable. A third *settings* module/activity handles preferences, using Android's own `PreferenceScreen`/Jetpack `DataStore` for persistence.
- **Settings-app integration**: a normal launcher `Activity` (can share the Compose UI toolkit) that the user opens to configure the keyboard; IME enabling/selection itself goes through system settings (`Settings.ACTION_INPUT_METHOD_SETTINGS`), which the app can deep-link to.
- **State/lifecycle**: keep per-session gesture state (current trace points, composing word) in a plain Kotlin class owned by the service, reset in `onStartInputView`; persistent state (learned dictionary, user prefs) via Jetpack `DataStore` or a local SQLite/Room database, since IME processes can be killed/recreated by the system at any time.
- **Relevant libraries**: Jetpack Compose + Compose-in-View interop, Jetpack `DataStore` (preferences/proto) for settings and small persistent state, Room (or plain SQLite) for the word/dictionary store if it grows large, `kotlinx.serialization` if dictionaries ship as JSON.
- Google's official "Creating an input method" guide and the FlorisBoard/Heliboard source were the primary references used for this summary.
