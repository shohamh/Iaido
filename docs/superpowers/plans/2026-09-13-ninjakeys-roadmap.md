# NinjaKeys Implementation Roadmap

> **For agentic workers:** This is a stage index, not an executable plan. Each stage gets its own detailed, bite-sized task plan (following the `superpowers:writing-plans` format) written just before that stage starts. Only [Stage 1](2026-09-13-ninjakeys-stage-1-core-engine-bootstrap.md) has a detailed plan so far — write the next stage's detailed plan once the prior stage's tests are green and committed.

**Goal:** Implement NinjaKeys end to end, in stages that each produce a working, demoable increment rather than one big-bang build.

**Spec:** [wayfinder/map.md](../../../wayfinder/map.md) and its 19 closed tickets under `wayfinder/tickets/` — every stage below traces back to specific tickets; read the linked ticket(s) before planning or implementing a stage.

## Global Constraints (apply to every stage)

- **Language/tooling:** Kotlin, current-stable Gradle/AGP, `minSdk` 31 (Android 12+), phones only. ([tech-stack ticket](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md))
- **Module split:** `core-engine` (pure Kotlin, zero Android dependency, unit-testable with plain JUnit) for gesture recognition / dictionary / prediction logic; `app` (Compose UI, `InputMethodService`, Room + DataStore) for everything Android-specific. Never let Android types leak into `core-engine`. ([tech-stack ticket](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md))
- **Persistence:** Jetpack DataStore for settings/preferences, Room for dictionary + learned-word data. ([tech-stack ticket](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md))
- **Gesture algorithm:** classic algorithmic path-matching (no ML/neural), behind swappable `CandidateGenerator`/`PathScorer` interfaces. ([gesture-algorithm ticket](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md))
- **On-device only:** no cloud sync, no accounts, anywhere in the app. ([map notes](../../../wayfinder/map.md))
- **TDD throughout:** every stage's tasks are test-first (write failing test, implement, verify green, commit) — this isn't a separate "testing stage," it's how every stage is built. [Stage 11](#stage-11-test-infrastructure-hardening) only adds the shared fixture library, instrumented-test harness, and CI — not first-time test coverage.
- **Package naming** (not decided by any ticket — a reasonable implementation default, confirm/rename early if it doesn't fit): base package `com.ninjakeys`, `core-engine` under `com.ninjakeys.core`, `app` under `com.ninjakeys.app`.

---

## Stage 1: Core-engine bootstrap + minimal single-finger recognition

**Detailed plan:** [2026-09-13-ninjakeys-stage-1-core-engine-bootstrap.md](2026-09-13-ninjakeys-stage-1-core-engine-bootstrap.md)

Gradle project scaffolding (`core-engine` + `app` modules), the `GesturePath`/`CandidateGenerator`/`PathScorer` interfaces, a trie-based candidate generator, a DTW-plus-corner-bonus path scorer, and frequency/weight combination — all against a tiny embedded test dictionary, zero Android/emulator involved. **Demoable as:** a green JVM unit test suite proving a synthetic "hi" swipe path resolves to "hi" over decoy candidates.

Traces to: [001](../../../wayfinder/tickets/001-gesture-algorithm-architecture.md), [008](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md).

## Stage 2: Minimal working IME

Bare-bones `InputMethodService` + Compose input view rendering a hardcoded English QWERTY layout, capturing raw `MotionEvent` touch paths, feeding them into Stage 1's recognizer, and committing the winning word via `InputConnection`. No punctuation, no multi-finger gestures, no real dictionary yet — just swipe-a-word-into-any-text-field, using the same tiny test dictionary from Stage 1. **Demoable as:** installing NinjaKeys on the emulator, enabling it as a keyboard, and swipe-typing one of the test-dictionary words into a real app's text field.

Traces to: [007](../../../wayfinder/tickets/007-android-ime-architecture-research.md), [008](../../../wayfinder/tickets/008-tech-stack-and-project-structure.md).

## Stage 3: Real English dictionary integration

Build-time pipeline that processes `wordfreq` + Google Books Ngrams data into a bundled Room/SQLite asset, loaded on first run; wire real base-frequency scoring into the Stage 1 `PathScorer`. **Demoable as:** swipe-typing resolving correctly against a real ~25k+ word English vocabulary instead of the tiny test dictionary.

**Carried forward from Stage 1's SDD review (Task 5, parked finding):** `ShapePathScorer`'s corner-matching-bonus branch was never exercised by Stage 1's tests (all fixture words were 2-letter/straight-line paths, so `turningPointIndices` always returned empty). Once real multi-letter dictionary words are in play, add a unit test with a genuine 3+ letter cornered path before trusting this branch in production — it's untested logic, not verified-safe logic.

Traces to: [002](../../../wayfinder/tickets/002-dictionary-research.md).

## Stage 4: Flick punctuation + standard IME conveniences

Swipe-up-for-numbers (corner-labeled keys), swipe-to-space on comma/period/question-mark/quotes, autocapitalization, double-space-for-period, long-press accented characters, and seamless tap/swipe mixing within one word. **Demoable as:** a keyboard that feels like a complete single-language typing experience, not just a swipe-recognition demo.

Traces to: [006](../../../wayfinder/tickets/006-flick-punctuation-design.md), [014](../../../wayfinder/tickets/014-standard-ime-conveniences.md).

## Stage 5: Hebrew layout, language switching, RTL

Hebrew layout (standard, no niqqud) with the geresh/gershayim key, globe-key + 2-finger-swipe language switching, RTL text handling (layout stays LTR-arranged, only text flows RTL), and Hebrew dictionary/n-gram data (wordfreq plus self-generated n-grams from OPUS/Wikipedia text, per the dictionary ticket). **Demoable as:** switching languages mid-message and swipe-typing correctly in both English and Hebrew.

Traces to: [009](../../../wayfinder/tickets/009-keyboard-layouts.md), [002](../../../wayfinder/tickets/002-dictionary-research.md).

## Stage 6: Multi-finger command gestures + basic Settings

Direct 2-finger gestures for undo/redo, dismiss (language-switch already lands in Stage 5); long-press-space command mode for cut/copy/paste/select-all; the Settings app's **Gestures** section for rebinding, wired to real gesture dispatch (global bindings only). **Demoable as:** rebinding a gesture in Settings and seeing the new binding take effect live in the keyboard.

Traces to: [004](../../../wayfinder/tickets/004-multi-finger-gestures.md), [015](../../../wayfinder/tickets/015-settings-app-ux.md).

## Stage 7: Personal dictionary & on-device learning

The `personal_overrides` Room table layered over the immutable base dictionary; all six learning signals (explicit add, non-top-suggestion pick, manual edit, flow-correction reinforcement/undo, delete-and-retype); capped growth with usage-activity-based decay; the Settings **Dictionary & Learning** section (reset-all, per-word forget). **Demoable as:** typing a correction once and seeing it win next time you swipe the same shape.

Traces to: [003](../../../wayfinder/tickets/003-personal-dictionary-learning-model.md).

## Stage 8: N-gram context scoring + flow correction

Contextual scoring using the previous 2-3 words (configurable); the background flow-correction pass (margin threshold, bounded cascading depth default 2); the suggestion/correction strip UI with the slot-machine reel (porting the validated [prototype](../../../wayfinder/tickets/010-theming-baseline.md) interaction from branch `prototype/theming-baseline`), including RTL rendering in Hebrew mode. **Demoable as:** typing a sentence where an earlier word gets silently corrected based on later context, then reeling it back via the strip.

Traces to: [011](../../../wayfinder/tickets/011-flow-correction-and-undo-gestures.md), [010](../../../wayfinder/tickets/010-theming-baseline.md).

## Stage 9: Two-handed split-word gesture typing

The signature Nintype feature: concurrent partial-path tracking per pointer, touch-down-order merge into full-word candidates, the ~300-400ms grace window, and taps-as-doubled-letters while a gesture is active. Deliberately scheduled after the single-finger path is proven solid, since it's the riskiest, most novel piece of the core engine. **Demoable as:** swiping "th" with one finger and "ere" with another, concurrently, and getting "there".

Traces to: [013](../../../wayfinder/tickets/013-two-handed-split-word-typing.md), [012](../../../wayfinder/tickets/012-nintype-multitouch-research.md).

## Stage 10: Theming + Settings polish

Apply the validated palette/typography token system (warm-neutral + teal accent, Manrope + JetBrains Mono) as the real Compose theme (light/dark); finish the remaining Settings sections (Setup guided keyboard-enable flow, Typing tunables as sliders/steppers, Help gesture reference); add the persistent live-preview textbox to every settings screen. **Demoable as:** the full Settings app, themed, with every screen showing live keyboard behavior underneath.

Traces to: [010](../../../wayfinder/tickets/010-theming-baseline.md), [015](../../../wayfinder/tickets/015-settings-app-ux.md).

## Stage 11: Test infrastructure hardening

The shared word+path fixture library in `core-engine` test sources (reused, converted to `MotionEvent`s, by `app`'s instrumented tests); Espresso/Compose UI tests and UiAutomator cross-app tests for `app`; a GitHub Actions workflow running unit + emulator instrumented tests on every push. Physical-device testing against the Galaxy S25 stays a manual local step. **Demoable as:** a green CI badge on `main`, and `./gradlew connectedAndroidTest` passing against the actual phone.

Traces to: [016](../../../wayfinder/tickets/016-testing-strategy.md).

## Stage 12: Dev distribution, hot reload, release packaging

Package `core-engine` as a separately-loadable `.jar`/`.dex`, loaded via `DexClassLoader`, with a GitHub-Releases-based update checker for silent hot-updates; wireless-ADB deploy scripting for `app`-module iteration; semantic-versioning tags on GitHub Releases; placeholder app icon. **Demoable as:** editing a `core-engine` scoring constant, pushing a release, and watching the phone pick it up without a manual reinstall.

Traces to: [017](../../../wayfinder/tickets/017-dev-distribution-hot-reload.md), [019](../../../wayfinder/tickets/019-release-packaging.md).

---

## Explicitly deferred (per the map, not part of any stage above)

- Dedicated accessibility engineering (TalkBack, motor-impairment fallback) — standard Compose semantics only for now. ([018](../../../wayfinder/tickets/018-accessibility.md))
- Play Store submission — GitHub Releases is the real channel for now; Play remains a future possibility that would require reworking Stage 12's dynamic-loading approach. ([019](../../../wayfinder/tickets/019-release-packaging.md))
