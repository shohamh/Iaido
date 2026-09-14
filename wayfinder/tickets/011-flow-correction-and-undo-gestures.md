---
id: 11
title: Flow correction & text-correction gestures
type: grilling
status: closed
assignee: agent
blocked_by: [1, 2]
---

## Question

Design the background "flow correction" pass and the gestures for reviewing/undoing it, both operating on already-committed text (not the keyboard surface):

- **Flow correction**: after committing a word, a background thread re-scores prior committed word(s) using fuller sentence context (via the n-gram context scoring from the gesture-algorithm ticket), silently replacing a word only if an alternate candidate beats it by a clear margin threshold — with a brief flash/highlight on the changed word so the correction is noticeable.
- **Correction gestures on committed text**: swiping up on a previously-typed word reopens it for correction (shows/cycles alternate candidates); swiping up-and-left on a word specifically undoes the last autocorrect applied to it (reverts to the pre-correction word).

Needs: exact margin-threshold tuning approach, how far back the background pass looks/re-triggers, how the alternates strip is shown for the reopened word, exact gesture detection zones/thresholds in the text area, and interaction with the personal-learning ticket (does an undo count as a negative training signal?).

## Resolution

**Technical constraint discovered mid-design**: an Android `InputMethodService` only receives touch events within its own input view; it has no access to touch events on the host app's text field and no reliable way to hit-test where a word renders on-screen in an arbitrary third-party app (text can scroll, wrap, differ per app). Reading the host app's rendered text would require Accessibility Service permission — too heavy an ask for a keyboard and a complication for any future publishing. So the gestures described in the original question (swiping directly on committed text in the host app) are **not implementable as stated**.

**Resolved design**: the correction interaction lives entirely within the IME's own suggestion-bar area instead:

- A small horizontal strip of committed/corrected words is rendered as chips in the IME's own suggestion-bar UI (the same area the word-candidate strip already occupies).
- **Swipe up (or from the chip's top/bottom edge) on a chip** → opens a vertically-scrolling "slot machine" reel of alternate candidates for that word, positioned above it. Releasing the finger commits whichever candidate is centered at release time (continuous drag-to-commit — no separate confirm tap, since that would defeat the point of a fast gesture). The chosen replacement is written into the actual text via `InputConnection`, since the IME knows exactly what it itself inserted and where.
- **Swipe up-and-left on a chip** → undoes that word's last autocorrect specifically, reverting to the pre-correction word via `InputConnection`. If the word was never autocorrected, this is a silent no-op.
- **Cursor re-sync**: if the user moves the cursor back into text committed earlier in the same input session (not just the tail end), the strip reloads that region's word/correction history so earlier words become correctable too, not just the most recent ones. This history is tracked only for the **current input session** (from when the keyboard opens on a text field until it closes/the app backgrounds) — it does not persist across app switches or keyboard restarts, and text not typed by Iaido itself in this session (pre-existing or pasted text) has no history to reload. Implementation-wise this means tracking a position-indexed map of word → correction metadata for the session, refreshed on `onUpdateSelection` cursor-position callbacks.
- This preserves the intended feel (a quick gesture near where you're already typing, no menu-diving) without requiring access to the host app's text or Accessibility permissions.

**Background flow-correction pass:**
- Triggered after each new word commits; re-scores the immediately preceding words within the **same 2-3 word context window** already used for n-gram scoring (from the [gesture-recognition architecture](001-gesture-algorithm-architecture.md) and [personal dictionary](003-personal-dictionary-learning-model.md) tickets) — not the whole sentence or paragraph.
- Silently replaces a word only when an alternate beats it by a clear margin threshold (tunable constant, consistent with the personal-learning ticket's approach to avoiding flip-flopping), with a brief flash/highlight on the corrected chip.
- **Cascading is allowed, but bounded**: correcting word N can re-trigger re-evaluation of preceding words, up to a default depth of **2 words**, tunable in settings. This is a deliberate departure from a stricter "one bounded pass, no cascading" default — the user wants limited cascading rather than none, so depth is capped and configurable rather than unlimited.

**Learning signals**: unchanged from the [personal dictionary & learning](003-personal-dictionary-learning-model.md) ticket — undoing a flow-correction is a negative signal on the word it changed to and a positive signal for the original.

**Addendum (from [Keyboard layouts](009-keyboard-layouts.md))**: the word-correction chip strip renders right-to-left when the active language is Hebrew, matching natural reading order for the words it displays.
