# Iaido Telemetry and Research Data Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in diagnostics first, then a separately consented research-data pipeline for bounded touch traces and affected correction examples, without changing keyboard behavior or uploading sensitive data accidentally.

**Architecture:** The Android app owns typed, plane-specific event contracts, consent, bounded app-private queues, crash persistence, and WorkManager uploads. A separate `telemetry-server/` FastAPI service validates diagnostics and research on different endpoints and stores them in separate PostgreSQL/object-storage data planes with independent retention, deletion, and export. Diagnostics never accepts readable text or raw touch paths; research accepts them only after its own consent.

**Tech Stack:** Kotlin, Android DataStore, Android Keystore, WorkManager, Kotlin serialization, existing `HttpURLConnection` transport style, FastAPI, Pydantic, PostgreSQL, S3-compatible object storage, pytest, JUnit 5, and existing Compose/connected IME tests.

**Spec:** `docs/superpowers/specs/2026-09-19-iaido-telemetry-design.md`

## Global Constraints

- Both telemetry planes are disabled by default and independently revocable.
- Diagnostics payloads contain no readable words, candidate strings, host-editor text, clipboard data, raw touch coordinates, raw pointer paths, Android identifiers, or precise location.
- Research data may contain only the bounded affected text span and normalized touch-pointer traces after the separate research confirmation.
- “Motion events” means keyboard touch-pointer `MotionEvent`s; accelerometer, gyroscope, microphone, camera, contacts, clipboard, and host-application telemetry remain excluded.
- Telemetry failures must never block input, recognition, correction, settings, or crash delegation.
- Existing uncommitted changes to `README.md`, `AppUpdateUiState.kt`, and `AppUpdateUiStateTest.kt` must remain untouched.
- No operator secret, database credential, or read credential may be embedded in the APK.
- Diagnostics retention defaults to 90 days; research retention defaults to 365 days; deployment configuration may shorten either value.
- Every task ends with focused tests, `git diff --check`, and one logical commit containing only that task’s files.

## File Map

### Android app

- Create `app/src/main/kotlin/com/iaido/app/TelemetryConsent.kt` for plane-specific consent state, disclosure versions, and DataStore mapping.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryEvent.kt` for sealed diagnostics/research event contracts and common envelopes.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryRedactor.kt` for diagnostics-only field validation and exception/stack redaction.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryQueue.kt` for bounded length-delimited local records, batch rollover, age/size caps, and acknowledgement.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryInstallationStore.kt` for installation identity and Keystore-protected write/deletion credentials.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryTransport.kt` for HTTPS provisioning, batch upload, and plane-specific deletion.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryUploadWorker.kt` for consent-aware WorkManager upload and retry policy.
- Create `app/src/main/kotlin/com/iaido/app/DiagnosticsTelemetry.kt` for semantic diagnostics capture and the bounded ring buffer.
- Create `app/src/main/kotlin/com/iaido/app/TelemetryCrashHandler.kt` for consent-aware crash-envelope persistence and prior-handler delegation.
- Create `app/src/main/kotlin/com/iaido/app/ResearchTraceRecorder.kt` for normalized, quantized, bounded touch traces.
- Create `app/src/main/kotlin/com/iaido/app/ResearchCorrectionRecorder.kt` for affected-span correction events.
- Create `app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt` for consent, disclosure, network policy, queue deletion, and server deletion UI.
- Modify `app/src/main/kotlin/com/iaido/app/KeyboardSettings.kt` to add the independent consent and research-network-policy keys.
- Modify `app/src/main/kotlin/com/iaido/app/SettingsActivity.kt` to host the new privacy section and invoke deletion/queue actions.
- Modify `app/src/main/kotlin/com/iaido/app/IaidoApplication.kt` to initialize the process-safe crash/telemetry runtime and schedule upload work only for enabled planes.
- Modify `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt` to emit semantic diagnostics and correction events and to own research correction recording.
- Modify `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt` to expose immutable touch frames at the raw pointer boundary without retaining `MotionEvent` objects.
- Modify `app/build.gradle.kts` to expose an operator-supplied telemetry base URL as build configuration without embedding credentials.
- Add focused tests beside each new production class under `app/src/test/kotlin/com/iaido/app/`.
- Add focused connected tests under `app/src/androidTest/kotlin/com/iaido/app/` for consent and real IME behavior.

### Telemetry server

- Create `telemetry-server/pyproject.toml` with FastAPI, Pydantic, SQLAlchemy/Alembic, PostgreSQL, boto3-compatible object storage, and pytest dependencies.
- Create `telemetry-server/src/iaido_telemetry/config.py` for environment-only deployment configuration.
- Create `telemetry-server/src/iaido_telemetry/schemas.py` for distinct diagnostics/research request models and bounded field validators.
- Create `telemetry-server/src/iaido_telemetry/auth.py` for installation write/deletion credentials and operator bearer-token checks.
- Create `telemetry-server/src/iaido_telemetry/db.py` for separate diagnostics/research metadata tables and operator audit records.
- Create `telemetry-server/src/iaido_telemetry/storage.py` for separate diagnostics/research object prefixes and deletion.
- Create `telemetry-server/src/iaido_telemetry/app.py` for installation, ingestion, deletion, and health routes.
- Create `telemetry-server/src/iaido_telemetry/retention.py` for retention cleanup jobs.
- Create `telemetry-server/src/iaido_telemetry/exports.py` for plane-explicit NDJSON and reviewed fixture exports.
- Create `telemetry-server/tests/` for endpoint, schema, isolation, deletion, retention, and export tests.
- Create `telemetry-server/docker-compose.yml` for local PostgreSQL and an S3-compatible development store.
- Create `telemetry-server/README.md` for local setup, deployment variables, operator access, retention, and privacy operations.

---

### Task 1: Define independent Android consent and event contracts

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryConsent.kt`
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryEvent.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/KeyboardSettings.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryConsentTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryEventTest.kt`

**Interfaces:**
- Produces `enum class TelemetryPlane { DIAGNOSTICS, RESEARCH }`.
- Produces `data class ConsentRecord(enabled: Boolean, consentVersion: Int, acceptedAtMs: Long?, revokedAtMs: Long?, policyDigest: String?)`.
- Produces `data class TelemetryEnvelope(schemaVersion: Int, eventId: String, batchId: String, installationId: String, sessionId: String, occurredAtMs: Long, appVersion: String, buildType: String, androidApi: Int, eventType: String, payload: JsonObject)`.
- Produces distinct sealed event roots `DiagnosticsEvent` and `ResearchEvent`; do not expose a shared `Map<String, Any?>` event API.
- Produces `DiagnosticsEventCodec.encode(event)` and `DiagnosticsEventCodec.decode(json)`, with decode rejecting unknown text/raw-trace fields before constructing a diagnostics event.

- [ ] **Step 1: Write failing tests for independent defaults and event-plane separation.**

```kotlin
@Test
fun `both planes default to disabled and use different consent keys`() {
    assertEquals(ConsentRecord.disabled(), diagnosticsConsentFromPreferences(emptyPreferences()))
    assertEquals(ConsentRecord.disabled(), researchConsentFromPreferences(emptyPreferences()))
    assertNotEquals(diagnosticsConsentKey.name, researchConsentKey.name)
}

@Test
fun `diagnostics event cannot carry research text or trace fields`() {
    assertThrows(IllegalArgumentException::class.java) {
        DiagnosticsEventCodec.decode("""{"event_type":"gesture_outcome","text":"secret","points":[[1,2]]}""")
    }
}
```

- [ ] **Step 2: Run the focused tests and verify they fail because the contracts do not exist.**

Run from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.TelemetryConsentTest --tests com.iaido.app.TelemetryEventTest --no-daemon
```

Expected: compilation/test failure naming the new types or keys.

- [ ] **Step 3: Add DataStore keys and immutable consent/event types.**

Use separate keys such as `diagnostics_consent_version`, `diagnostics_enabled`, `research_consent_version`, and `research_enabled`. Encode only allowed typed fields in each sealed event subtype. Put common envelope metadata outside payloads and validate schema versions before serialization.

- [ ] **Step 4: Run the focused tests and verify the contracts pass.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.TelemetryConsentTest --tests com.iaido.app.TelemetryEventTest --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit the contract layer.**

```powershell
git add app/src/main/kotlin/com/iaido/app/TelemetryConsent.kt app/src/main/kotlin/com/iaido/app/TelemetryEvent.kt app/src/main/kotlin/com/iaido/app/KeyboardSettings.kt app/src/test/kotlin/com/iaido/app/TelemetryConsentTest.kt app/src/test/kotlin/com/iaido/app/TelemetryEventTest.kt
git commit -m "feat: define independent telemetry consent contracts"
```

### Task 2: Implement diagnostics redaction and bounded local queues

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryRedactor.kt`
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryQueue.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryRedactorTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryQueueTest.kt`

**Interfaces:**
- Produces `fun redactThrowable(throwable: Throwable): RedactedThrowable`.
- Produces `fun validateDiagnostics(event: DiagnosticsEvent): DiagnosticsEvent` that rejects text-like and raw-coordinate fields.
- Produces `class TelemetryQueue(directory: File, clock: () -> Long, limits: QueueLimits)` with `append`, `pendingBatches`, `acknowledge`, `deleteAll`, and `enforceLimits`.
- Produces `data class QueueLimits(maxBatchEvents: Int = 100, maxBatchBytes: Long = 256 * 1024, maxAgeMs: Long = 7 * 24 * 60 * 60 * 1000L, maxQueuedBytes: Long = 5 * 1024 * 1024)`.

- [ ] **Step 1: Write failing redaction tests for stack traces, query strings, words, and raw paths.**

```kotlin
@Test
fun `redacts file paths and text-like exception details`() {
    val result = redactThrowable(IllegalStateException("typed password=secret at C:\\Users\\me\\file.kt"))
    assertFalse(result.stackTrace.contains("secret"))
    assertFalse(result.stackTrace.contains("C:\\Users\\me"))
}

@Test
fun `diagnostic validation rejects raw touch and readable text fields`() {
    assertThrows(IllegalArgumentException::class.java) {
        DiagnosticsEventCodec.decode("""{"event_type":"raw_touch_path","points":[[1,2]]}""")
    }
}
```

- [ ] **Step 2: Write failing queue tests for rollover, acknowledgement, expiry, and oldest-first eviction.**

```kotlin
@Test
fun `queue rolls after 100 events and deletes only acknowledged batch`() {
    val queue = TelemetryQueue(tempDirectory(), clock = { 1_000L }, limits = QueueLimits(maxBatchEvents = 2))
    queue.append(diagnosticEnvelope("one"))
    queue.append(diagnosticEnvelope("two"))
    queue.append(diagnosticEnvelope("three"))
    val batches = queue.pendingBatches()
    assertEquals(2, batches.size)
    queue.acknowledge(batches.first().batchId)
    assertEquals(1, queue.pendingBatches().size)
}
```

- [ ] **Step 3: Implement final-defense redaction and a length-delimited JSON queue.**

Write each record with a length prefix or newline-delimited JSON plus a checksum, roll by event count/size, discard records older than seven days, and enforce the five-megabyte per-plane cap by deleting oldest batches. Never place diagnostics and research files in the same directory.

- [ ] **Step 4: Run focused redaction and queue tests.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.TelemetryRedactorTest --tests com.iaido.app.TelemetryQueueTest --no-daemon
```

Expected: PASS with assertions for all limits and deletion behavior.

- [ ] **Step 5: Commit the local storage layer.**

```powershell
git add app/src/main/kotlin/com/iaido/app/TelemetryRedactor.kt app/src/main/kotlin/com/iaido/app/TelemetryQueue.kt app/src/test/kotlin/com/iaido/app/TelemetryRedactorTest.kt app/src/test/kotlin/com/iaido/app/TelemetryQueueTest.kt
git commit -m "feat: add bounded redacted telemetry queues"
```

### Task 3: Build the first-party server contracts and ingestion API

**Files:**
- Create: `telemetry-server/pyproject.toml`
- Create: `telemetry-server/src/iaido_telemetry/config.py`
- Create: `telemetry-server/src/iaido_telemetry/schemas.py`
- Create: `telemetry-server/src/iaido_telemetry/auth.py`
- Create: `telemetry-server/src/iaido_telemetry/db.py`
- Create: `telemetry-server/src/iaido_telemetry/storage.py`
- Create: `telemetry-server/src/iaido_telemetry/app.py`
- Create: `telemetry-server/docker-compose.yml`
- Create: `telemetry-server/tests/test_ingestion.py`
- Create: `telemetry-server/tests/test_isolation.py`

**Interfaces:**
- `POST /v1/installations` returns `installation_id`, a write credential, and a deletion credential over HTTPS.
- `POST /v1/diagnostics/batches` accepts diagnostics-only batches and returns `{ "batch_id": ..., "accepted": true }`.
- `POST /v1/research/batches` accepts research-only batches and returns the same acknowledgement shape.
- Plane deletion routes remove both metadata and objects and return a stable deletion receipt.
- Operator routes use a bearer token supplied only through server environment configuration.

- [ ] **Step 1: Write failing pytest cases for schema isolation and endpoint authentication.**

```python
def test_diagnostics_rejects_research_text_and_trace(client, diagnostics_headers):
    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={"batch_id": "b1", "events": [{"event_type": "gesture", "text": "secret", "points": []}]},
    )
    assert response.status_code == 422

def test_research_requires_installation_write_credential(client):
    response = client.post("/v1/research/batches", json={"batch_id": "b1", "events": []})
    assert response.status_code == 401
```

- [ ] **Step 2: Run the server tests and verify they fail because the service is absent.**

Run from `telemetry-server`:

```powershell
python -m pytest tests/test_ingestion.py tests/test_isolation.py -q
```

Expected: import/route failures.

- [ ] **Step 3: Implement separate Pydantic models, installation credentials, database tables, and storage prefixes.**

Use separate `DiagnosticsBatch` and `ResearchBatch` models with explicit maximum sizes. Store searchable metadata under separate table names and raw batches under `diagnostics/` and `research/` prefixes. Make batch insertion idempotent on `(installation_id, plane, batch_id)`.

- [ ] **Step 4: Implement installation, ingestion, and plane-specific deletion routes.**

Reject missing/invalid credentials, unknown schema versions, oversized requests, diagnostics payloads containing research fields, and duplicate payloads with conflicting checksums. Return a stable acknowledgement for an already accepted identical batch.

- [ ] **Step 5: Run server tests and verify the API passes.**

```powershell
python -m pytest tests/test_ingestion.py tests/test_isolation.py -q
```

Expected: PASS.

- [ ] **Step 6: Commit the server ingestion foundation.**

```powershell
git add telemetry-server
git commit -m "feat: add separated telemetry ingestion service"
```

### Task 4: Add Android installation credentials, HTTPS transport, and upload worker

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryInstallationStore.kt`
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryTransport.kt`
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryUploadWorker.kt`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryTransportTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryUploadWorkerTest.kt`

**Interfaces:**
- `TelemetryInstallationStore` exposes `getOrCreateInstallation()`, `writeCredential(plane)`, `deletionCredential()`, and `clear()`.
- `TelemetryTransport` exposes `provision()`, `upload(plane, batch)`, and `delete(plane)`.
- `TelemetryUploadWorker` receives a `TelemetryPlane` input and returns `Result.success`, `Result.retry`, or `Result.failure` according to the server response class.

- [ ] **Step 1: Write failing tests for HTTPS-only URLs, credential scope, duplicate acknowledgements, and retry classification.**

```kotlin
@Test
fun `transport rejects non HTTPS telemetry endpoint`() {
    assertThrows(IllegalArgumentException::class.java) {
        TelemetryTransport(URL("http://collector.invalid"), fakeConnectionFactory()).upload(
            TelemetryPlane.DIAGNOSTICS,
            sampleBatch(),
        )
    }
}

@Test
fun `server 429 and 503 retry while schema rejection fails`() {
    assertEquals(UploadDisposition.RETRY, dispositionForResponse(503))
    assertEquals(UploadDisposition.RETRY, dispositionForResponse(429))
    assertEquals(UploadDisposition.DISCARD, dispositionForResponse(422))
}
```

- [ ] **Step 2: Implement Keystore-backed credential storage and endpoint configuration.**

Enable `buildConfig` and expose `BuildConfig.IAIDO_TELEMETRY_BASE_URL` from the `iaidoTelemetryBaseUrl` Gradle property, defaulting to an empty string that disables transport. Generate installation credentials only through the server provisioning call; never add a credential constant to Kotlin or Gradle source.

- [ ] **Step 3: Implement `HttpURLConnection` transport and idempotent batch acknowledgement.**

Require `https`, set bounded connect/read timeouts, send the batch checksum and ID, cap response size, close every stream, and classify 2xx/429/5xx/4xx according to the worker contract.

- [ ] **Step 4: Implement one-shot WorkManager upload with network constraints and exponential backoff.**

Use unique work per plane. The worker must re-read consent before every batch, stop successfully when consent is revoked, acknowledge only after the server response, and schedule no work when the endpoint is empty or credentials are absent.

- [ ] **Step 5: Run focused Android transport/worker tests.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.TelemetryTransportTest --tests com.iaido.app.TelemetryUploadWorkerTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit the Android transport layer.**

```powershell
git add app/src/main/kotlin/com/iaido/app/TelemetryInstallationStore.kt app/src/main/kotlin/com/iaido/app/TelemetryTransport.kt app/src/main/kotlin/com/iaido/app/TelemetryUploadWorker.kt app/build.gradle.kts app/src/test/kotlin/com/iaido/app/TelemetryTransportTest.kt app/src/test/kotlin/com/iaido/app/TelemetryUploadWorkerTest.kt
git commit -m "feat: add consent-aware telemetry transport"
```

### Task 5: Capture diagnostics and recover crashes

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/DiagnosticsTelemetry.kt`
- Create: `app/src/main/kotlin/com/iaido/app/TelemetryCrashHandler.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoApplication.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Test: `app/src/test/kotlin/com/iaido/app/DiagnosticsTelemetryTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryCrashHandlerTest.kt`
- Modify: `app/src/test/kotlin/com/iaido/app/IaidoApplicationTest.kt`

**Interfaces:**
- `DiagnosticsTelemetry.record(event: DiagnosticsEvent)` is a no-op when diagnostics consent is disabled.
- `DiagnosticsTelemetry.flush()` rolls the current ring buffer into the diagnostics queue and schedules diagnostics upload work.
- `TelemetryCrashHandler.install(previous: Thread.UncaughtExceptionHandler?)` writes a bounded crash envelope only when diagnostics consent is enabled, then delegates.

- [ ] **Step 1: Write failing tests for consent-off no-op, semantic event serialization, bounded breadcrumbs, and prior crash-handler delegation.**

```kotlin
@Test
fun `disabled diagnostics never append or schedule`() {
    val runtime = diagnosticsRuntime(consent = ConsentRecord.disabled())
    runtime.record(DiagnosticsEvent.GestureOutcome(kind = "swipe", outcome = "recognized"))
    assertEquals(0, runtime.queue.pendingBatches().size)
    assertFalse(runtime.uploadWasScheduled)
}

@Test
fun `crash handler delegates after writing only when enabled`() {
    val prior = RecordingExceptionHandler()
    val handler = TelemetryCrashHandler(enabled = { true }, crashStore = fakeCrashStore(), prior = prior)
    handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret"))
    assertTrue(prior.received)
    assertFalse(handler.lastWrittenPayload.contains("secret"))
}
```

- [ ] **Step 2: Implement the bounded diagnostics ring buffer and semantic event helpers.**

Expose helpers for app start, IME start/finish, gesture outcome, recognition latency bucket, suggestion action, runtime error code, and crash metadata. Pass only enums, counts, bucket values, and redacted errors into the diagnostics event contract.

- [ ] **Step 3: Install the crash handler in the default application process and preserve the prior handler.**

Initialize it from `IaidoApplication.onCreate` only for `Application.getProcessName() == packageName`, matching the existing release-monitor process guard. Write the crash envelope synchronously to a bounded file, then delegate even when persistence fails.

- [ ] **Step 4: Add service lifecycle and semantic hooks without changing behavior.**

Emit session start/finish around `onStartInputView`/`onFinishInputView`, gesture outcomes around the existing swipe/split/punctuation/command branches, correction actions from the existing suggestion/replacement methods, and runtime failures from existing guarded paths. Do not pass editor strings, candidates, or `MotionEvent` objects to diagnostics.

- [ ] **Step 5: Run diagnostics and application tests.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.DiagnosticsTelemetryTest --tests com.iaido.app.TelemetryCrashHandlerTest --tests com.iaido.app.IaidoApplicationTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit phase-1 capture.**

```powershell
git add app/src/main/kotlin/com/iaido/app/DiagnosticsTelemetry.kt app/src/main/kotlin/com/iaido/app/TelemetryCrashHandler.kt app/src/main/kotlin/com/iaido/app/IaidoApplication.kt app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt app/src/test/kotlin/com/iaido/app/DiagnosticsTelemetryTest.kt app/src/test/kotlin/com/iaido/app/TelemetryCrashHandlerTest.kt app/src/test/kotlin/com/iaido/app/IaidoApplicationTest.kt
git commit -m "feat: capture opt-in diagnostics and crash breadcrumbs"
```

### Task 6: Add the Privacy & diagnostics settings UI and deletion controls

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/SettingsActivity.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetrySettingsSectionTest.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/TelemetryConsentE2eTest.kt`

**Interfaces:**
- The section exposes separate switches labeled `Share anonymous diagnostics` and `Contribute typing research data`.
- `onDiagnosticsChanged(enabled: Boolean)` and `onResearchChanged(enabled: Boolean)` persist consent version, timestamp, and policy digest before scheduling or cancelling work.
- `onDeletePending(plane)` deletes only that plane’s local queue; `onDeleteUploaded(plane)` invokes only that plane’s server deletion route.

- [ ] **Step 1: Write failing UI/state tests for separate toggles, disclosure text, and deletion status.**

```kotlin
@Test
fun `research disclosure names readable text and raw touch traces`() {
    val copy = telemetryDisclosure(TelemetryPlane.RESEARCH)
    assertTrue(copy.contains("readable typing content"))
    assertTrue(copy.contains("raw touch traces"))
}

@Test
fun `diagnostics disclosure explicitly excludes words and raw paths`() {
    val copy = telemetryDisclosure(TelemetryPlane.DIAGNOSTICS)
    assertTrue(copy.contains("does not include words"))
    assertTrue(copy.contains("raw touch paths"))
}
```

- [ ] **Step 2: Implement the section and add it to `SettingsActivity` without changing existing settings state.**

Keep the section below existing Debug/Typing controls, preserve the live preview, and show current queue/deletion status. Research enabling requires a second confirmation dialog; toggling diagnostics never implies research consent.

- [ ] **Step 3: Wire consent transitions to queue deletion and WorkManager scheduling.**

On disable, delete pending files before showing the disabled state. On enable, persist the consent record, provision credentials if needed, and enqueue only the selected plane. If provisioning fails, leave the toggle disabled and show an actionable status.

- [ ] **Step 4: Add connected coverage for the real Settings activity.**

Use the existing UiAutomator/IME harness to enable diagnostics, verify the research switch remains off, disable diagnostics, and assert pending diagnostic data is deleted without changing keyboard settings or editor text.

- [ ] **Step 5: Run focused settings tests.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.TelemetrySettingsSectionTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --tests com.iaido.app.TelemetryConsentE2eTest --no-daemon
```

Expected: JVM PASS; connected test PASS when an emulator/device is available, otherwise report the setup limitation as unverified.

- [ ] **Step 6: Commit the phase-1 consent UI.**

```powershell
git add app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt app/src/main/kotlin/com/iaido/app/SettingsActivity.kt app/src/test/kotlin/com/iaido/app/TelemetrySettingsSectionTest.kt app/src/androidTest/kotlin/com/iaido/app/TelemetryConsentE2eTest.kt
git commit -m "feat: add opt-in telemetry privacy settings"
```

### Task 7: Add server retention, deletion, and operator exports

**Files:**
- Create: `telemetry-server/src/iaido_telemetry/retention.py`
- Create: `telemetry-server/src/iaido_telemetry/exports.py`
- Modify: `telemetry-server/src/iaido_telemetry/app.py`
- Modify: `telemetry-server/src/iaido_telemetry/db.py`
- Modify: `telemetry-server/src/iaido_telemetry/storage.py`
- Create: `telemetry-server/tests/test_deletion_retention_export.py`
- Create: `telemetry-server/README.md`

**Interfaces:**
- `delete_installation_plane(installation_id, plane)` removes metadata and objects and writes a deletion audit record.
- `purge_expired(now)` applies the 90-day diagnostics and 365-day research defaults.
- `export_plane(plane, start, end, output)` requires an operator token and cannot cross data-plane boundaries.

- [ ] **Step 1: Write failing tests for metadata/object deletion, retention, export isolation, and audit records.**

```python
def test_research_deletion_removes_metadata_and_raw_object(storage, db, operator_client):
    receipt = operator_client.delete("/v1/installations/i1/research", headers=operator_headers())
    assert receipt.status_code == 200
    assert storage.exists("research/i1/batch.json.gz") is False
    assert db.research_rows("i1") == []
    assert db.audit_rows(action="delete", plane="research")
```

- [ ] **Step 2: Implement plane-specific deletion and configurable retention.**

Delete database rows and object-storage objects in one operator operation, make repeated deletion idempotent, and ensure diagnostics deletion cannot touch research prefixes or tables.

- [ ] **Step 3: Implement authenticated NDJSON exports and aggregate SQL views.**

Require an explicit plane and time range, write operator audit records, and include only the selected plane in output. Add aggregate views for crash counts, runtime error rates, gesture outcomes, correction-action counts, and latency buckets.

- [ ] **Step 4: Document local deployment and privacy operations.**

Document environment variables for database, object storage, operator token, retention, and telemetry base URL; local Docker Compose startup; migration commands; export; deletion; and how to inspect a sample payload without exposing production data.

- [ ] **Step 5: Run server retention/export tests.**

```powershell
python -m pytest tests/test_deletion_retention_export.py -q
```

Expected: PASS.

- [ ] **Step 6: Commit server lifecycle and analysis tools.**

```powershell
git add telemetry-server/src/iaido_telemetry telemetry-server/tests/test_deletion_retention_export.py telemetry-server/README.md
git commit -m "feat: add telemetry retention deletion and exports"
```

### Task 8: Implement normalized research touch traces

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/ResearchTraceRecorder.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt`
- Test: `app/src/test/kotlin/com/iaido/app/ResearchTraceRecorderTest.kt`
- Test: `app/src/androidTest/kotlin/com/iaido/app/ResearchTouchCaptureE2eTest.kt`

**Interfaces:**
- Produces `data class TouchFrame(action: Int, eventTimeMs: Long, surfaceWidthPx: Float, surfaceHeightPx: Float, pointers: List<TouchPointer>)`.
- Produces `data class TouchPointer(pointerId: Int, xPx: Float, yPx: Float)`.
- `ResearchTraceRecorder.consume(frame: TouchFrame)` records only when research consent is active.
- `ResearchTraceRecorder.finish(classification, language, layoutId): ResearchTrace?` returns a bounded normalized trace or `null` when invalid/over cap.

- [ ] **Step 1: Write failing tests for normalization, quantization, pointer ordering, cancellation, and hard caps.**

```kotlin
@Test
fun `trace stores normalized quantized points and preserves pointer ids`() {
    val recorder = ResearchTraceRecorder(enabled = { true })
    recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 100L, 1000f, 500f, listOf(TouchPointer(4, 250f, 125f))))
    val trace = recorder.finish("swipe", Language.ENGLISH, "qwerty")!!
    assertEquals(0.25f, trace.points.single().x)
    assertEquals(0.25f, trace.points.single().y)
    assertEquals(4, trace.points.single().pointerId)
}

@Test
fun `disabled or oversized traces are dropped`() {
    val recorder = ResearchTraceRecorder(enabled = { false })
    recorder.consume(sampleTouchFrame())
    assertNull(recorder.finish("swipe", Language.ENGLISH, "qwerty"))
}
```

- [ ] **Step 2: Add an immutable touch-frame callback at `KeyboardInputView`’s `pointerInteropFilter`.**

Convert the current `MotionEvent` into `TouchFrame` immediately using local surface dimensions and a copied pointer list. Do not retain the Android `MotionEvent`. Invoke the callback on every raw event only through the service-provided recorder path; the recorder itself must no-op when research is disabled.

- [ ] **Step 3: Implement bounded normalization and trace finalization.**

Normalize x/y into `[0, 1]`, quantize to a documented precision, store event times relative to trace start, preserve pointer IDs and action ordering, and drop traces exceeding duration, point, or byte limits. Include layout/language and algorithm/config version identifiers but no absolute screen or host-window data.

- [ ] **Step 4: Wire trace start/finish to single-finger, split, command, punctuation, cancellation, and backspace branches.**

Only finalizable typing gestures become research traces. Explicitly mark command, punctuation, tap, failed, and cancelled traces so analysis can distinguish them; do not convert a command or backspace trace into a swipe-training example.

- [ ] **Step 5: Run recorder and connected capture tests.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.ResearchTraceRecorderTest --no-daemon
.\gradlew.bat :app:connectedDebugAndroidTest --tests com.iaido.app.ResearchTouchCaptureE2eTest --no-daemon
```

Expected: JVM PASS; connected test produces an inspectable local research fixture only when research consent is enabled.

- [ ] **Step 6: Commit normalized research capture.**

```powershell
git add app/src/main/kotlin/com/iaido/app/ResearchTraceRecorder.kt app/src/main/kotlin/com/iaido/app/KeyboardInputView.kt app/src/test/kotlin/com/iaido/app/ResearchTraceRecorderTest.kt app/src/androidTest/kotlin/com/iaido/app/ResearchTouchCaptureE2eTest.kt
git commit -m "feat: capture bounded opt-in touch traces"
```

### Task 9: Capture affected correction examples and connect the research plane

**Files:**
- Create: `app/src/main/kotlin/com/iaido/app/ResearchCorrectionRecorder.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/TelemetryUploadWorker.kt`
- Modify: `app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt`
- Test: `app/src/test/kotlin/com/iaido/app/ResearchCorrectionRecorderTest.kt`
- Test: `app/src/test/kotlin/com/iaido/app/TelemetryPlaneIsolationTest.kt`

**Interfaces:**
- `ResearchCorrectionRecorder.record(event: CorrectionInput)` accepts the affected source/final span, candidate metadata, action, trace ID, and algorithm version only when research consent is active.
- `ResearchCorrectionRecorder` rejects empty/oversized spans, surrounding sentence context, and unbounded candidate lists.
- `TelemetryUploadWorker` uses research-only Wi-Fi/unmetered constraints when the research policy is selected.

- [ ] **Step 1: Write failing tests for exact-span capture and diagnostics/research isolation.**

```kotlin
@Test
fun `correction record keeps affected span but drops surrounding context`() {
    val record = researchCorrection(source = "teh", final = "the", surroundingContext = "the secret document")
    assertEquals("teh", record.sourceText)
    assertEquals("the", record.finalText)
    assertFalse(record.serialized.contains("secret"))
}

@Test
fun `diagnostics queue never receives research correction text`() {
    val runtime = telemetryRuntime()
    runtime.research.record(sampleCorrection())
    assertTrue(runtime.diagnosticsQueue.pendingBatches().isEmpty())
}
```

- [ ] **Step 2: Implement bounded correction records and text normalization.**

Normalize Unicode, cap each affected source/final span at 64 Unicode code points, cap candidate alternatives at 5 and each candidate at 64 code points, record action enums such as `candidate_selected`, `manual_edit`, `undo`, and `flow_correction`, and never read or store sentence context.

- [ ] **Step 3: Wire correction recording to existing service seams.**

Record the source/final text only in the existing replacement, undo, flow-correction, and manual-edit paths after the affected span is known. Attach the current research trace ID when available; otherwise use a generated correction ID and leave `trace_id` null. Preserve all existing `InputConnection` and correction-history behavior.

- [ ] **Step 4: Connect research queue/upload policy and add a visible Wi-Fi-only control.**

Default research uploads to unmetered Wi-Fi, persist the policy separately from consent, and ensure turning research off deletes its pending queue without touching diagnostics data.

- [ ] **Step 5: Run isolation and focused service tests.**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.ResearchCorrectionRecorderTest --tests com.iaido.app.TelemetryPlaneIsolationTest --no-daemon
```

Expected: PASS, including assertions that diagnostics never contain readable research fields.

- [ ] **Step 6: Commit research correction capture.**

```powershell
git add app/src/main/kotlin/com/iaido/app/ResearchCorrectionRecorder.kt app/src/main/kotlin/com/iaido/app/IaidoInputMethodService.kt app/src/main/kotlin/com/iaido/app/TelemetryUploadWorker.kt app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt app/src/test/kotlin/com/iaido/app/ResearchCorrectionRecorderTest.kt app/src/test/kotlin/com/iaido/app/TelemetryPlaneIsolationTest.kt
git commit -m "feat: add opt-in correction research records"
```

### Task 10: Add reviewed research exports and deterministic test fixtures

**Files:**
- Modify: `telemetry-server/src/iaido_telemetry/exports.py`
- Create: `telemetry-server/src/iaido_telemetry/fixture_export.py`
- Create: `telemetry-server/tests/test_fixture_export.py`
- Create: `docs/research/README.md`
- Create: `docs/research/schema-v1.md`
- Create: `app/src/test/kotlin/com/iaido/app/ResearchFixtureCodecTest.kt`

**Interfaces:**
- `export_research_fixtures(start, end, review_manifest, output_dir)` requires an explicit review manifest and emits only approved records.
- Fixture files contain deterministic IDs, normalized traces, language/layout metadata, bounded correction examples, and expected algorithm assertions.
- Fixture export strips installation IDs, credentials, timestamps unnecessary for replay, and operator-only metadata.

- [ ] **Step 1: Write failing tests for review gating and deterministic output.**

```python
def test_fixture_export_requires_review_manifest(research_db, tmp_path):
    with pytest.raises(ValueError, match="review manifest"):
        export_research_fixtures(research_db, start, end, review_manifest=None, output_dir=tmp_path)

def test_same_reviewed_rows_produce_same_fixture_bytes(research_db, tmp_path):
    first = export_research_fixtures(research_db, start, end, manifest, tmp_path / "a")
    second = export_research_fixtures(research_db, start, end, manifest, tmp_path / "b")
    assert read_bytes(first) == read_bytes(second)
```

- [ ] **Step 2: Implement review-manifest validation and de-identifying fixture export.**

Require an operator-selected record list or approved hash manifest, sort records by stable content hash, replace installation/session IDs with deterministic fixture IDs, and fail when a record violates current trace/text bounds.

- [ ] **Step 3: Add the fixture schema and Kotlin codec tests.**

Define the versioned JSON format consumed by pure core-engine tests. Reject unsupported versions, missing trace/correction fields, invalid normalized coordinates, and spans beyond the documented cap.

- [ ] **Step 4: Document the human review process.**

Document how to inspect a sample, reject sensitive content, approve a manifest, export fixtures, add them to tests, and delete source research records after fixture extraction when retention policy requires it.

- [ ] **Step 5: Run fixture export tests.**

```powershell
python -m pytest tests/test_fixture_export.py -q
.\gradlew.bat :app:testDebugUnitTest --tests com.iaido.app.ResearchFixtureCodecTest --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit reviewed research fixture tooling.**

```powershell
git add telemetry-server/src/iaido_telemetry/exports.py telemetry-server/src/iaido_telemetry/fixture_export.py telemetry-server/tests/test_fixture_export.py docs/research app/src/test/kotlin/com/iaido/app/ResearchFixtureCodecTest.kt
git commit -m "feat: export reviewed research fixtures"
```

### Task 11: Run full verification and stage the rollout

**Files:**
- Modify: `telemetry-server/README.md`
- Modify: `docs/research/README.md`
- Modify: `README.md`
- Test/artifacts: existing Android and server test outputs only

- [ ] **Step 1: Run all pure Kotlin tests.**

```powershell
.\gradlew.bat :core-engine:test :app:testDebugUnitTest --no-daemon
```

Expected: PASS; if a timeout or truncated output occurs, report the suite as unverified rather than passing it by inference.

- [ ] **Step 2: Run server tests and local service checks.**

```powershell
python -m pytest telemetry-server/tests -q
```

Expected: PASS, including ingestion, deletion, retention, and fixture export tests.

- [ ] **Step 3: Assemble the debug APK.**

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon
```

Expected: a successful debug APK build with an empty endpoint default that keeps telemetry unavailable until configured.

- [ ] **Step 4: Run focused connected IME suites.**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

Verify real editor text, selection, gesture outcomes, settings toggles, diagnostics-off behavior, and research-on local sample behavior. A missing or unstable emulator/device leaves connected coverage explicitly unverified.

- [ ] **Step 5: Inspect sample payloads and server deletion manually in staging.**

Enable diagnostics only and confirm no words, candidates, raw paths, host text, or clipboard fields appear. Separately enable research and confirm normalized pointer traces and only the affected correction span appear. Exercise both deletion endpoints and confirm the opposite plane remains intact.

- [ ] **Step 6: Run final diff and repository status checks.**

```powershell
git diff --check
git status --short --branch
git log -12 --oneline --decorate
```

Confirm only the intended telemetry commits and the user’s pre-existing unrelated changes remain. Do not reset, clean, or amend unrelated work.

- [ ] **Step 7: Document rollout status without overstating evidence.**

Record the staging endpoint, schema versions, retention configuration, verified test commands, connected-device availability, and any unverified external-server or physical-device steps before enabling production ingestion.
