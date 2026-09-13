# NinjaKeys Stage 2: Minimal IME Design

## Goal

Make NinjaKeys installable as a minimal Android input method that renders a
hardcoded English QWERTY keyboard, captures one-finger swipe traces, resolves
the trace with the Stage 1 core engine, and commits the winning word to the
focused text field.

## Scope

Included:

- An `InputMethodService` registered with Android's input-method framework.
- A Compose input view with three hardcoded QWERTY rows.
- Raw `MotionEvent` capture for one active pointer.
- A small in-app dictionary containing `hi`, `no`, and `bye`.
- Conversion of rendered key centers and touch coordinates into the
  `KeyboardLayout`/`GesturePath` types expected by `core-engine`.
- Commit of the top recognition result through the current `InputConnection`.
- JVM tests for the Android-independent session/controller boundary.

Excluded until later stages: taps, punctuation, suggestions, autocorrection,
real dictionary loading, persistence, language switching, multitouch, and
settings UI.

## Architecture

`NinjaKeysInputMethodService` owns the current input session. It resets the
session when `onStartInputView` begins and clears it in `onFinishInputView`.
`onCreateInputView` creates a Compose-backed `KeyboardInputView` and supplies a
callback for completed swipes. The service callback invokes a small
`SwipeCommitController`, which composes the Stage 1 `GestureRecognizer` with
the embedded dictionary and commits only the first ranked candidate.

The Compose view is responsible for presentation and raw touch capture only.
It calculates actual key-center coordinates from its measured width and row
geometry, so the path and layout share the same local coordinate space. The
view sends a completed `GesturePath` to the controller on `ACTION_UP`; traces
with fewer than two points are ignored.

## Interfaces

```kotlin
fun interface SwipeCommitTarget {
    fun commit(path: GesturePath, layout: KeyboardLayout)
}

class SwipeCommitController(
    private val recognizer: GestureRecognizer,
    private val dictionary: List<WordEntry>,
    private val commitText: (String) -> Unit,
) {
    fun commit(path: GesturePath, layout: KeyboardLayout)
}
```

`SwipeCommitController.commit` calls `recognizer.recognize`, takes the first
result when present, and invokes `commitText` with its word. It does nothing
for an empty result or a path shorter than two points. The controller has no
Android dependencies and is tested through this public method.

## Android integration

The manifest declares `android.permission.BIND_INPUT_METHOD` and an
`android.view.InputMethod` service with `android:exported="true"`,
`android:permission="android.permission.BIND_INPUT_METHOD"`, and metadata
pointing to `res/xml/method.xml`. The service label is NinjaKeys.

Compose dependencies are added through the existing version catalog and BOM.
The app keeps `minSdk = 31`, Java/Kotlin target 17, and the existing
`core-engine` dependency.

## Error and lifecycle behavior

- No `InputConnection` means no commit; the service must not crash.
- No recognized candidates means no text mutation.
- A new input view discards any previous in-progress path.
- Only the active primary pointer is captured; secondary pointers are ignored
  in this stage.
- `ACTION_CANCEL` discards the current path.

## Verification

- JVM tests cover controller winner commit, empty-result no-op, and short-path
  no-op.
- `:core-engine:test` must remain green.
- `:app:assembleDebug` is attempted; if the host has no Android SDK, that
  limitation is reported separately from source/test results.
- Manual emulator verification, when an SDK is available: enable NinjaKeys in
  system keyboard settings, focus a text field, swipe `h` to `i`, and observe
  `hi` committed to the field.
