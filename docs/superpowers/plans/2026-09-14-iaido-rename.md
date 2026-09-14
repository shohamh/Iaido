# Iaido Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the Android app and its relevant project/source/release references from NinjaKeys to Iaido, including a new `com.iaido.app` install identity.

**Architecture:** Perform a repository-wide semantic rename of current app, package, class, test, script, and documentation identifiers. Keep the GitHub repository URL unchanged because remote repository renaming is an external GitHub operation; update the app identity and release asset naming while preserving that repository endpoint. Use the existing CI secret-based signing path and verify a signed Iaido APK with the local debug keystore as a wiring smoke test.

**Tech Stack:** Kotlin/Android, Gradle, GitHub Actions, PowerShell, JUnit 5, Android instrumentation.

## Global Constraints

- `com.iaido.app` is a new Android application identity; existing `com.ninjakeys.app` installs are intentionally not upgraded.
- User-visible branding uses `Iaido`; technical packages/classes/scripts use `iaido`/`Iaido` consistently.
- `https://github.com/shohamh/Iaido` is the canonical release source after the GitHub repository rename.
- Release APKs must be signed through the existing secret-backed workflow with a stable key; no private key enters Git.

---

### Task 1: Rename source identity and Android manifests

**Files:**
- Modify: `settings.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`
- Rename package directories/files under `app/src/**/kotlin/com/iaido` and `core-engine/src/**/kotlin/com/iaido`
- Modify all Kotlin package/import/class/label references found by the inventory search

- [x] Replace package declarations/imports with `com.iaido.*`, rename `IaidoInputMethodService`/`IaidoTheme` identifiers, set project/application/service/settings labels to Iaido, and set the root project name to Iaido.
- [x] Change `applicationId` and namespace to `com.iaido.app`, update FileProvider authority through `${applicationId}`, and update any debug-only IME class references.
- [x] Run a focused source compile and search for stale production identifiers.
- [x] Commit `Rename Android identity to Iaido`.

### Task 2: Rename tests, tools, docs, and release metadata

**Files:**
- Modify: `app/src/androidTest/**`, `app/src/test/**`, `core-engine/src/test/**`
- Modify: `tools/**`, `.github/workflows/**`, `README.md`, `wayfinder/**`, `docs/superpowers/**`

- [x] Rename test selectors, accessibility descriptions, log filters, artifact paths, helper variables, script output, and documentation branding to Iaido.
- [x] Update release asset names and workflow text while keeping the current GitHub repository URL stable.
- [x] Run a repository-wide case-insensitive stale-name search excluding historical migration notes only when the reference is intentionally about the old app identity; record any deliberate exceptions.
- [x] Commit `Rename project references to Iaido`.

### Task 3: Verify signed Iaido packaging and runtime identity

**Files:**
- Modify: `.github/workflows/android-release.yml` only if signing/release names need correction
- Test: existing app/unit/instrumented suites and APK metadata

- [x] Run the complete core/app unit suite plus debug/release/instrumentation builds.
- [x] Install the debug Iaido APK on the emulator, verify the package is `com.iaido.app`, and run the Settings update-surface test.
- [x] Build a release APK using the local debug keystore as a non-production signing smoke test; verify `app-release.apk` with `apksigner` and confirm one signer.
- [x] Verify the production workflow still requires `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`, with no credentials tracked.
- [x] Run `git diff --check`, inspect status, and commit any final verification-only adjustments.
