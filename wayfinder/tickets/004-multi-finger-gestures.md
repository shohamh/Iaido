---
id: 4
title: Multi-finger gesture set & customization
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Decide the default multi-finger gesture set (Nintype-style — e.g. 2-finger swipe for language switch, dismiss, etc.), and how users customize/remap these bindings. Starting proposal from initial discussion: 2-finger horizontal swipe = switch input language, 2-finger swipe down = dismiss keyboard.

## Resolution

**Scope note**: Nintype's actual two-handed split-word gesture typing (see [Nintype multi-touch research](012-nintype-multitouch-research.md)) is a core-engine feature, not a discrete command binding — it's split into its own ticket, [Two-handed split-word gesture typing](013-two-handed-split-word-typing.md). This ticket covers only discrete command/navigation gestures.

**Default gesture set:**
- 2-finger horizontal swipe → switch input language (English ↔ Hebrew).
- 2-finger swipe down → dismiss keyboard.
- 2-finger gesture (direction TBD in implementation, e.g. swipe left/right or a distinct pattern from language-switch) → undo / redo, bound directly since these are used constantly and deserve zero-friction access.
- Long-press spacebar → enters a temporary command mode; a follow-up tap/swipe selects cut, copy, paste, or select-all. These are used less often, so a brief mode avoids needing many hard-to-remember distinct gesture combinations. "Search" and other rarer actions stay in a normal menu/settings, not a gesture slot.
- Punctuation is explicitly **not** in scope here — that's the per-key directional-flick ticket.

**Customization**: a settings screen lists each gesture slot (finger-count + direction, or command-mode entry) with a picker to assign any available action; two slots can't share the same trigger. Bindings are **global only** for v1 (no per-app customization — added complexity not clearly needed for a personal-use keyboard).

**Technical capture**: multi-finger gestures are detected via Android `MotionEvent.getPointerCount()`; a gesture counts as multi-finger only if 2+ pointers are down from near the start of the touch sequence, to avoid misreading a single-finger word-swipe as a command gesture.
