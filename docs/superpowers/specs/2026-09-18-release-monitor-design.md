# Iaido Release Monitor Design

## Goal

Notify users when a newer Stable or Nightly Iaido release is available, with a notification action that downloads and installs the signed APK, while checking more frequently during an active Android release workflow.

## Scope

- Monitor the update channel currently selected in Settings.
- Poll GitHub Releases on a best-effort 10-minute cadence.
- When the Android release workflow is queued or running, poll its status every minute for at most one hour, then return to the normal cadence.
- Download and validate a new release once, persist the staged APK metadata, and expose the version name in the notification.
- Let the notification action open the Android installer, or the unknown-apps permission screen when installation permission is missing.
- Request `POST_NOTIFICATIONS` on Android 13+ from the Settings screen and explain when notifications are disabled.

Android may defer background work because of Doze, battery restrictions, or scheduler quotas; the requested intervals are scheduling targets, not hard real-time guarantees.

## Architecture

`ReleaseMonitorWorker` is a persistent one-shot WorkManager worker. After each run it schedules its next one-shot invocation using `ReleasePollingPolicy`: 10 minutes normally, or one minute while a recent `android-release` workflow is queued/running and the one-hour fast-poll window has not expired. The worker uses application context and never depends on an open Settings activity.

`AppUpdateClient` remains responsible for trusted release metadata, APK download, package/signature/version validation, staging, and installer intents. The monitor adds a lightweight release identity check so the same release does not download repeatedly while the user has not installed it. The identity includes channel, release tag, release id, and asset update timestamp.

`GitHubReleaseMonitorClient` reads the selected release endpoint and the public Actions workflow-runs endpoint. It treats a missing Nightly release as a normal unavailable state, preserves stable behavior, and treats workflow API errors as a reason to use the normal polling interval rather than spamming retries.

`ReleaseNotificationPublisher` owns notification channel creation, version/channel text, and the `Download & install` pending intent. The action starts `ReleaseInstallActivity`, which loads the staged APK and starts the existing installer flow. This keeps notification receivers out of package-installer lifecycle code.

## State and failure handling

- Selected channel changes reset the monitor's release identity and schedule a normal poll.
- A newer release is staged once and produces one notification per release identity.
- Repeated polls of the same staged release do not redownload or repost the notification.
- A missing Nightly release is silent during background polling; Settings continues to show the explanatory status when manually requested.
- Network, API, malformed metadata, APK, package, version, or signature failures are logged only as a worker failure result and retried on the normal cadence.
- Notification permission denial does not prevent polling or staging; Settings displays the action needed to enable notifications.

## Verification

- Pure tests cover normal/fast polling transitions, one-hour expiration, release identity deduplication, workflow status parsing, and notification copy.
- Existing updater tests continue to cover trusted URLs, package/version/signature validation, and installer behavior.
- Run the complete app/core JVM suites, assemble the debug APK, run `git diff --check`, and verify the pushed `main` SHA.
