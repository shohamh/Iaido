# Iaido APK self-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended) to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings update action that downloads the newest signed Iaido APK from GitHub Releases and hands it to Android's package installer.

**Architecture:** Keep release discovery and version/asset policy in small testable Kotlin types. Put Android-only APK inspection, certificate validation, private-file staging, and installer intent creation behind an `AppUpdateClient`. Let `SettingsActivity` own only UI state and lifecycle-safe coroutine launching. Add a tag-triggered GitHub workflow that publishes a signed `app-release.apk` when signing secrets are configured.

**Tech Stack:** Kotlin, Android Activity/Compose, `PackageManager`, `FileProvider`, `HttpURLConnection`, JUnit 5, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-14-iaido-apk-self-update-design.md`

## Global Constraints

- GitHub Releases is the only update source; release URLs are accepted only from the fixed GitHub API and release download host.
- APK updates require the same package name and signing certificate as the installed app; Android confirmation remains mandatory.
- Downloads are bounded, streamed to private temporary files, validated, and atomically staged.
- Signing credentials stay in GitHub Actions secrets and are never committed.

---

### Task 1: Release metadata policy

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/AppUpdateRelease.kt`
- Test: `app/src/test/kotlin/com/iaido/app/AppUpdateReleaseTest.kt`

**Interfaces:**
- Produces `AppReleaseAsset(name: String, browserDownloadUrl: String, sizeBytes: Long?)`, `AppRelease(tagName: String, assets: List<AppReleaseAsset>)`, and `selectApkAsset(release: AppRelease): AppReleaseAsset`.
- Rejects non-HTTPS URLs, non-GitHub release assets, missing APKs, and ambiguous APK assets.

- [x] **Step 1: Write failing tests** for one valid APK asset, non-APK assets, duplicate APK assets, and untrusted URLs.
- [x] **Step 2: Run `:app:testDebugUnitTest --tests com.iaido.app.AppUpdateReleaseTest` and confirm failure.**
- [x] **Step 3: Implement the immutable metadata types and selector with the fixed GitHub host policy.**
- [x] **Step 4: Run the focused test and confirm it passes.**
- [x] **Step 5: Commit `Add APK release metadata policy`.**

### Task 2: APK archive validation and staging

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/AppUpdateClient.kt`
- Create: `app/src/main/res/xml/app_update_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/iaido/app/AppUpdateClientTest.kt`

**Interfaces:**
- `AppUpdateClient.update(): AppUpdateResult` returns `UpToDate`, `ReadyToInstall(apk: File, versionCode: Long)`, `InstallPermissionRequired`, or `Failed(message: String)`.
- `AppUpdateClient.installIntent(apk: File): Intent` creates the package-installer intent after staging.

- [x] **Step 1: Write failing policy tests** for current-version rejection, newer-version acceptance, oversized download rejection, wrong package rejection, and invalid staging cleanup using injected release/download/package boundaries.
- [x] **Step 2: Run the focused test and confirm failure.**
- [x] **Step 3: Implement fixed API lookup at `https://api.github.com/repos/shohamh/Iaido/releases/latest`, JSON parsing, bounded streaming download, archive package/version inspection, signing-certificate comparison, private temporary staging, and atomic replacement.**
- [x] **Step 4: Add `REQUEST_INSTALL_PACKAGES`, a narrowly scoped `FileProvider`, and `app_update_paths.xml`; keep the provider non-exported with URI grants.**
- [x] **Step 5: Run focused tests and confirm they pass.**
- [x] **Step 6: Commit `Add validated APK updater`.**

### Task 3: Settings update UI

**Files:**
- Modify: `app/src/main/kotlin/com/iaido/app/SettingsActivity.kt`
- Create: `app/src/test/kotlin/com/iaido/app/AppUpdateUiStateTest.kt`

**Interfaces:**
- `AppUpdateUiState` exposes stable user-facing states: idle, checking, downloading, ready, up-to-date, permission-required, and failure.
- Settings launches update work on `Dispatchers.IO`, updates state on the main lifecycle, opens unknown-source settings when needed, and invokes the installer for a staged APK.

- [x] **Step 1: Write failing state-mapping tests** with literal status/button labels for checking, ready-to-install, permission-required, up-to-date, and failure.
- [x] **Step 2: Run the focused test and confirm failure.**
- [x] **Step 3: Implement the state model and add an accessible Update app button/status block in Settings.**
- [x] **Step 4: Wire the button to `AppUpdateClient`, permission settings, and the package installer; prevent duplicate clicks while busy.**
- [x] **Step 5: Run focused tests and compile the app.**
- [x] **Step 6: Commit `Add Settings APK update action`.**

### Task 4: Signed release workflow

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `.github/workflows/android-release.yml`
- Modify: `README.md`

**Interfaces:**
- Tagging `v*` builds and publishes `app-release.apk` from the release variant.
- Local builds remain possible without secrets; CI release builds fail clearly when the required signing secrets are absent.

- [x] **Step 1: Add a signing-config test/documented validation path** that distinguishes unsigned local artifacts from publishable release artifacts.
- [x] **Step 2: Configure release signing from `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` environment variables without committing credentials.**
- [x] **Step 3: Add the tag-triggered workflow to decode `ANDROID_KEYSTORE_BASE64`, assemble the signed release, and create/update the GitHub Release asset.**
- [x] **Step 4: Document required repository secrets and same-key upgrade behavior.**
- [x] **Step 5: Run debug/release builds, manifest inspection, and workflow YAML validation.**
- [x] **Step 6: Commit `Publish signed APK releases for in-app updates`.**

### Task 5: Full verification

**Files:**
- No additional source files.

- [x] **Step 1: Run `:core-engine:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`.**
- [x] **Step 2: Run the Settings/update instrumented coverage on the local emulator where package-installer UI is available; record any Android confirmation boundary that must remain manual.**
- [x] **Step 3: Run `git diff --check`, inspect release/debug merged manifests, and verify the worktree contains only this feature.**
- [x] **Step 4: Review the commit series and report exact verification evidence; do not push or publish without a separate request.**
