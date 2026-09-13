---
id: 14
title: Standard IME conveniences beyond swipe-typing
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Decide which conventional keyboard conveniences NinjaKeys supports alongside gesture typing: autocorrect/autocapitalize behavior for tap-typed text (not swiped), long-press secondary characters on keys not already covered by the swipe-up-number/swipe-to-space schemes, and any other standard behaviors (double-space-for-period, auto-capitalize after sentence-ending punctuation, etc.) expected of a modern Android keyboard.

## Resolution

- **Tap-typing autocorrect**: reuses the same scoring pipeline as swipe-typing (base frequency + personal learning + n-gram context, from the [gesture-recognition](001-gesture-algorithm-architecture.md) and [personal dictionary](003-personal-dictionary-learning-model.md) tickets) — one prediction system serves both input modes rather than building separate tap-only logic.
- **Autocapitalization**: standard behavior — capitalizes the first letter at the start of a text field and after sentence-ending punctuation (period/question mark/exclamation).
- **Double-space-for-period**: kept alongside swipe-to-space. They're different input methods (tap pattern vs. swipe gesture) producing the same net result, not competing mechanisms — dropping either would feel like a regression to users of either habit.
- **Long-press secondary characters**: English letter keys get standard accented variants (é, ñ, etc.) via long-press. Hebrew needs no equivalent (niqqud already out of scope; geresh/gershayim already covered on their own dedicated slot).
- **Mixing tap and swipe within one word**: allowed seamlessly (e.g. tap "th", swipe "ere" to finish "there") — this generalizes the doubled-letter tap-during-gesture rule already decided in the [two-handed split-word typing](013-two-handed-split-word-typing.md) ticket (a tap while a gesture is active/in its grace window extends the current word) rather than introducing a new mechanism.
