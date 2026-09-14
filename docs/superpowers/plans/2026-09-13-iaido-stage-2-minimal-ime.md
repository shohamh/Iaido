# Iaido Stage 2: Minimal IME Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Install Iaido as a minimal Android IME that renders a hardcoded QWERTY keyboard, recognizes one-finger swipes with Stage 1, and commits the winning word.

**Architecture:** The Android app owns `IaidoInputMethodService` and a Compose input view. A pure Kotlin `SwipeCommitController` bridges the view's completed `GesturePath` to the existing `GestureRecognizer`, keeping commit behavior JVM-testable and leaving Android types at the app boundary.

**Tech Stack:** Kotlin 2.0.21, Gradle 8.9, AGP 8.7.2, Android API 36, minSdk 31, Jetpack Compose BOM 2024.11.00, JUnit 5 for pure Kotlin tests.

**Spec:** `docs/superpowers/specs/2026-09-13-iaido-stage-2-minimal-ime-design.md`

## Global Constraints

- `core-engine` remains pure Kotlin with zero Android dependencies.
- `app` remains the only Android-specific module.
- Capture raw `MotionEvent` points and use the actual rendered key centers in the same local coordinate space.
- Stage 2 includes only one-finger swipe recognition with `hi`, `no`, and `bye`; no taps, punctuation, suggestions, persistence, or multitouch.
- Missing `InputConnection`, empty recognition results, short paths, and cancelled gestures must be no-ops.
- Preserve unrelated untracked files: `.gradle-home/`, `.gradle-verify/`, and `bootstrap-gradle.java`.

---

### Task 1: Compose and IME project wiring

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/method.xml`

**Interfaces:** Produces the Compose dependencies and Android input-method registration required by later tasks.

- [ ] **Step 1: Add version-catalog Compose libraries**

Add aliases for `androidx.compose.ui:ui`, `androidx.compose.ui:ui-tooling-preview`, and `androidx.compose.material3:material3`, all using the existing Compose BOM; add `androidx.compose.ui:ui-tooling` as a debug dependency.

- [ ] **Step 2: Wire Compose compiler and dependencies in `app`**

Apply the Kotlin Compose compiler plugin using the existing Kotlin version and add the BOM plus UI, preview, Material 3, and core-ktx dependencies. Add `buildFeatures { compose = true }`.

- [ ] **Step 3: Register the IME service**

Declare `android.permission.BIND_INPUT_METHOD`, register `IaidoInputMethodService` as exported with the bind permission, and point service metadata at `@xml/method`.

- [ ] **Step 4: Add input-method metadata**

Create `method.xml` with an `input-method` root and a subtype-free `keyboard` declaration suitable for the English-only Stage 2 service.

- [ ] **Step 5: Verify configuration**

Run `./gradlew :app:assembleDebug --no-daemon`. If Android SDK configuration blocks it, retain the source changes and report that environment limitation.

- [ ] **Step 6: Commit**

```text
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/res/xml/method.xml
git commit -m "Wire Compose and register the Stage 2 input method"
```

### Task 2: Android-independent swipe commit controller

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/SwipeCommitController.kt`
- Create: `app/src/test/kotlin/com/iaido/app/SwipeCommitControllerTest.kt`

**Interfaces:**
- Consumes: `GestureRecognizer`, `GesturePath`, `KeyboardLayout`, and `WordEntry` from `core-engine`.
- Produces: `SwipeCommitController(recognizer, dictionary, commitText)` with `commit(path, layout)`.

- [ ] **Step 1: Write failing tests**

Test that a recognizer result commits the top word, an empty result does not call `commitText`, and a path with fewer than two points does not call it.

- [ ] **Step 2: Run the focused tests and confirm failure**

Run `./gradlew :app:testDebugUnitTest --tests "com.iaido.app.SwipeCommitControllerTest" --no-daemon`; expect compilation failure because the controller does not exist.

- [ ] **Step 3: Implement the controller**

Use `GestureRecognizer.recognize(path, layout, dictionary)`, return immediately for fewer than two points, and invoke `commitText(results.first().word.word)` only when results are non-empty.

- [ ] **Step 4: Run focused tests**

Run the same `:app:testDebugUnitTest` command and expect all controller tests to pass.

- [ ] **Step 5: Commit**

```text
git add app/src/main/kotlin/com/iaido/app/SwipeCommitController.kt app/src/test/kotlin/com/iaido/app/SwipeCommitControllerTest.kt
git commit -m "Add JVM-testable swipe commit controller"
```

### Task 3: Compose keyboard view and raw gesture capture

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt`
- Create: `app/src/main/kotlin/com/iaido/app/StageOneDictionary.kt`

**Interfaces:**
- Consumes: a `KeyboardLayout` callback and `onSwipe(GesturePath, KeyboardLayout)` callback.
- Produces: a Compose `@Composable fun KeyboardInputView(onSwipe: (GesturePath, KeyboardLayout) -> Unit)`.

- [ ] **Step 1: Define the embedded dictionary**

Expose an immutable list containing `WordEntry("hi", 1.0)`, `WordEntry("no", 1.0)`, and `WordEntry("bye", 1.0)`.

- [ ] **Step 2: Render the QWERTY rows**

Render `qwertyTestLayout()` letters in three horizontally arranged rows using Compose `Row`/`Box`, with stable key labels and measured local coordinates.

- [ ] **Step 3: Capture raw MotionEvents**

Use `pointerInteropFilter` on the keyboard container. On primary `ACTION_DOWN` start a point list; on primary `ACTION_MOVE` append points; on `ACTION_UP` emit a `GesturePath` and current layout when at least two points exist; on `ACTION_CANCEL` clear the list. Ignore secondary pointers.

- [ ] **Step 4: Keep coordinates consistent**

Build the `KeyboardLayout` from the same container dimensions and row/key geometry used for rendering, so touch points and key centers share local coordinates.

- [ ] **Step 5: Commit**

```text
git add app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt app/src/main/kotlin/com/iaido/app/StageOneDictionary.kt
git commit -m "Render Stage 2 QWERTY keyboard and capture swipe paths"
```

### Task 4: InputMethodService lifecycle and end-to-end app wiring

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`

**Interfaces:**
- Consumes: `KeyboardInputView`, `SwipeCommitController`, and `StageOneDictionary`.
- Produces: Android `InputMethodService` lifecycle integration and `InputConnection.commitText` behavior.

- [ ] **Step 1: Implement service creation**

Override `onCreateInputView()` to create a `ComposeView`, set its composition strategy, and set content to `KeyboardInputView`.

- [ ] **Step 2: Implement session lifecycle**

Construct a `GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer())`, create a controller whose commit lambda calls the current `currentInputConnection?.commitText(word, 1)`, and clear transient view state in `onStartInputView`/`onFinishInputView`.

- [ ] **Step 3: Connect completed swipes**

Pass each completed path/layout to the controller; do not commit when the service has no current input connection.

- [ ] **Step 4: Run verification**

Run `./gradlew :core-engine:test :app:assembleDebug build --no-daemon`. Record JVM test results and any Android SDK limitation separately.

- [ ] **Step 5: Review the final diff and commit**

```text
git diff --check
git status --short --branch
git add app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt
git commit -m "Add minimal swipe typing input method service"
```

## Completion Checklist

- Stage 2 source is committed without staging unrelated untracked files.
- `:core-engine:test` remains green.
- `SwipeCommitController` JVM tests are green if Android dependencies are available.
- `:app:assembleDebug` is green or explicitly blocked only by missing Android SDK.
- The final report distinguishes committed, local, pushed, and environment-blocked status.
