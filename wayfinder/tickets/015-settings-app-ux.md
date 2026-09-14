---
id: 15
title: Settings app UX beyond gesture customization
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Design the overall settings app structure (screens/sections) that organizes every configurable behavior already decided across the map's tickets — gesture bindings, tunable constants (cascading depth, grace window, etc.), personal-dictionary reset/forget, language management, and enabling Iaido as the system IME — into a coherent settings UX, plus decide what (if anything) still needs a general "about/help" screen.

## Resolution

**Top-level structure** (grouped by what the user is trying to do, not by which ticket produced the setting):
- **Setup**: guided flow for Android's standard two-step keyboard setup (enable under Settings > System > Languages & Input, then switch via the picker), deep-linking into the system screens rather than replicating them. Also reachable on first run.
- **Gestures**: rebind multi-finger commands (language-switch, dismiss, undo/redo, command-mode cut/copy/paste/select-all) from the [multi-finger gestures ticket](004-multi-finger-gestures.md).
- **Typing**: curated tunables only — cascading flow-correction depth (default 2, from the [flow-correction ticket](011-flow-correction-and-undo-gestures.md)) and the two-handed typing grace window (~300-400ms default, from the [split-word typing ticket](013-two-handed-split-word-typing.md)) as steppers/sliders.
- **Dictionary & Learning**: reset-all learning and per-word forget, from the [personal dictionary ticket](003-personal-dictionary-learning-model.md).
- **Help**: static gesture reference covering multi-finger commands, flick punctuation, swipe-to-space keys, and two-handed split-word typing — useful even for personal use since custom-bound and easily-forgotten gestures benefit from a lookup.

Language management (English/Hebrew) isn't a separate section — v1 has no per-language configuration beyond the switching mechanism already decided.

**Tunable depth**: only constants explicitly called out as user-tunable during design (cascading depth, grace window) get real UI controls. Raw internal scoring weights (λ's, corner-bonus weight, proximity thresholds, margin thresholds) stay as internal `core-engine` constants, not exposed anywhere in Settings — they're developer-tuning knobs, not user preferences, and exposing them would turn Settings into an incomprehensible science-fair project.

**Live preview**: every settings screen keeps a persistent textbox with Iaido itself active beneath it, so any change (a rebound gesture, an adjusted tunable) can be tried immediately without leaving Settings.
