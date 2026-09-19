# Iaido

An Android keyboard combining the best of Swype and Nintype gesture-typing, reimagined for modern Android.

## Why “Iaido”?

Iaido (居合道) is a Japanese martial art focused on drawing a sword and attacking an opponent in one swift, controlled motion. We chose the name because we want the keyboard to offer that same swiftness: one deliberate swipe following a clean path to produce a complete word.

### Pronunciation

**Iaido** is pronounced **“ee-eye-doh”** (*i-a-i-dō*).

## Status

Stages 1-12 are implemented on `main`. Stage 12 provides signed GitHub
Release packaging, verified core-engine updates, atomic current/previous
rollback storage, and a compiled fallback when no update is available.

## Development commands

```powershell
.\gradlew.bat :core-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
powershell -ExecutionPolicy Bypass -File tools\package_core_engine.ps1
powershell -ExecutionPolicy Bypass -File tools\deploy_app.ps1 -DeviceSerial <adb-serial> -Build
powershell -ExecutionPolicy Bypass -File tools\deploy_app.ps1 -TailscaleHost as-s25.tail555d2b.ts.net -TailscalePort 5555 -Build
powershell -ExecutionPolicy Bypass -File tools\deploy_app.ps1 -UsbDeviceSerial <usb-adb-serial> -TailscaleHost as-s25.tail555d2b.ts.net -Build
```

The package command writes ignored `dist/` outputs: the core-engine JAR, its
SHA-256 checksum, and a versioned JSON manifest. Wireless ADB pairing is done
once through Android Studio or `adb pair`; subsequent installs use the device
serial passed to `deploy_app.ps1`. For ADB over Tailscale, keep Tailscale
connected on the phone, enable ADB TCP mode on port 5555, and pass its
MagicDNS name with `-TailscaleHost` as shown above. If the phone uses another
ADB port, pass that port with `-TailscalePort`.

For a phone connected by USB with USB debugging enabled, use `-DeviceSerial`
for a direct USB install. To bootstrap ADB TCP mode over USB and then continue
through Tailscale, use `-UsbDeviceSerial` with the phone's USB ADB serial as
shown above; the helper enables port 5555 before connecting to the Tailscale
hostname.

## Telemetry and research data

Iaido has two independently opt-in telemetry planes, both disabled by default and revocable at any
time from Iaido Settings → Privacy & diagnostics:

- **Anonymous diagnostics** — app/IME lifecycle, gesture outcomes, latency buckets, suggestion and
  correction action counts, runtime error codes, and redacted crash metadata. Never words,
  candidate strings, host-editor text, clipboard contents, Android identifiers, or raw touch paths.
- **Typing research data** — normalized keyboard touch traces (pointer ids, relative times,
  quantized coordinates) plus the affected word/span of a correction. This plane can contain
  readable typing content and needs its own confirmation before it can be enabled.

Each plane has a separate consent record, local queue, upload endpoint, server table and object
prefix, retention window (90 days diagnostics, 365 days research by default), and deletion path.
Revoking a plane deletes its pending local queue and stops new capture; "Delete uploaded data"
removes that plane's server-side data. Telemetry failures never block typing, recognition,
correction, or settings.

Builds ship with an empty `BuildConfig.IAIDO_TELEMETRY_BASE_URL`, so nothing is uploaded until a
deployment supplies an HTTPS collector via `-PiaidoTelemetryBaseUrl=https://...`. No operator
secret, database credential, or read credential is embedded in the APK.

The collector is the separate deployable in [`telemetry-server/`](telemetry-server/README.md)
(FastAPI, PostgreSQL, S3-compatible object storage). Reviewed research records can be turned into
deterministic, de-identified regression fixtures — see [`docs/research/README.md`](docs/research/README.md)
and [`docs/research/schema-v1.md`](docs/research/schema-v1.md).

```powershell
python -m pytest telemetry-server/tests -q
```

To run the collector on a development machine and reach it from a device over a real HTTPS
endpoint, follow "Behind a TLS-terminating proxy on the same host" in
[`telemetry-server/README.md`](telemetry-server/README.md). Tailscale Funnel needs no certificate
handling — `tailscale funnel --bg 8000` publishes `https://<host>.<tailnet>.ts.net` to the
loopback-published container — and the Android build then takes
`-PiaidoTelemetryBaseUrl=https://<host>.<tailnet>.ts.net`.

Rollout status: diagnostics capture, research capture (traces plus bounded correction records),
reviewed fixture export, and the collector are implemented. Verified locally by
`:core-engine:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`, the collector's pytest suite,
and `:app:connectedDebugAndroidTest` on an API 35 emulator: 56 instrumented tests run, with both
telemetry suites (`TelemetryConsentE2eTest`, `ResearchTouchCaptureE2eTest`) passing and six
failures in other instrumented suites that reproduce on `main` before this work (bilingual,
inference, and reel gesture fixtures plus the settings update action), so they are not telemetry
regressions. The collector has also been run locally behind Tailscale Funnel and exercised from
the emulator against a real public HTTPS endpoint: the app provisioned an installation and enabled
diagnostics through the funnel, and the ingestion, operator-listing, plane-isolation, and
authorization paths were exercised over that endpoint. Production ingestion stays disabled until a
staging collector is deployed and those pre-existing instrumented failures are addressed.

## Keyboard profile migration

Open Iaido Settings and use **Export profile** to save a versioned,
checksum-protected JSON file through Android's document picker. The file contains
typing settings, command bindings, and learned words/ngrams; it does not contain
the text in the currently focused editor. Use **Import profile** on the new phone
to validate and replace the stored profile. Reopen the keyboard afterward so the
IME service reloads imported learning data.

The same validated snapshot model is used by debug E2E setup. It restores model
state through a monotonic runtime revision and waits until both the input
connection and rendered keyboard acknowledge that revision; Android runtime
handles and in-flight gestures are intentionally never serialized.

## In-app APK updates

Settings can download the newest stable `app-release.apk` from the GitHub
Releases API. The APK must be signed with the same application key as the
installed build; Android then shows its normal install confirmation. The tag
release workflow expects these repository secrets:

If a debug build is installed on a device, uninstall it before installing the
first production release. Android does not allow an update across signing keys;
uninstalling removes app-local settings and learned words.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

For each release, update both `iaidoVersion` and the strictly increasing
`iaidoVersionCode` in `gradle.properties`, then push the matching `v<version>`
tag. The workflow publishes the resulting signed APK as `app-release.apk`.
