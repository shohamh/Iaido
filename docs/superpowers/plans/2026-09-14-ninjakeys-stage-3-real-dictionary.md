# NinjaKeys Stage 3: Real English Dictionary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Stage 2 fixture with a reproducible, verified English frequency pipeline and a production dictionary boundary used by recognition.

**Architecture:** A pinned upstream wordfreq export is checked into `tools/data` with attribution. A tested converter produces a normalized CSV asset containing only words the letter-only engine can represent. The app loads that asset through a cached repository; the recognizer receives a validated immutable snapshot, so runtime has no network or Python dependency.

**Tech Stack:** Python 3 standard library, Kotlin/JVM, Android assets, JUnit 5, Gradle 9.7.1.

**Spec:** `wayfinder/tickets/002-dictionary-research.md` and Stage 3 in `docs/superpowers/plans/2026-09-13-ninjakeys-roadmap.md`.

## Global Constraints

- `core-engine` remains pure Kotlin with zero Android dependencies.
- The runtime dictionary is on-device and immutable; no network access is allowed.
- The source snapshot, source URL, license, and generated row-count/checksum are documented.
- The generated asset contains at least 24,000 unique lowercase alphabetic words in descending frequency order.
- The scorer has a genuine multi-letter corner regression.

---

### Task 1: Pin and verify source data

**Files:** Create `tools/data/wordfreq-en-25000-log.json`, `tools/data/README.md`.

- [ ] Check in the exact upstream export used to generate the asset.
- [ ] Record upstream URL, license, retrieval date, and SHA-256 checksum.
- [ ] Verify the source parses and contains at least 25,000 rows.

### Task 2: Tested deterministic conversion

**Files:** Modify `tools/build_dictionary.py`; create `tools/test_build_dictionary.py`.

- [ ] Write failing Python tests for filtering, numeric conversion, sorting, duplicate handling, and the 24k minimum.
- [ ] Run the tests and observe the expected failure before implementation changes.
- [ ] Implement the converter and a `--verify` mode that checks generated output invariants.
- [ ] Run the focused tests and regenerate `app/src/main/assets/dictionary/en.csv`.

### Task 3: Runtime repository contract

**Files:** Modify `EnglishDictionaryRepository.kt` and its tests; modify service wiring.

- [ ] Test malformed rows, invalid frequencies, immutable caching, and representative real words.
- [ ] Implement the cached repository and wire the generated asset without a Stage 2 fallback.
- [ ] Run app focused tests, core tests, and debug assembly.

### Task 4: Scorer regression and stage gate

**Files:** Modify `ShapePathScorerTest.kt`; create `tools/verify_dictionary.ps1`.

- [ ] Verify the corner branch with a 3+ letter path.
- [ ] Run converter tests, dictionary verification, all JVM tests, app unit tests, assembly, and `git diff --check`.
- [ ] Commit only after every gate is green and record exact evidence.
