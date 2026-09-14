# NinjaKeys Stage 12 Dev Distribution and Release Packaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Stage 12's locally verifiable distribution path reproducible and explicit: package `core-engine` with a checksum, support wireless-ADB APK iteration, expose semantic version metadata, and provide placeholder branding without inventing an unsafe hot-update protocol.

**Architecture:** Gradle owns the reproducible JAR configuration. A repository-root PowerShell packager produces an ignored distribution directory containing the JAR and its SHA-256 manifest. A separate ADB helper handles APK deployment to an explicitly selected device. Runtime `DexClassLoader` updates remain a policy-gated follow-up because the repository has no signed release-manifest schema, trust key, rollback rule, or endpoint contract yet.

**Tech Stack:** Kotlin/JVM, Android/Compose, Gradle, PowerShell, GitHub Releases, ADB.

**Spec:** `wayfinder/tickets/017-dev-distribution-hot-reload.md` and `wayfinder/tickets/019-release-packaging.md`.

## Global Constraints

- `core-engine` remains pure Kotlin with zero Android dependencies.
- The app targets Android 12+ (`minSdk` 31) and changes to the app module still require APK installation.
- GitHub Releases is the near-term distribution channel; Play Store compatibility is explicitly deferred.
- Generated outputs must remain untracked and must never be used as source fixtures.
- Every behavior change is test-first and must have fresh verification evidence before completion claims.

---

### Task 1: Define the packaging contract with a failing verification script

**Files:**
- Create: `tools/test_package_core_engine.ps1`
- Create: `docs/superpowers/plans/2026-09-14-ninjakeys-stage-12-packaging.md`

**Interfaces:**
- Consumes: `tools/package_core_engine.ps1` with an output-directory parameter.
- Produces: a repeatable check that requires `core-engine.jar`, `core-engine.jar.sha256`, and `core-engine-manifest.json`, and proves two package runs have the same SHA-256.

- [x] **Step 1: Write the failing test**

  The test creates two temporary output directories, invokes the existing package script, and asserts the manifest/checksum contract and byte-for-byte hash stability.

- [x] **Step 2: Run test to verify it fails**

  Run: `powershell -ExecutionPolicy Bypass -File tools/test_package_core_engine.ps1`

  Expected: FAIL because the current six-line script only writes `core-engine.jar` and does not write checksum or manifest metadata.

- [x] **Step 3: Commit**

  Commit the test only after confirming the failure is caused by the missing packaging contract.

### Task 2: Implement reproducible packaging and release metadata

**Files:**
- Modify: `core-engine/build.gradle.kts`
- Modify: `tools/package_core_engine.ps1`
- Modify: `.gitignore`
- Modify: `gradle.properties`

**Interfaces:**
- `tools/package_core_engine.ps1 -OutputDirectory <path>` produces `core-engine.jar`, `core-engine.jar.sha256`, and `core-engine-manifest.json`.
- `gradle.properties` supplies `ninjaKeysVersion=0.1.0`; the manifest records the same version.

- [x] **Step 1: Write minimal implementation**

  Configure all JVM JAR tasks with reproducible file ordering and disabled timestamp preservation. Make the script resolve the repository root from `$PSScriptRoot`, run `:core-engine:jar`, copy the artifact, calculate SHA-256, and emit a machine-readable manifest. Ignore `dist/` and `artifacts/`.

- [x] **Step 2: Run the focused test to verify it passes**

  Run: `powershell -ExecutionPolicy Bypass -File tools/test_package_core_engine.ps1`

  Expected: PASS, including equal hashes from independent package runs and a manifest hash matching `Get-FileHash`.

- [x] **Step 3: Commit**

  Commit the reproducible packaging contract and metadata.

### Task 3: Add safe wireless-ADB iteration and placeholder branding

**Files:**
- Create: `tools/deploy_app.ps1`
- Create: `app/src/main/res/drawable/ic_launcher.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- `tools/deploy_app.ps1 -DeviceSerial <serial>` installs the debug APK to the explicitly selected ADB target; `-Build` assembles it first; `-Logcat` streams NinjaKeys logs afterward.
- The manifest uses the placeholder vector icon and the app version comes from `ninjaKeysVersion`.

- [x] **Step 1: Write focused validation**

  Build the debug APK and inspect the merged manifest to prove the icon and version metadata are present. Validate the deploy helper's argument/error paths without requiring a physical device.

- [x] **Step 2: Implement the helper and branding**

  Require an explicit serial when multiple devices are connected, permit a single connected device as a convenience, and never install to an ambiguous target. Use a simple vector mark as the temporary icon.

- [x] **Step 3: Run the Android build verification**

  Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon`

  Expected: exit 0 with the debug APK built and app tests passing.

- [x] **Step 4: Commit**

  Commit the wireless-ADB helper, semantic version wiring, and placeholder icon.

### Task 4: Review the dynamic-update boundary and document the blocker

**Files:**
- Modify: `docs/superpowers/dilemmas.md`
- Modify: `docs/superpowers/plans/2026-09-14-ninjakeys-stages-8-12-infrastructure.md`

**Interfaces:**
- No runtime loader is added until the release endpoint, artifact signature/key trust, version comparison, rollback, and failure fallback rules are specified.

- [x] **Step 1: Verify the missing policy inputs**

  Confirm the repository has no signed manifest schema, public key, or updater API that can be safely consumed by a `DexClassLoader` implementation.

- [x] **Step 2: Record the decision boundary**

  Add the exact missing inputs to `dilemmas.md` and keep only the runtime-update checkbox open in the cross-stage infrastructure plan. The packaged JAR path remains complete and independently verifiable.

- [x] **Step 3: Commit**

  Commit the explicit Stage 12 boundary and user questions.

### Task 5: Complete the verification gate and stage audit

**Files:**
- Modify: stage plan checkboxes only if fresh evidence supports them.

- [x] **Step 1: Run focused packaging verification**

  Run: `powershell -ExecutionPolicy Bypass -File tools/test_package_core_engine.ps1`.

- [x] **Step 2: Run the full repository gate**

  Run: `./gradlew :core-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon`.

- [x] **Step 3: Check the working tree and diff hygiene**

  Run: `git diff --check`, `git status --short`, and inspect generated-output tracking with `git status --ignored --short`.

- [x] **Step 4: Report verified, partial, and blocked items separately**

  Do not call runtime hot updates complete until the missing release policy is supplied and tested.
