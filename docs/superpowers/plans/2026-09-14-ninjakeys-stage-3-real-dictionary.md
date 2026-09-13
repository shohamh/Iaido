# NinjaKeys Stage 3: Real English Dictionary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the three-word Stage 2 dictionary with a reproducibly generated, bundled English frequency asset loaded through a testable repository and used by the recognizer.

**Architecture:** A small build-tool script converts the CC BY-SA wordfreq JSON export into normalized CSV. The Android app bundles the generated asset and loads it lazily through a pure Kotlin repository boundary, while the existing recognizer continues to receive `List<WordEntry>` and uses the real frequencies. No network access occurs at runtime.

**Tech Stack:** Kotlin/JVM, Android resources, Python build tooling, JUnit 5.

**Spec:** `wayfinder/tickets/002-dictionary-research.md` and Stage 3 in `docs/superpowers/plans/2026-09-13-ninjakeys-roadmap.md`.

## Global Constraints

- Keep `core-engine` pure Kotlin with zero Android dependencies.
- Keep the generated English asset at 24,000+ alphabetic entries and preserve frequency ordering.
- Do not add raw source data or build caches to git.
- Add a genuine 3+ letter cornered-path scorer regression before trusting the corner bonus.

---

### Task 1: Dictionary asset conversion and metadata

**Files:** Create `tools/build_dictionary.py`, `app/src/main/assets/dictionary/en.csv`, `app/src/main/assets/dictionary/README.md`; modify `.gitignore` only if needed.

- [ ] Write converter tests or executable validation for JSON-to-CSV normalization.
- [ ] Download/convert the pinned wordfreq export into UTF-8 `word,frequency` rows, filtering alphabetic lowercase words and converting log-frequency with `exp`.
- [ ] Validate count, ordering, duplicate absence, and representative words; record source/license/commit in asset metadata.
- [ ] Commit the generated asset and converter.

### Task 2: Repository boundary and app wiring

**Files:** Create `app/src/main/kotlin/com/ninjakeys/app/EnglishDictionaryRepository.kt` and tests; modify `StageOneDictionary.kt`, `NinjaKeysInputMethodService.kt`.

- [ ] Write failing tests for loading rows, skipping malformed rows, and caching the immutable result.
- [ ] Implement the repository with an injected text loader so JVM tests do not require Android.
- [ ] Wire the service to load the bundled asset once and feed it to `SwipeCommitController`.
- [ ] Verify focused tests, core tests, and debug assembly.
- [ ] Commit the verified stage.

### Task 3: Corner-bonus regression and scoring verification

**Files:** Modify `core-engine/src/test/kotlin/com/ninjakeys/core/recognition/ShapePathScorerTest.kt`.

- [ ] Add a 3+ letter path with a real direction change and assert the intended cornered candidate outranks a same-length decoy.
- [ ] Run the focused scorer tests and the complete JVM suite.
- [ ] Commit the regression separately.
