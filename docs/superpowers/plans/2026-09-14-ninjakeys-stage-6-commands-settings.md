# NinjaKeys Stage 6: Command Gestures and Basic Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add undo/redo, dismiss, language-switch, and command-mode bindings with a customizable settings model.

**Architecture:** Pure Kotlin command bindings map stable gesture slots to actions and reject duplicate triggers. Android translates actions to `InputConnection` edits or service dismissal; settings storage starts as a local preference abstraction and can later move to DataStore without changing dispatch.

**Spec:** `wayfinder/tickets/004-multi-finger-gestures.md` and `wayfinder/tickets/015-settings-app-ux.md`.

## Global Constraints

- Language switching remains the existing English/Hebrew action.
- Bindings are global only; no per-app overrides.
- Long-press space enters command mode before cut/copy/paste/select-all actions.

---

### Task 1: Binding model and dispatcher

- [ ] Write failing tests for default bindings, duplicate-trigger rejection, and action dispatch.
- [ ] Implement pure Kotlin bindings/dispatcher.
- [ ] Run all JVM tests and commit.

### Task 2: Android command execution and settings model

- [x] Wire undo/redo/cut/copy/paste/select-all/dismiss to the current input connection/service.
- [x] Add a minimal settings activity/model exposing global gesture bindings.
- [x] Verify assembly and commit.
