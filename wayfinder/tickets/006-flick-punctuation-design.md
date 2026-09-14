---
id: 6
title: Design Iaido' flick-punctuation mapping
type: grilling
status: closed
assignee: agent
blocked_by: [5]
---

## Question

Using the researched Swype reference mapping, decide Iaido' own per-key directional flick mapping for punctuation/alternate characters, for both the English and Hebrew layouts.

## Resolution

Iaido uses two independent, coexisting mechanics rather than Swype's original generic 4-direction-per-key scheme:

**1. Swipe-up for numbers** (modern convention, not Swype's original scheme): swipe up on any letter key inserts that key's number (the one shown in its small corner label) — matches contemporary keyboard muscle memory (Gboard etc.) more than Swype's own up=shift/down/left/right layout, since the goal is the *feel* of fast access, not literal parity with the original scheme.

**2. Swipe-to-space for sentence-ending/attaching punctuation** (this is Swype's actual documented trick, replicated directly): swiping from a specific punctuation key to the spacebar inserts that character **plus a trailing space** in one motion; a plain tap on the same key inserts just the character. Applies to:
- English: comma, period, question mark, quotes.
- Hebrew: geresh (׳), gershayim (״).

Other punctuation (colon, semicolon, exclamation mark, etc.) is not flick-accessible — it lives on the normal symbols layout switch.

**Visual hinting**: letter keys show a small corner label for their number (swipe-up target), matching modern convention and costing nothing in unfamiliarity. The swipe-to-space keys stay visually clean/unlabeled (no icon clutter for "this key can swipe to space") — discoverability is handled via a one-time help screen rather than on-key hints.

**Interaction with per-key directional flicks generally**: Iaido does not implement Swype's full 4-direction-per-key scheme (up/down/left/right all bound to different characters on every key) — only swipe-up (numbers, all letter keys) and swipe-to-spacebar (the specific punctuation keys above) are used, keeping the gesture vocabulary small and consistent with the multi-finger command gestures already designed.
