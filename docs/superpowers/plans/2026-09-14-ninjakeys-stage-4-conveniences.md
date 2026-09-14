# NinjaKeys Stage 4: Flick Punctuation and IME Conveniences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the English keyboard usable for ordinary tap typing alongside swipe typing, with number flicks, punctuation-to-space, capitalization, double-space periods, accents, and basic editing.

**Architecture:** Pure Kotlin `TypingController` owns classification and text policy; Android supplies commit/delete callbacks and cursor context. The Compose view renders the full English surface and reports taps and completed pointer gestures through a small event model.

**Tech Stack:** Kotlin, Compose, Android `InputConnection`, JUnit 5.

**Spec:** `wayfinder/tickets/006-flick-punctuation-design.md` and `wayfinder/tickets/014-standard-ime-conveniences.md`.

## Global Constraints

- Preserve one-finger swipe recognition and the Stage 3 dictionary.
- Letter keys remain the normal QWERTY layout; number access is swipe-up only.
- Tapping punctuation inserts only punctuation; punctuation-to-space inserts punctuation plus one trailing space.
- Double-space period and swipe-to-space coexist.
- Long-press accents are limited to English letter variants.

---

### Task 1: Pure typing event policy

**Files:** Create `app/src/main/kotlin/com/ninjakeys/app/TypingController.kt` and its JVM tests.

- [x] Write failing tests for tap insertion, number flick, punctuation-to-space, capitalization after sentence boundaries, double-space period, backspace, and accented long-press resolution.
- [x] Implement minimal event classification and callback-based text editing.
- [x] Run focused tests and the existing app/core suites.
- [x] Commit the controller.

### Task 2: Full English keyboard surface and pointer routing

**Files:** Modify `KeyboardInputView.kt` and `NinjaKeysInputMethodService.kt`.

- [x] Add punctuation, space, backspace, and visible corner number labels while retaining measured key centers.
- [x] Route one-point touches to tap events, upward letter gestures to number events, punctuation-to-space paths to punctuation-space events, and other multi-point paths to the recognizer.
- [x] Wire controller callbacks to the active `InputConnection`, including delete-surrounding-text.
- [x] Verify debug assembly and JVM tests.
- [x] Commit the integrated Stage 4 behavior.

### Task 3: Stage gate

- [x] Run `git diff --check`, all JVM tests, and `:app:assembleDebug`.
- [x] Record any emulator-only behavior that remains unverified in `docs/superpowers/dilemmas.md`.
- [x] Commit the stage plan/status evidence.

