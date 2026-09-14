# NinjaKeys Stages 8-12 Infrastructure Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the remaining core seams and development infrastructure for contextual correction, split typing, theming, CI, and packaging.

**Architecture:** Core behavior remains pure Kotlin; Android integration is isolated in `app`; CI and packaging invoke the existing Gradle tasks.

### Stage 8

- [x] Integrate optional n-gram evidence into `GestureRecognizer`.
- [x] Bundle pinned English and Hebrew `wordfreq` small vocabulary assets.

### Stage 9

- [x] Add touch-order split-word merge with a bounded grace window.
- [x] Show a live best-effort split-word preview during the gesture and grace window.

### Stage 10

- [x] Add light/dark theme tokens.

### Stage 11

- [x] Add GitHub Actions unit/build workflow.

### Stage 12

- [x] Add reproducible core-engine JAR packaging script.
- [x] Add signed-manifest verification, semver/minimum-version gates, HTTPS update fetching, ignored local signing key, atomic current/previous storage, and rollback-on-load-failure `DexClassLoader` support.
- [x] Activate a loaded primitive-only ranking entrypoint behind a stable runtime facade; the compile-time engine remains the fallback when no verified update is available.
