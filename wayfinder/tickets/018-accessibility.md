---
id: 18
title: Accessibility considerations
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Decide what accessibility support NinjaKeys needs for v1, given it's a gesture-heavy input method built for personal use first: TalkBack/screen-reader compatibility, minimum touch-target sizing, contrast requirements for the theming baseline, and whether the gesture-heavy interaction model (swipe-to-type, multi-finger commands, flick punctuation) has any fallback for someone who can't perform these gestures.

## Resolution

- **Dedicated accessibility work deferred**: consistent with the [release packaging](../map.md) deferral, no dedicated TalkBack/screen-reader or motor-impairment-fallback engineering for v1. NinjaKeys builds on standard Compose components (which carry reasonable default semantics) rather than actively engineering for accessibility, revisited if/when there's an actual need (publishing, or changed personal needs).
- **Touch-target sizing**: follows Android's standard 48dp minimum touch-target guidance regardless of the deferral above — this is baseline mis-tap prevention benefiting everyone, not an accessibility-specific feature, and costs nothing extra to build correctly from the start.
- **Contrast check**: the already-locked-in [theming baseline](010-theming-baseline.md) palette (warm-neutral paper tones + teal accent) gets a basic WCAG contrast verification (text/key-labels vs. background) — a quick check against an existing palette, not new design work.
- **Tap-typing as the de facto fallback**: the seamless tap/swipe coexistence already decided in the [standard IME conveniences ticket](014-standard-ime-conveniences.md) serves as the practical fallback for anyone who can't perform swipe gestures, with no dedicated accessibility-specific fallback UI needed.
