---
id: 10
title: Theming baseline (default light/dark)
type: prototype
status: closed
assignee: agent
blocked_by: []
---

## Question

Produce a rough visual prototype of the default keyboard theme (light + dark, following system setting): key shapes/spacing, colors, typography — enough to react to and lock in as the v1 baseline, with the architecture left open for future custom theming.

## Resolution

Prototype: an interactive HTML mockup (phone-frame keyboard, light/dark toggle, EN/HE toggle, and a working slot-machine-style reel for the suggestion/correction strip). Captured as a primary source on the throwaway branch `prototype/theming-baseline` (commit "PROTOTYPE: theming baseline visual mockup"), kept out of `master` — only this validated decision is folded into the real spec.

**Validated visual baseline:**
- **Palette**: warm off-white paper (not stark white) in light mode, near-black (not pure black) in dark mode; a teal accent (nodding to Swype's classic gesture-trail color) for the active suggestion/correction state. Same token set drives both themes.
- **Typography**: a geometric sans (Manrope) for key labels/body text; a monospace face (JetBrains Mono) for the small corner number labels and suggestion/chip text, giving the "technical precision" feel appropriate to a gesture-typing tool.
- **Layout**: standard 3-row QWERTY + bottom row, flat low-elevation keys (subtle 1px shadow, not heavy Material-style elevation), minimal visual clutter — matching the Swype/Nintype-era aesthetic rather than a denser modern layout.
- **Suggestion/correction strip**: confirmed to double as both the swipe-candidate strip and the flow-correction word strip, mirroring the **last 2-3 words of the actual sentence being typed** (not decorative/example suggestions). Each word is a chip with a "slot machine" reel: dragging vertically scrolls candidates, releasing commits whichever is centered, and a plain tap/click is a no-op (doesn't accidentally change anything). Reopening a word's reel resumes centered on whatever was last picked, not always the first candidate. A corrected word gets an outlined highlight.
- **RTL**: confirmed via the prototype that the strip and its chips render right-to-left in Hebrew mode, per the addendum already recorded on the [flow-correction ticket](011-flow-correction-and-undo-gestures.md).
- Direct on-screen editing of the message text itself was explicitly rejected during review — it doesn't match the real constraint (an IME can't touch a host app's text view), so all correction interaction is confirmed to live in the strip, which then edits the composed text via the IME's own text-editing API.

Theming customization beyond light/dark stays out of scope for v1, per the map's standing preferences.
