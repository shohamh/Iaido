---
id: 9
title: Keyboard layouts (English + Hebrew) and layout switching UX
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Decide the key arrangement for the English QWERTY layout and the standard Hebrew layout, and the UX for switching between them (e.g. spacebar swipe, dedicated key, long-press) alongside the multi-finger language-switch gesture from the multi-finger gestures ticket.

## Resolution

**Punctuation placement**: No new dedicated punctuation row is added to accommodate swipe-to-space. Comma, period, question mark, and quote each keep whatever their standard/conventional placement already is in an Android-style layout (some as ordinary base-layer keys, others as the usual secondary/alt-character slot on a letter key, per normal keyboard convention) — the exact per-key assignment is an implementation detail for the actual layout-building work, not a spec-level decision. The swipe-to-space gesture (from the [flick-punctuation ticket](006-flick-punctuation-design.md)) starts from wherever that character's standard slot already is and drags to the spacebar; the layout itself isn't redesigned around this feature.

**Hebrew apostrophe/quote slot**: The same physical key slot used for English apostrophe/quote (tap=apostrophe, long-press=quote) becomes, in Hebrew mode, tap=geresh (׳) / long-press=gershayim (״) — gershayim *is* the double-quote-equivalent character, so this is the same tap/long-press pattern with different glyphs per active language, not a separate mechanism.

**Language switching**:
- Primary discoverable method: a small globe-icon key (standard Android IME convention) that cycles to the next enabled language on tap.
- Power-user shortcut: the 2-finger horizontal swipe gesture already decided in the [multi-finger gestures ticket](004-multi-finger-gestures.md).

**Numbers/symbols**:
- No permanently visible number row — keeps the minimal Swype/Nintype-era aesthetic. Numbers are primarily reached via swipe-up on letter keys (from the flick-punctuation ticket).
- A `?123`-style key switches to a secondary layout with a numeric row plus symbols not covered by swipe/flick access (colon, semicolon, brackets, currency, math symbols, etc.) — for cases like entering a phone number where repeated swipe-up isn't practical. Shared structure across both languages.

**RTL handling**:
- Only the **text in the output field** flows right-to-left for Hebrew (standard Android text rendering handles this automatically). The **keyboard's own key layout does not mirror** — physical key positions stay in the same left-to-right arrangement as English, matching how real physical Hebrew keyboards work.
- **Cross-ticket addendum to [Flow correction & text-correction gestures](011-flow-correction-and-undo-gestures.md)**: the word-correction chip strip should render right-to-left when the active language is Hebrew, matching natural reading order for the words it displays.
