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

(none yet — map just charted)

## Not yet specified

- Full word-prediction ranking/scoring algorithm once the dictionary format and learning model are decided.
- Settings app UX beyond gesture customization (general preferences, about/help screens).
- Standard IME conveniences beyond swipe-typing: autocorrect-on-tap-typing, autocapitalization, long-press secondary characters on number/symbol rows — need a decision on which are in scope for v1.
- Undo/correction UX immediately after a swipe (e.g. swipe-to-fix, alternate-word picker).
- Testing/QA plan details (beyond "Galaxy S25 + emulator").
- Release packaging concerns (app icon, store listing) — deferred until publishing is actually pursued.
- Accessibility considerations.

## Out of scope

- Tablet/foldable support — phones only per destination.
- Niqqud (Hebrew vowel points) input — standard Hebrew layout only for v1.
- Cloud sync / account-based dictionary sync — on-device only.
- Languages beyond English and Hebrew for v1.
- Custom theming/visual customization beyond system light/dark — single default theme for v1.
- Forking an existing IME codebase — building from scratch.
