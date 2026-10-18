# Diagnosis: swipe (research) telemetry end to end, 2026-10-17

Scope: Iaido keyboard (research plane, `TelemetryPlane.RESEARCH`), then trace capture, queue, WorkManager
upload, then the collector (`telemetry-server/`), then the operator API and dashboard. I did this by
running everything for real: the Docker collector on `shoham-laptop`, published with `tailscale serve`,
and a debug APK on the `IaidoApi35` emulator. Nothing in the product was changed.

## Headline

**With a baked-in URL and a reachable HTTPS collector, the pipeline does work.** A real swipe on the
Iaido keyboard (research consent turned on through the real Settings switch and confirm dialog) was
queued, uploaded by `TelemetryUploadWorker` (`Worker result SUCCESS`), accepted by the collector
(`POST /v1/research/batches 202`), listed by `GET /v1/operator/research/batches`, and drawn on the
dashboard batch page as `gesture_trace - swipe (qwerty, 12 points)` with app `0.1.22`, build `debug`,
API 35. The existing `TelemetryUploadE2eTest` (diagnostics plane) also passes against the same
collector.

The owner's "never worked" comes from the breaks below. B1 alone is enough to explain it for every
shipped APK. B2 would also block every nightly build even once B1 is fixed.

## Breaks

Each entry lists the symptom, root cause, location and how I confirmed it. "Reproduced" means I saw it
on the emulator or collector. "Code-verified" means I traced it in the code but did not reproduce it.

### B1. Shipped APKs have no collector URL, so telemetry is silently impossible (blocking, every release)
- **Symptom:** On a release or nightly APK, the switch does nothing useful. Enabling diagnostics or
  research shows "Could not set up ... telemetry. Check your connection and try again." and flips
  back. Nothing ever reaches a server. No log line explains why.
- **Root cause:** `IAIDO_TELEMETRY_BASE_URL` comes only from the Gradle property
  `iaidoTelemetryBaseUrl` and defaults to `""`. No CI workflow passes it, so
  `TelemetryTransport(URL(""))` throws and `runCatching` turns it into `null`. Provisioning then
  returns `DISCARD`. The UI shows this as a connection problem rather than "not configured".
- **Where:**
  - `app/build.gradle.kts:17` (`getOrElse("")`) and `:52-56` (buildConfigField)
  - `.github/workflows/android-release.yml:65-68` (`assembleRelease` without `-PiaidoTelemetryBaseUrl`)
  - `.github/workflows/android.yml:19` (same)
  - `app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt:156-158` (transport becomes null)
    and `:173-175` (`transport?.provision() ?: DISCARD`), message at `:121-127`
  - `TelemetryUploadWorker.kt:130-133`: `shouldScheduleTelemetry` quietly returns false for a blank
    URL (this is the "silent no-op" anti-goal in the spec)
- **Evidence:** Code-verified. The spec states the CI fact. With `-PiaidoTelemetryBaseUrl` set, the
  same code path provisions (`POST /v1/installations 201`).

### B2. Nightly versions are rejected by the collector, which blocks the queue permanently (blocking, every nightly)
- **Symptom:** A nightly build named `0.1.22-nightly.150` gets `422` on every batch.
- **Root cause:** The server's `AppVersion` requires `^\d{1,4}\.\d{1,4}\.\d{1,4}$` with max length 14.
  Release CI sets `IAIDO_VERSION=${base_version}-nightly.${GITHUB_RUN_NUMBER}` for nightlies. The
  client sends `BuildConfig.VERSION_NAME` unchanged. For the client, 422 means `DISCARD`, and the
  worker then returns `FAILURE` without acknowledging the batch (see B4).
- **Where:**
  - `telemetry-server/src/iaido_telemetry/schemas.py:32-35` (`AppVersion`), used at `:199`
  - `.github/workflows/android-release.yml:51` (nightly version format)
  - `ResearchCorrectionRecorder.kt:213` and `DiagnosticsTelemetry.kt` `buildEnvelope`
    (`appVersion = BuildConfig.VERSION_NAME`)
  - `TelemetryTransport.kt:24-28` (4xx becomes `DISCARD`)
- **Evidence:** Reproduced. I posted the server fixture with `app_version: "0.1.22-nightly.150"`
  through the tailnet URL and got `422 string_too_long ... max_length 14`. This also affects the
  version-chunking goal: the nightly suffix is exactly what must be kept, so the server has to accept
  it rather than the client stripping it.

### B3. Missing or lost credentials are replaced by fabricated local ones, giving a permanent 401 (blocking once hit; real devices)
- **Symptom:** Research consent is on and swipes are queued, but every upload gets `401 Unauthorized`.
  The worker reports `FAILURE` and the batch stays queued. Turning the switch off and on does not help.
- **Root cause:** Both envelope builders call
  `TelemetryInstallationStore.getOrCreateInstallation { random UUIDs }`. If the store is empty when an
  event is recorded, they **persist** a made-up installation id and write credential. After that,
  `TelemetryTransport.provision()` calls `getOrCreateInstallation` and gets the fake back, so it never
  contacts the server and reports `ACKNOWLEDGED`. Uploads then use the fake bearer, the server returns
  401, the client maps that to `DISCARD`, and the worker returns `FAILURE`. The store can be empty
  while consent is on in several ways:
  - `AndroidKeystoreCredentialStorage.read()` clears itself when decryption fails (`:61-70`). This
    happens whenever the Keystore key is gone.
  - `android:allowBackup="true"` restores the DataStore (consent stays on) and the encrypted prefs,
    but not the Keystore key on a new device or reinstall. Decryption fails, the store clears, and the
    next event fabricates credentials.
  - Consent can be set without provisioning. `ResearchTouchCaptureE2eTest.setResearchConsent` writes
    the pref directly, and there is the race between `persistConsent` and `provisionCredentials` in
    `TelemetryConsentController.enable`.
- **Where:**
  - `app/src/main/kotlin/com/iaido/app/ResearchCorrectionRecorder.kt:192-201`
  - `app/src/main/kotlin/com/iaido/app/DiagnosticsTelemetry.kt:277-286`
  - `TelemetryInstallationStore.kt:37-41` (create-if-absent persists whatever the lambda returns)
  - `TelemetryTransport.kt:44-53` (provision short-circuits on any stored installation)
  - `TelemetrySettingsSection.kt:105-116` (consent persisted before provisioning)
  - `app/src/main/AndroidManifest.xml:10` (`allowBackup="true"`)
- **Evidence:** Reproduced. With research consent on and the app provisioned, I deleted
  `shared_prefs/telemetry-installation.xml` and swiped. A new `telemetry-installation.xml` appeared,
  the collector logged `POST /v1/research/batches 401`, logcat showed
  `Worker result FAILURE ... TelemetryUploadWorker`, and the batch file remained in
  `files/telemetry/research/`.

### B4. One rejected batch blocks the whole queue forever (amplifies B2, B3, B5)
- **Symptom:** After a single 4xx or oversize rejection, nothing on that plane ever uploads again.
- **Root cause:** `TelemetryUploadRunner.run` goes through batches oldest first and returns `FAILURE`
  on the first `DISCARD`, without acknowledging or dropping it. The next scheduled run (KEEP policy;
  a failed unique work is replaced) hits the same oldest batch again. The only way out is age-based
  eviction after 7 days (`QueueLimits.maxAgeMs`) or the user pressing "Delete pending data".
- **Where:** `app/src/main/kotlin/com/iaido/app/TelemetryUploadWorker.kt:36-42`,
  `TelemetryTransport.kt:24-28`, `TelemetryQueue.kt:22-26`.
- **Evidence:** Seen together with B3 (batch still queued after `FAILURE`); the rest is code-verified.

### B5. The batch size cap measures compressed bytes, but the transport and server cap uncompressed bytes (likely on real use; Wi-Fi-only makes it worse)
- **Symptom:** After a period of swiping without unmetered Wi-Fi (research uploads default to Wi-Fi
  only), the accumulated batch is never sent, and B4 then blocks everything after it.
- **Root cause:** `TelemetryQueue.append` keeps up to 100 events in a batch as long as the **gzip**
  size is at most 256 KiB. `TelemetryTransport.upload` returns `DISCARD` when the **uncompressed**
  JSON is over 256 KiB, and the server enforces the same uncompressed limit with 413. A swipe trace
  is about 70 bytes per point, so 30-60 points is about 2-4 KB. Somewhere around 60-100 queued
  swipes, the uncompressed body passes 256 KiB while the compressed size is still well under.
- **Where:** `TelemetryQueue.kt:22-26,64-71` (compressed-size check), `TelemetryTransport.kt:70-72`
  (uncompressed check returns `DISCARD`), `telemetry-server/src/iaido_telemetry/app.py:163-164,190-197`
  (413).
- **Evidence:** Code-verified only (not reproduced).

### B6. Events appended while an upload is in flight are deleted unsent (silent data loss)
- **Symptom:** Some swipes made while an upload is running never reach the server.
- **Root cause:** `append` writes into the **last** batch file, which can be the one the worker is
  currently sending. `acknowledge(batchId)` then deletes that file by name, including events added
  after the upload body was built. Nothing coordinates the IME-thread append with the worker's
  read-upload-ack sequence.
- **Where:** `TelemetryQueue.kt:56-71` (append to last file), `:87-90` (acknowledge deletes the file),
  `TelemetryUploadWorker.kt:82-86`.
- **Evidence:** Code-verified only.

### B7. The local collector stack does not start as documented (blocks the owner's PC setup)
- **Symptom:** `docker compose up --build` fails with
  `quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z ... 401 UNAUTHORIZED`.
- **Root cause:** MinIO images can no longer be pulled. `quay.io/minio/*` returns 401 (also for
  `:latest`), and `docker.io/minio/minio` and `minio/mc` return "repository does not exist"
  (`bitnami/minio` is not found either).
- **Where:** `telemetry-server/docker-compose.yml:18` (minio image), `:33` (mc image), and the
  `minio-init` command at `:41-45`, which uses `mc`.
- **Workaround used (works):** SeaweedFS S3 (`chrislusf/seaweedfs:4.48`, anonymous S3) plus
  `amazon/aws-cli:2.27.50` for bucket creation (override below). `S3ObjectStorage.put_if_absent`
  with `IfNoneMatch="*"` works against it: a replay of the same batch returned 202, idempotent.

### B8. The documented way to reach the collector is broken, or public (docs)
- **Symptom:** Plain `docker compose up` cannot be reached from the host (only `expose`). The README
  says to add an override by hand, and then recommends `tailscale funnel`, which is **public internet**.
- **Root cause / where:** `telemetry-server/README.md:100-109` (override snippet and
  `tailscale funnel --bg 8000`). No override file is checked in. The tailnet-only command is
  `tailscale serve --bg 8000`.
- **Related environment fact:** Docker Desktop on this laptop reported
  `the path "C:\Users\Shoham\workspace\ninjakeys\telemetry-server" is not shared from the host` for
  the compose bind mount `.:/service` (`docker-compose.yml:81`). This persisted even after the owner
  said file sharing was allowed (it may need a Docker Desktop apply or restart). Workaround: bake the
  source into an image (override below; no hot reload).

### B9. The tailnet hostname does not resolve to the tailnet inside the emulator (test environment; real phones need Tailscale)
- **Symptom:** In the emulator, `shoham-laptop.tail555d2b.ts.net` resolves to `176.58.90.46`, a
  public Tailscale Funnel ingress address (this node had Funnel on before this session). That address
  does not reach `tailscale serve`.
- **Root cause:** The emulator uses the host's normal upstream DNS, not Tailscale MagicDNS (Windows
  NRPT rules do not apply to it). `-dns-server 100.100.100.100` resolves the tailnet name, but quad-100
  on this host returns SERVFAIL for public names. Android network validation then fails (Wi-Fi without
  `VALIDATED`), and WorkManager logs `NetworkState(isConnected=false, isValidated=false)` and
  `Constraints not met`, so uploads never run. `-dns-server 100.100.100.100,8.8.8.8` gives validation
  but resolves the name to the public Funnel IP again.
- **What works:** Keep the default emulator DNS, `adb root`, then bind-mount a hosts file that maps the
  name to the laptop's Tailscale IP `100.97.128.31`. The emulator can reach `100.97.128.31` through
  the host's Tailscale route, and TLS still validates because the hostname matches the cert. Commands
  are below. For real phones: the phone must be on the tailnet (Tailscale app running) for
  `tailscale serve` to be reachable.

### Other observations (not breaks)
- **No observability:** every telemetry failure is swallowed (`catch (_: Exception)`) with no log
  line, so neither the owner nor logcat can tell why nothing arrives. Recommend a debug-only log tag
  for provision, schedule and upload outcomes.
- **Main-thread disk work:** `ResearchCorrectionRecorderProvider.recordTrace` runs on the IME main
  thread once per gesture. It does a queue read, gzip and write plus a Keystore decrypt
  (`ResearchCorrectionRecorder.kt:141-151,185-201`, via `IaidoInputMethodService.kt:1571-1578`).
  This is a performance risk rather than a functional break.
- **Taps are also traces:** `KeyboardInputView.kt:459-545` finishes a trace for every gesture
  (tap, flick, backspace and so on), so research volume is about one envelope per keystroke. This is
  relevant to B5.
- Research batches are not tagged by version on the server yet (`OperatorBatch` has no app_version
  or algorithm_version columns). That is planned work, not a break.

## Working setup (reuse in later tasks)

All credentials are throwaway local dev values from the README.

### Collector
Local-only override (not committed), `compose.local.yml`:

```yaml
services:
  minio:                                   # name kept so depends_on/env in the base file still work
    image: chrislusf/seaweedfs:4.48
    entrypoint: ["weed"]
    command: ["server", "-dir=/data", "-s3", "-s3.port=9000", "-ip.bind=0.0.0.0"]
    environment: {}
    healthcheck:
      test: ["CMD-SHELL", "nc -z localhost 9000"]
      interval: 5s
      timeout: 5s
      retries: 24
  minio-init:
    image: amazon/aws-cli:2.27.50
    entrypoint: /bin/sh
    environment:
      AWS_ACCESS_KEY_ID: ${IAIDO_S3_ACCESS_KEY}
      AWS_SECRET_ACCESS_KEY: ${IAIDO_S3_SECRET_KEY}
      AWS_DEFAULT_REGION: us-east-1
    command:
      - -ec
      - >-
        for i in $$(seq 1 30); do aws --endpoint-url http://minio:9000 s3api head-bucket --bucket iaido-telemetry && exit 0;
        aws --endpoint-url http://minio:9000 s3 mb s3://iaido-telemetry && exit 0; sleep 2; done; exit 1
  telemetry:
    ports:
      - "127.0.0.1:8000:8000"
    # only while Docker Desktop refuses the bind mount; drop these 5 lines to get hot reload back
    build:
      context: C:/Users/Shoham/workspace/ninjakeys/telemetry-server
      dockerfile: <abs path>/telemetry.Dockerfile   # FROM python:3.13-slim / WORKDIR /service / COPY . /service / RUN pip install --no-cache-dir -e .
    image: iaido-telemetry-local:dev
    volumes: !reset []
```

Start (Git Bash):

```bash
cd /c/Users/Shoham/workspace/ninjakeys/telemetry-server
export IAIDO_POSTGRES_PASSWORD=local-dev-password IAIDO_S3_ACCESS_KEY=local-dev-access-key \
       IAIDO_S3_SECRET_KEY=local-dev-secret-key IAIDO_OPERATOR_TOKEN=local-dev-operator-token
docker compose -p iaido-telemetry -f docker-compose.yml -f <path>/compose.local.yml up -d --build
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8000/v1/installations   # 426 (plain HTTP refused, expected)
tailscale serve --bg 8000            # tailnet-only HTTPS; never `tailscale funnel`
```

- **Collector URL:** `https://shoham-laptop.tail555d2b.ts.net` (Tailscale `Self.DNSName`; laptop
  Tailscale IP `100.97.128.31`).
- **Operator token:** `local-dev-operator-token`. Dashboard: Basic `iaido` / `local-dev-operator-token`.
- **Checks:**
  - `curl -X POST https://shoham-laptop.tail555d2b.ts.net/v1/installations` returns `201`
  - `curl -H 'Authorization: Bearer local-dev-operator-token' https://shoham-laptop.tail555d2b.ts.net/v1/operator/research/batches`
  - logs: `docker compose -p iaido-telemetry ... logs -f telemetry`
- Note: running `tailscale serve --bg 8000` replaced this node's previous serve config, which was a
  **public Funnel** on `/` to `http://127.0.0.1:5173` (another project's dev server). It is now
  tailnet-only to `:8000`. `tailscale serve --https=443 off` removes ours.

### App build / install
```bash
cd /c/Users/Shoham/workspace/ninjakeys
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PiaidoTelemetryBaseUrl=https://shoham-laptop.tail555d2b.ts.net
A=/c/Users/Shoham/AppData/Local/Android/Sdk/platform-tools/adb.exe
$A install -r -t app/build/outputs/apk/debug/app-debug.apk
$A install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```
(Debug versionName comes from `gradle.properties` `iaidoVersion=0.1.22`; the debug app id is also
`com.iaido.app`.)

### Emulator (tailnet name resolution, B9)
```bash
/c/Users/Shoham/AppData/Local/Android/Sdk/emulator/emulator.exe -avd IaidoApi35 -no-snapshot-save -no-boot-anim &   # default DNS
$A wait-for-device; $A root          # userdebug image: root is available
MSYS_NO_PATHCONV=1 $A shell 'cat /system/etc/hosts > /data/local/tmp/hosts;
  echo "100.97.128.31 shoham-laptop.tail555d2b.ts.net" >> /data/local/tmp/hosts;
  chmod 644 /data/local/tmp/hosts; chcon u:object_r:system_file:s0 /data/local/tmp/hosts;
  mount -o bind /data/local/tmp/hosts /system/etc/hosts'     # repeat after every emulator boot
```
Wi-Fi stays `VALIDATED` and unmetered, so the research Wi-Fi-only constraint is satisfied.

### Driving the path
- Existing diagnostics E2E:
  `$A shell am instrument -w -e class com.iaido.app.TelemetryUploadE2eTest com.iaido.app.test/androidx.test.runner.AndroidJUnitRunner`
  gives `OK (1 test)` and the collector logs `POST /v1/diagnostics/batches 202`.
- Research by hand:
  1. Open Settings (`am start -n com.iaido.app/.SettingsActivity`).
  2. Scroll to "Contribute typing research data" and tap the switch, then "Enable".
  3. Run `ime enable` and `ime set com.iaido.app/.IaidoInputMethodService`.
  4. Tap "Live preview" (540,430).
  5. Swipe "hello" with `input motionevent DOWN 646 2010; MOVE ... 275 1904 ... 964 2010 ...; UP 911 1904`
     (key centres at 1080x2400 with number row: h(646,2010) e(275,1904) l(964,2010) o(911,1904)).
  6. Watch for `POST /v1/research/batches 202` and an empty `files/telemetry/research/` (root shell).
- Note: `am force-stop com.iaido.app` makes Android fall back to Gboard, so run `ime set` again.
- At the end of this task I ran `pm clear com.iaido.app`, which removed the fabricated B3 credentials
  and reset consent.
