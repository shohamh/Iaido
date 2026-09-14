---
id: 5
title: Research Swype's directional flick-punctuation mapping
type: research
status: closed
assignee: agent
blocked_by: []
---

## Question

Document how Swype's per-key directional flicks for punctuation/alternate characters actually worked (which directions mapped to which characters, on which keys) as a factual reference for designing Iaido' own mapping.

## Resolution

Per Swype's own patent filing ("Key swipe gestures for touch sensitive UI virtual keyboard", [US 2014/0306898](https://patents.justia.com/patent/20140306898)) and contemporary reviews:

- Each letter key supported up to **four directional flicks**, each bound to a different character/action:
  - **Swipe up** on a key → uppercase/shifted version of that letter.
  - **Swipe down** on a key → an alternate (typically lowercase or secondary) selection.
  - **Swipe right** → a first alternate character (commonly a number or symbol associated with that key).
  - **Swipe left** → a second alternate character (a different number/symbol).
- Separately, Swype had **gesture shortcuts routed through the dedicated "Swype key"** (not per-letter-key flicks) for actions like Cut, Copy, Select All, and jumping to the number keypad — these are app-level command gestures, not punctuation flicks, and are out of scope for this ticket (they overlap with the multi-finger gesture-customization ticket instead).
- Net effect for a swipe-typing keyboard: numbers and common symbols became reachable via directional flicks directly on the letter keys, without switching to a separate symbols layout for common cases — this is the specific behavior the user called out wanting replicated.
- Nuance discontinued Swype in February 2018, so no living reference implementation exists to test against directly; this reconstruction is from patent text and period reviews/tutorials, not firsthand testing.

This gives enough of a factual base for the [Design Iaido' flick-punctuation mapping](006-flick-punctuation-design.md) ticket to decide the concrete per-key/per-direction mapping for both English and Hebrew layouts.
