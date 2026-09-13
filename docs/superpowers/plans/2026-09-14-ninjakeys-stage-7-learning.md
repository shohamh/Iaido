# NinjaKeys Stage 7: Personal Dictionary and Learning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a capped, on-device personal dictionary overlay with reinforce, forget, and reset operations.

**Architecture:** `PersonalDictionary` keeps immutable base entries separate from mutable boosts and returns merged snapshots to the recognizer. Android persistence can adopt Room behind this boundary later.

**Spec:** `wayfinder/tickets/003-personal-dictionary-learning-model.md`.

### Task 1: Pure learning overlay

- [x] Add regression tests for reinforcement, forget, and reset.
- [x] Implement capped boost overlay.
- [ ] Add Room/DataStore persistence and settings UI.
- [ ] Verify complete stage gate and commit.
