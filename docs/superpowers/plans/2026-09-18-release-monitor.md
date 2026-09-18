# Release Monitor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notify users about newer selected-channel Iaido releases and install them from the notification while adapting polling around active GitHub release workflows.

**Architecture:** A persistent one-shot WorkManager worker schedules its next run through a pure polling policy. A GitHub monitor client finds release identities and workflow status, while the existing updater downloads/validates/stages APKs. A notification publisher and installer activity provide the user-facing handoff.

**Tech Stack:** Kotlin, Android WorkManager, Android notifications, Compose Settings, GitHub Releases/Actions REST APIs, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-18-release-monitor-design.md`

## Global Constraints

- Polling intervals are best-effort targets because Android may defer background work.
- Monitor only the channel selected in Settings.
- Never install an APK without the existing package, version, size, and signing-certificate validation.
- Never redownload or repost a notification for the same channel/release/asset identity.
- Keep GitHub credentials out of the APK; use public API endpoints and existing release URL trust checks.
- Preserve the existing manual Settings update flow and dark/light theme behavior.

---

### Task 1: Model polling and release identity

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/ReleasePollingPolicy.kt`
- Create: `app/src/main/kotlin/com/iaido/app/ReleaseMonitorState.kt`
- Test: `app/src/test/kotlin/com/iaido/app/ReleasePollingPolicyTest.kt`

**Interfaces:**
- Produces `ReleasePollingPolicy.nextDelayMs(nowMs: Long, state: ReleaseMonitorState): Long` and `ReleaseMonitorState.startFastPolling(nowMs: Long): ReleaseMonitorState`.
- Produces `ReleaseIdentity(channel: UpdateChannel, tagName: String, releaseId: Long, assetUpdatedAt: String)` for deduplication.

- [ ] **Step 1: Write failing tests** for the 10-minute normal delay, one-minute fast delay, one-hour expiration, workflow completion reset, and equality of identical release identities.
- [ ] **Step 2: Run `./gradlew :app:testDebugUnitTest --tests com.iaido.app.ReleasePollingPolicyTest` and confirm the new symbols fail to compile.**
- [ ] **Step 3: Implement the immutable state and pure policy with named constants for ten minutes, one minute, and one hour.**
- [ ] **Step 4: Re-run the focused tests and confirm they pass.**
- [ ] **Step 5: Commit with `git commit -m "feat: add release polling policy"`.**

### Task 2: Add GitHub monitor metadata and updater staging identity

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/com/iaido/app/GitHubReleaseMonitorClient.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/AppUpdateRelease.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/AppUpdateClient.kt`
- Test: `app/src/test/kotlin/com/iaido/app/GitHubReleaseMonitorClientTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/AppUpdateReleaseTest.kt`

**Interfaces:**
- `GitHubReleaseMonitorClient.latestRelease(channel): ReleaseProbe` returns `Available(identity, versionLabel)` or `Unavailable`.
- `GitHubReleaseMonitorClient.releaseWorkflowStatus(): ReleaseWorkflowStatus` returns `Running`, `Idle`, or `Unknown`.
- `AppUpdateClient.updateIfNew(identity): AppUpdateResult` reuses the existing validation path and avoids redownloading an already staged identity.

- [ ] **Step 1: Add tests for parsing release id, tag, asset update time, version label, workflow `queued`/`in_progress` status, and malformed/404 responses.**
- [ ] **Step 2: Run the focused monitor/release tests and confirm they fail.**
- [ ] **Step 3: Add the WorkManager dependency alias and implement the public GitHub API parser with bounded metadata reads and the existing HTTPS/user-agent policy.**
- [ ] **Step 4: Extend staged update metadata to include version name and release identity without weakening APK validation.**
- [ ] **Step 5: Re-run focused tests and confirm they pass.**
- [ ] **Step 6: Commit with `git commit -m "feat: track release identities for updates"`.**

### Task 3: Schedule background monitoring

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/ReleaseMonitorWorker.kt`
- Create: `app/src/main/kotlin/com/iaido/app/ReleaseMonitorScheduler.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/kotlin/com/iaido/app/ReleaseMonitorStateTest.kt`

**Interfaces:**
- `ReleaseMonitorScheduler.schedule(context, reason)` enqueues a unique one-shot `ReleaseMonitorWorker`.
- `ReleaseMonitorWorker.doWork()` checks the selected channel, probes workflow/release state, stages a new APK, publishes a notification, and schedules the next run.

- [ ] **Step 1: Write tests for persisted selected channel, fast-window expiration, retry-safe scheduling, and no duplicate release handling.**
- [ ] **Step 2: Run the focused tests and confirm they fail.**
- [ ] **Step 3: Implement unique WorkManager scheduling with network constraints and a one-shot delay from `ReleasePollingPolicy`; reschedule after every terminal run.**
- [ ] **Step 4: Start scheduling from `SettingsActivity.onCreate` and on channel selection; declare the worker-compatible application configuration.**
- [ ] **Step 5: Re-run focused tests and assemble the debug APK.**
- [ ] **Step 6: Commit with `git commit -m "feat: schedule background release monitoring"`.**

### Task 4: Publish update notifications and installer actions

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/ReleaseNotificationPublisher.kt`
- Create: `app/src/main/kotlin/com/iaido/app/ReleaseInstallActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/iaido/app/SettingsActivity.kt`
- Test: `app/src/test/kotlin/com/iaido/app/ReleaseNotificationTest.kt`

**Interfaces:**
- `ReleaseNotificationPublisher.publish(context, versionName, channel, apk)` creates the notification with a `Download & install` action.
- `ReleaseInstallActivity` receives a staged update identity, starts `AppUpdateClient.installIntent`, and opens unknown-apps settings when required.

- [ ] **Step 1: Write tests for stable/nightly notification titles, exact version text, action intent extras, and duplicate notification IDs.**
- [ ] **Step 2: Run the focused notification tests and confirm they fail.**
- [ ] **Step 3: Implement the notification channel, immutable/update-current pending intent, Android 13 permission request, and installer activity.**
- [ ] **Step 4: Add a Settings explanation/button for notification permission and ensure manual updates still use the existing UI.**
- [ ] **Step 5: Re-run notification tests and assemble the debug APK.**
- [ ] **Step 6: Commit with `git commit -m "feat: notify users about Iaido updates"`.**

### Task 5: Full verification and push

**Files:**
- Modify only files already listed above if verification exposes a defect.

- [ ] **Step 1: Run `./gradlew :app:testDebugUnitTest :core-engine:test --no-daemon --console=plain`.**
- [ ] **Step 2: Run `./gradlew :app:assembleDebug --no-daemon --console=plain`.**
- [ ] **Step 3: Run `git diff --check` and inspect `git status --short` for only scoped files.**
- [ ] **Step 4: Commit any final scoped fix with a focused message.**
- [ ] **Step 5: Push `git push origin main`.**
- [ ] **Step 6: Verify `git ls-remote origin refs/heads/main` equals `git rev-parse HEAD` and report the GitHub Actions run status separately from the push result.**
