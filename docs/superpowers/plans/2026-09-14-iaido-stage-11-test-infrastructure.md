# Iaido Stage 11: Test Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make CI run JVM tests, Android unit tests, and emulator instrumentation using shared synthetic swipe fixtures.

**Architecture:** The core JVM module publishes a Gradle `testFixtures` variant containing layout/path builders. App instrumentation tests depend on that variant and convert the same path points into Android-facing assertions. GitHub Actions keeps the fast unit job and adds an emulator job; Galaxy S25 execution remains a manual connected-device command.

**Tech Stack:** Gradle Java test fixtures, AndroidX Test runner, GitHub Actions, Android emulator.

**Spec:** `wayfinder/tickets/016-testing-strategy.md`.

## Global Constraints

- Unit tests run on every build without Android dependencies.
- Synthetic swipe fixtures have one source of truth in `core-engine` test fixtures.
- Instrumented tests run through `connectedAndroidTest` on an emulator.
- Physical Galaxy S25 testing remains manual and is not claimed by CI.

---

### Task 1: Shared fixture library and instrumentation smoke test

- [x] Publish `core-engine` test fixtures with deterministic word paths.
- [x] Add app AndroidX instrumentation dependencies and a fixture-consuming test.
- [x] Compile and run the focused JVM/app test tasks.

### Task 2: Emulator CI

- [x] Add an emulator runner job invoking `connectedDebugAndroidTest`.
- [x] Preserve the existing unit/build job.
- [x] Validate YAML and run the local build gate.

### Task 3: Stage gate

- [x] Mark the plan/roadmap complete and commit after unit/build verification.
