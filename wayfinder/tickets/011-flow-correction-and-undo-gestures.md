---
id: 11
title: Flow correction & text-correction gestures
type: grilling
status: open
assignee: null
blocked_by: [1, 2]
---

## Question

Design the background "flow correction" pass and the gestures for reviewing/undoing it, both operating on already-committed text (not the keyboard surface):

- **Flow correction**: after committing a word, a background thread re-scores prior committed word(s) using fuller sentence context (via the n-gram context scoring from the gesture-algorithm ticket), silently replacing a word only if an alternate candidate beats it by a clear margin threshold — with a brief flash/highlight on the changed word so the correction is noticeable.
- **Correction gestures on committed text**: swiping up on a previously-typed word reopens it for correction (shows/cycles alternate candidates); swiping up-and-left on a word specifically undoes the last autocorrect applied to it (reverts to the pre-correction word).

Needs: exact margin-threshold tuning approach, how far back the background pass looks/re-triggers, how the alternates strip is shown for the reopened word, exact gesture detection zones/thresholds in the text area, and interaction with the personal-learning ticket (does an undo count as a negative training signal?).
