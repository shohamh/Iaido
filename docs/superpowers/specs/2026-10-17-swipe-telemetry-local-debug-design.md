# Swipe telemetry to the local collector, chunked by version

## Intent
The owner wants to debug swipe problems on their PC using real swipe traces from their devices. The opt-in research telemetry plane, the Dockerised collector (`telemetry-server/`) and its dashboard already exist, but "it never worked for me". Make it work end to end, prove it with emulator tests, and make data separable by app version so old, already-fixed bugs don't drive new changes.

## Known causes / facts
- `.github/workflows/android-release.yml` and `android.yml` never pass `-PiaidoTelemetryBaseUrl`, so shipped APKs have `BuildConfig.IAIDO_TELEMETRY_BASE_URL == ""` (telemetry unavailable).
- The collector rejects non-HTTPS (426). It is published to the owner's devices with `tailscale serve` (tailnet-only HTTPS) on laptop `shoham-laptop`.
- Research plane = "swipe data" (`TelemetryPlane.RESEARCH`); its consent is separate from diagnostics and off by default; uploads default to unmetered Wi-Fi.
- Other breaks may exist in trace -> queue -> upload -> server; a diagnosis task finds them.

## Decisions (owner)
- Collector URL is baked into the APK at build time (no Settings field).
- Opt-in stays in Settings (research consent).
- Version chunking: the server stores app_version and algorithm_version per batch as indexed columns; the dashboard defaults to the latest app version with a picker and per-version counts; exports and fixture exports require an explicit app version.
- Executed subagent-driven on branch `dev`; verified on the `IaidoApi35` emulator against the real Docker collector.

## Goals
Swipe on the keyboard with research consent on -> trace reaches the collector -> visible in the dashboard under the app's exact version.

## Non-goals
Settings URL field; changing the Wi-Fi-only policy; new diagnostics-plane features; production hosting.

## Anti-goals
Silent no-op when no URL is configured; mixing versions in an export; leaking research data via the diagnostics plane.

## Builder's calls (mine, not owner-approved)
- URL comes from Gradle property `iaidoTelemetryBaseUrl`, fed in CI from repo variable `IAIDO_TELEMETRY_BASE_URL`, locally from `gradle.properties`/`-P`.
- Existing rows lacking version columns are backfilled from their stored payload where possible, else `unknown`.
- "Latest" version = the one with the most recent received batch.
