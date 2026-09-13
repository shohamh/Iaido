---
id: 13
title: Two-handed split-word gesture typing
type: grilling
status: closed
assignee: agent
blocked_by: [1, 12]
---

## Question

Design NinjaKeys' version of Nintype's signature mechanic: splitting a single word's letters across simultaneous partial gestures/taps from two hands (e.g. swiping "th" with one hand while swiping/tapping "ere" with the other, concurrently, to produce "there"), including mixed taps for doubled letters (e.g. swipe then double-tap for "I'll").

This extends the [gesture-recognition algorithm architecture](001-gesture-algorithm-architecture.md)'s `CandidateGenerator`/`PathScorer` interfaces to accept multiple concurrent partial paths/taps that must be merged into one candidate letter sequence before scoring against the dictionary. Needs: how partial paths are ordered/merged (e.g. by relative keyboard position, or touch-down order), how the system decides a word is "complete" (both hands lifted? a timeout? explicit signal?), how this interacts with the single-hand fallback (must single-finger swiping still work standalone), and how doubled-letter taps are disambiguated from an unrelated second gesture starting.

## Resolution

**Merge strategy**: Each concurrent partial gesture runs independently through the existing `CandidateGenerator`, producing letter-subsequence candidates for that partial path alone. Partial candidates are then concatenated — ordered by which gesture's *touch-down* occurred first — into full-word candidates, validated against the dictionary, and re-scored via the existing `PathScorer` against the combined/concatenated path. This is deliberately **not** a fixed keyboard-geography split (e.g. left-half/right-half): the reference example ("th" + "ere" → "there") doesn't map to keyboard halves, so the split is whatever letters the user chooses per hand, merged by temporal order rather than position.

**Word completion**: A word isn't necessarily done the instant all fingers lift. A short **grace window** (~300-400ms, tunable) follows any finger lift; a new touch-down within that window is treated as extending the *same* in-progress word rather than starting a new one. Only once the grace window elapses with no active or newly-started gesture does the word commit. This accommodates realistic (imperfectly simultaneous) two-handed typing rather than requiring perfect concurrency.

**Doubled-letter taps**: A quick tap (minimal movement, short duration) on a key is interpreted as "insert this letter now" (the repeat-letter mechanism, e.g. "I'll") **only** when a word-gesture is currently active or within its grace window. Outside that context, a tap is ordinary single-letter tap-typing, unrelated to gesture recognition.

**Single-finger fallback**: No explicit "two-handed mode" toggle exists. A single active path is simply the one-path case of the same merge pipeline (nothing to concatenate), so standalone single-finger swiping is unchanged and requires no special-casing.

**Performance**: The existing <50ms final-recognition target (from the [gesture-recognition architecture](001-gesture-algorithm-architecture.md) ticket) remains the goal for the merged two-handed case as well, treated as provisional pending real benchmarking on the Galaxy S25 rather than pre-emptively relaxed.
