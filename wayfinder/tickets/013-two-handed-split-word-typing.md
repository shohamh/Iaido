---
id: 13
title: Two-handed split-word gesture typing
type: grilling
status: open
assignee: null
blocked_by: [1, 12]
---

## Question

Design NinjaKeys' version of Nintype's signature mechanic: splitting a single word's letters across simultaneous partial gestures/taps from two hands (e.g. swiping "th" with one hand while swiping/tapping "ere" with the other, concurrently, to produce "there"), including mixed taps for doubled letters (e.g. swipe then double-tap for "I'll").

This extends the [gesture-recognition algorithm architecture](001-gesture-algorithm-architecture.md)'s `CandidateGenerator`/`PathScorer` interfaces to accept multiple concurrent partial paths/taps that must be merged into one candidate letter sequence before scoring against the dictionary. Needs: how partial paths are ordered/merged (e.g. by relative keyboard position, or touch-down order), how the system decides a word is "complete" (both hands lifted? a timeout? explicit signal?), how this interacts with the single-hand fallback (must single-finger swiping still work standalone), and how doubled-letter taps are disambiguated from an unrelated second gesture starting.
