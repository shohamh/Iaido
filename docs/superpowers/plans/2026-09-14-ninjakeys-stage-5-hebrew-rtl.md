# NinjaKeys Stage 5: Hebrew, RTL, and Language Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the standard Hebrew layout, language-aware punctuation, RTL output metadata, and globe/two-finger English-Hebrew switching.

**Architecture:** Language is an explicit pure Kotlin state (`Language.ENGLISH` or `Language.HEBREW`) owned by a small switcher. Layout geometry stays left-to-right in both modes, while the active language controls letters, dictionary, punctuation glyphs, and editor locale hints.

**Spec:** `wayfinder/tickets/009-keyboard-layouts.md`, `wayfinder/tickets/002-dictionary-research.md`, and `wayfinder/tickets/004-multi-finger-gestures.md`.

## Global Constraints

- Keep physical key positions LTR in Hebrew mode; only output text uses RTL.
- Globe tap cycles enabled languages; a two-finger horizontal swipe invokes the same switch action.
- Hebrew v1 has no niqqud.

---

### Task 1: Core language model and layout

- [ ] Add failing tests for Hebrew key ordering, language cycling, and RTL detection.
- [ ] Implement `Language`, `LanguageSwitcher`, Hebrew layout, and language helpers.
- [ ] Run all core tests and commit.

### Task 2: App language-aware keyboard

- [ ] Add a small Hebrew frequency asset/repository fixture and active dictionary selection.
- [ ] Render the same physical geometry with Hebrew labels and geresh/gershayim behavior.
- [ ] Add globe tap and two-finger horizontal gesture routing; set `EditorInfo` language hints where supported.
- [ ] Verify JVM tests and debug assembly, then commit.

