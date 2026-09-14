# NinjaKeys Stage 7: Personal Dictionary and Learning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a capped, on-device personal dictionary overlay with all six learning signals, usage-based decay, Room persistence, and settings controls.

**Architecture:** `PersonalDictionary` keeps immutable base entries separate from mutable word and n-gram overrides, applies capped diminishing boosts and activity-based decay, and returns merged snapshots to the recognizer. Room stores the overlay; the app service records learning events and settings owns reset/forget operations.

**Spec:** `wayfinder/tickets/003-personal-dictionary-learning-model.md`.

## Global Constraints

- Learning remains on-device only.
- Base dictionary rows are never mutated.
- Decay is based on other typed-word activity, never wall-clock time.

---

### Task 1: Pure learning overlay

- [x] Add regression tests for reinforcement, forget, and reset.
- [x] Implement capped boost overlay.
- [ ] Add word and n-gram learning signals with diminishing returns and activity decay.
- [ ] Add Room/DataStore persistence and settings UI.
- [ ] Verify complete stage gate and commit.
