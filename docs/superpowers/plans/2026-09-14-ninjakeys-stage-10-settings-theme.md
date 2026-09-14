# NinjaKeys Stage 10: Theme and Settings UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the settings stub with the designed setup, gestures, typing, dictionary, help, and live-preview experience under the validated warm-neutral/teal light-dark theme.

**Architecture:** `NinjaKeysTheme` owns Material color/typography tokens and follows the system theme. `SettingsActivity` owns only screen navigation/actions and persists the two user tunables through DataStore; the preview is a normal text field that delegates keyboard behavior to Android’s IME picker. Dictionary actions remain on the existing Room repository.

**Tech Stack:** Kotlin, Android Compose Material 3, DataStore Preferences, Room.

**Spec:** `wayfinder/tickets/010-theming-baseline.md` and `wayfinder/tickets/015-settings-app-ux.md`.

## Global Constraints

- Light mode uses warm off-white paper and teal accent; dark mode uses near-black and teal accent.
- User tunables are only cascading correction depth and split-typing grace window.
- Setup deep-links to Android system IME settings instead of copying system screens.
- Every settings screen keeps a persistent preview text field.

---

### Task 1: Compose theme tokens

- [x] Add light/dark Material schemes and typography wrappers in `NinjaKeysTheme.kt`.
- [x] Add tests for distinct schemes and stable token values.
- [x] Apply the theme to the settings activity and keyboard input view.
- [x] Run theme tests and debug compilation.

### Task 2: Settings sections and persistence

- [x] Add DataStore keys/defaults for cascade depth (0..4, default 2) and grace window (300..400ms, default 350).
- [x] Build Setup, Gestures, Typing, Dictionary & Learning, and Help sections with live preview.
- [x] Add tests for tunable bounds/defaults and run the app unit suite.

### Task 3: Stage gate

- [x] Run core tests, app unit tests, debug assembly, and diff check.
- [x] Review against tickets 010/015, update roadmap/plan, and commit the stage.
