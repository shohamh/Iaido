---
type: wayfinder:map
---

# NinjaKeys — map

## Destination

A full product + technical spec for NinjaKeys, an Android gesture-typing keyboard combining Swype's trace-to-word matching and directional flick punctuation with Nintype's multi-finger gestures — detailed enough that a future agent session, with no memory of this conversation, can implement it without re-deriving decisions.

## Notes

- Domain: Android IME development (`InputMethodService`), gesture-typing algorithms, on-device NLP/prediction.
- Skills every session should consult: `/grilling` and `/domain-modeling` for decision tickets; `/prototype` for UI/behavior tickets; `/research` for fact-finding tickets.
- Standing preferences:
  - Built from scratch in Kotlin, not forked from an existing IME.
  - Android 12+ (API 31+) only, phones only. Primary test device: Galaxy S25 (physical) + emulator.
  - Gesture-recognition: classic algorithmic path-matching (not ML/neural), but architected behind a swappable algorithm interface so it can be replaced later.
  - Word prediction/learning is strictly on-device — no cloud sync, no accounts.
  - Languages: English + Hebrew, with easy switching. Standard Hebrew layout, no niqqud for v1.
  - Multi-finger gestures (Nintype-style) should be user-customizable, not hardcoded.
  - Flick punctuation should replicate Swype's per-key directional flicks.
  - Single default theme (system light/dark) for v1; architecture should leave room for theming later.
  - Built for personal use first; structure so publishing later (Play Store/F-Droid/open source) stays possible.
  - This effort is planning-only: tickets resolve decisions, they don't execute implementation.

## Decisions so far

- [Research Android IME architecture patterns](tickets/007-android-ime-architecture-research.md) — extend `InputMethodService` with a Compose-rendered input view for UI, raw `MotionEvent` handling for gesture capture, and a separate pure-Kotlin core engine module (dictionary/gesture/prediction) decoupled from the Android IME service module.
- [Tech stack & project structure](tickets/008-tech-stack-and-project-structure.md) — Kotlin + Compose, two modules (`core-engine` pure-Kotlin, `app` for IME/UI/persistence), Room + DataStore, minSdk 31, current-stable tooling.
- [Gesture-recognition algorithm architecture](tickets/001-gesture-algorithm-architecture.md) — swappable `CandidateGenerator`/`PathScorer` interfaces, DTW+corner shape scoring combined with frequency/personal/n-gram-context weights, incremental + finger-lift recognition, top-5 ranked output. Split off flow-correction/undo gestures as a new ticket.
- [Research English + Hebrew word-frequency dictionaries](tickets/002-dictionary-research.md) — use `wordfreq` (CC BY-SA 4.0) for base English+Hebrew word frequency; Google Books Ngrams (CC BY 3.0) for English n-grams; self-generate Hebrew n-grams from OPUS/Wikipedia text (no ready-made open dataset found); ship as a bundled binary/SQLite asset.
- [Research Swype's flick-punctuation mapping](tickets/005-flick-punctuation-research.md) — per Swype's patent, each letter key had 4 directional flicks (up=shift, down=alt-lower, right/left=number/symbol alternates); Swype-key command gestures (cut/copy/select-all) are separate and belong with the multi-finger gesture ticket instead.
- [Personal dictionary & on-device learning model](tickets/003-personal-dictionary-learning-model.md) — separate `personal_overrides` overlay table over the immutable base dictionary; six learning signals including delete-and-retype amplifying n-gram context; usage-activity-based decay (not time-based); backup via Android's own app-data backup; reset-all and per-word forget controls.
- [Research Nintype's multi-finger mechanic](tickets/012-nintype-multitouch-research.md) — corrected understanding: it's one word split across simultaneous partial gestures/taps from two hands (e.g. "th" + "ere" → "there"), not two independent concurrent words. Core-engine extension, not a command-gesture binding.
- [Multi-finger gesture set & customization](tickets/004-multi-finger-gestures.md) — discrete command gestures only (language-switch, dismiss, undo/redo direct; cut/copy/paste/select-all via long-press-space command mode), global customizable bindings. Split off two-handed split-word typing as its own ticket.
- [Two-handed split-word gesture typing](tickets/013-two-handed-split-word-typing.md) — concurrent partial paths merged by touch-down order and re-scored via existing PathScorer; grace window (~300-400ms) after lift for near-simultaneous typing; taps-as-doubled-letters only while a word gesture is active; single-finger case falls out for free.
- [Design NinjaKeys' flick-punctuation mapping](tickets/006-flick-punctuation-design.md) — swipe-up on letter keys = number (corner-labeled); swipe-to-spacebar on comma/period/question-mark/quotes (English) and geresh/gershayim (Hebrew) = character + trailing space; no full 4-direction-per-key scheme.
- [Flow correction & text-correction gestures](tickets/011-flow-correction-and-undo-gestures.md) — corrections happen via a word-chip strip in the IME's own suggestion bar (not on host-app text, which IMEs can't touch), slot-machine-style scroll-to-pick alternates, cursor-position-synced history for the current input session only, bounded cascading correction (default depth 2, tunable). Renders RTL in Hebrew mode (addendum from the layouts ticket).
- [Keyboard layouts & switching UX](tickets/009-keyboard-layouts.md) — no new punctuation row (swipe-to-space uses each character's existing standard slot); Hebrew apostrophe-slot becomes geresh/gershayim; globe-key + 2-finger-swipe for language switch; no permanent number row, `?123` layout for the rest; keyboard layout doesn't mirror for Hebrew RTL, only text does.
- [Theming baseline](tickets/010-theming-baseline.md) — validated via prototype (branch `prototype/theming-baseline`): warm-neutral palette with teal accent, Manrope + JetBrains Mono, flat low-elevation Swype-era keys. Suggestion strip mirrors the actual sentence tail with a slot-machine reel per word (drag-to-scroll, release-to-commit, resumes at last pick, RTL in Hebrew). Confirms correction lives in the strip, not on host-app text.
- [Standard IME conveniences beyond swipe-typing](tickets/014-standard-ime-conveniences.md) — tap-typing reuses the same scoring pipeline as swipe-typing; standard autocapitalization; double-space-for-period coexists with swipe-to-space; long-press accents for English (none needed for Hebrew); seamless tap/swipe mixing within one word.
- [Settings app UX beyond gesture customization](tickets/015-settings-app-ux.md) — five sections (Setup/Gestures/Typing/Dictionary & Learning/Help) grouped by user intent; only explicitly-called-out tunables (cascading depth, grace window) get UI, raw scoring weights stay internal; guided Android keyboard-enable/switch flow; every settings screen keeps a live preview textbox with NinjaKeys active beneath it.
- [Automated testing strategy](tickets/016-testing-strategy.md) — JUnit unit tests for `core-engine` (no device needed) using shared path-point fixtures also converted to `MotionEvent`s for Espresso/UiAutomator instrumented tests on `app`; both emulator and physical Galaxy S25 are just ADB targets of `connectedAndroidTest`; GitHub Actions runs unit+emulator tests on every push, physical-device testing stays a manual local step.
- [Fast-iteration distribution to physical phone](tickets/017-dev-distribution-hot-reload.md) — `core-engine` ships as a separately-loadable `.jar`/`.dex` via `DexClassLoader` for genuinely silent hot-updates (checked via GitHub Releases); `app`-module changes need a full APK install with one required tap (Android platform constraint, no rooting); Compose Live Edit/Apply Changes for active-session hot reload; wireless ADB throughout. Explicitly incompatible with future Play distribution — accepted as a deferred tradeoff.
- [Accessibility considerations](tickets/018-accessibility.md) — dedicated accessibility work deferred (like release packaging) in favor of standard Compose semantics; 48dp minimum touch targets regardless (benefits everyone); basic WCAG contrast check on the locked theming palette; tap-typing already serves as the de facto non-gesture fallback.

## Not yet specified

- Release packaging concerns (app icon, store listing) — deferred until publishing is actually pursued.

## Out of scope

- Tablet/foldable support — phones only per destination.
- Niqqud (Hebrew vowel points) input — standard Hebrew layout only for v1.
- Cloud sync / account-based dictionary sync — on-device only.
- Languages beyond English and Hebrew for v1.
- Custom theming/visual customization beyond system light/dark — single default theme for v1.
- Forking an existing IME codebase — building from scratch.
