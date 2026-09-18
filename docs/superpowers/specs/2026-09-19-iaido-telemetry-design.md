# Iaido Telemetry and Research Data Design

## Goal

Add opt-in diagnostics that make crashes, debug sessions, and aggregate keyboard usage observable, then add a separately consented research-data path for improving swipe recognition and autocorrection from real touch traces and correction outcomes.

The two phases must be independently useful and independently revocable. Diagnostics must never upload readable typed text or raw touch paths. Research data may contain the affected typed and corrected text only after a separate, explicit research consent.

## Scope

### Phase 1: opt-in diagnostics

- Add a Privacy & diagnostics section to Iaido Settings.
- Keep diagnostics disabled by default and do not create an upload queue until the user enables it.
- Record structured, redacted breadcrumbs for app/IME lifecycle, gesture outcomes, correction interactions, performance buckets, input-connection failures, and uncaught crashes.
- Persist a bounded local ring buffer and crash envelope so a crash can be uploaded on the next launch when consent is still enabled.
- Batch events locally and upload them to a first-party HTTPS collector with WorkManager, retry/backoff, idempotency, and strict size/age limits.
- Provide local pending-data deletion and server-side installation-data deletion.
- Provide an authenticated server export/query tool for later analysis; a dashboard is out of scope for the first implementation.

Diagnostics must not include host-editor text, committed words, candidate words, clipboard contents, raw touch coordinates, raw pointer paths, accessibility text, contact information, Android identifiers, or precise location.

### Phase 2: separately consented research dataset

- Add a second, independently disabled consent toggle with a prominent explanation that this may upload readable text from the affected word/span and raw keyboard touch traces.
- Capture touch-pointer `MotionEvent` sequences from the real keyboard surface, including multi-pointer and cancellation behavior, normalized to the keyboard surface and bounded in size.
- Capture correction examples for the affected word/span and the decision that produced the final text, without collecting surrounding editor text by default.
- Store research batches separately from diagnostics in a separate server data plane, with separate retention, permissions, export, and deletion paths.
- Support reviewed export into deterministic fixtures for core-engine and connected-IME regression tests.

“Motion events” means keyboard touch-pointer events only. Accelerometer, gyroscope, microphone, camera, contacts, clipboard, and host-application telemetry are excluded.

## Non-goals

- No account system or user-facing analytics dashboard in the first release.
- No remote feature flags or remote model updates.
- No collection for a data plane while that plane’s consent is disabled.
- No attempt to reconstruct full sentences or host-app documents from telemetry.
- No use of a third-party crash/analytics SDK in the first implementation.

## Architecture

The Android client has one small telemetry runtime with two logical data planes:

1. `DiagnosticsTelemetry` accepts only typed, redacted diagnostic events.
2. `ResearchTelemetry` accepts only research events after the separate research consent is active.

They may share queue, batching, transport, and consent plumbing, but they must use different event validators, storage directories, upload endpoints, server tables/buckets, retention policies, and deletion commands. A diagnostics code path must not be able to add text or raw motion fields to a diagnostics event by accident.

The server is a small first-party HTTPS service, kept as a separate deployable service under `telemetry-server/` in this repository. It exposes write-only device ingestion endpoints and authenticated operator export/query commands. Devices never receive read credentials, and the APK contains no operator secret.

The Android client uses the existing Kotlin/Android stack: DataStore for consent and configuration, app-private files for bounded queues, WorkManager for deferred upload, Android Keystore-backed protection for installation credentials and research queue files, and the existing `HttpURLConnection` style for HTTPS requests. The server uses FastAPI, PostgreSQL, and compressed object storage for batch payloads.

## Consent and privacy model

### Separate consent records

Store separate records for diagnostics and research:

- `enabled`: current choice.
- `consentVersion`: version of the disclosure accepted.
- `acceptedAt` and `revokedAt`: local timestamps.
- `policyDigest`: digest of the displayed data-use disclosure.
- `installationId`: random per-install pseudonymous identifier, never derived from Android ID, serial number, account, or hardware identifiers.

The research toggle must not be presented as a sub-option that can be enabled implicitly by diagnostics. Enabling research requires a second confirmation screen or dialog that explicitly names readable text and raw touch traces. Revoking either consent immediately stops new events for that plane and deletes that plane’s pending local queue.

### Uploaded-data deletion

The first opt-in provisions an installation record over HTTPS. The client stores an opaque write credential and a separate deletion credential in Keystore-protected storage. The write credential can only submit batches for that installation; it cannot read data or invoke deletion. The Settings screen’s “Delete uploaded data” action signs a deletion request for the selected data plane, and the server deletes both searchable metadata and stored batch objects.

If credentials are lost because app data is cleared or the app is uninstalled, server-side retention remains the fallback deletion mechanism. The UI must explain this limitation rather than claim guaranteed recovery of an old installation identity.

### Data catalog shown to users

The diagnostics disclosure lists:

- app version, Android API level, coarse device model bucket, language mode, and random installation/session IDs;
- event types, timing/latency buckets, gesture classification/outcome, candidate-count and score-margin buckets, correction-action types, and redacted crash metadata;
- approximate event timestamps and bounded diagnostic breadcrumbs.

It explicitly states that diagnostics do not include words, sentences, candidate strings, host-app text, clipboard contents, or raw touch paths.

The research disclosure separately lists:

- normalized touch-pointer paths with bounded timing and pointer data;
- keyboard language/layout and algorithm version;
- the affected typed/recognized/final text span and correction decision, capped to a bounded word/span size;
- candidate metadata required for analysis, subject to research-schema validation.

It explicitly states that research data can contain readable typing content and should not be enabled for sensitive typing.

## Android data flow

### Consent-off behavior

When a plane is disabled:

- no event is created for that plane;
- no local queue file is appended;
- no upload work is scheduled for that plane;
- disabling the plane deletes its pending files before returning success.

Normal Android/system crash reporting remains outside Iaido’s control; Iaido does not add an app-level crash payload while diagnostics consent is disabled.

### Diagnostics capture

The application installs a process-level uncaught-exception handler that, when diagnostics consent is active, synchronously writes a bounded crash envelope before delegating to Android’s prior handler. The envelope contains exception class, a redacted stack trace, app/build/runtime metadata, and the last bounded diagnostic breadcrumbs. It does not contain editor text or raw gesture data.

The normal diagnostic logger writes structured JSON events to an app-private ring buffer. Events are emitted at existing semantic seams:

- application and IME process/session start/finish;
- completed, cancelled, rejected, and classified gestures;
- recognition latency and bounded candidate/score-margin metrics;
- suggestion/correction actions as enums and counts, never words;
- input-connection failures and recoverable runtime errors;
- settings/update/runtime failures with stable error codes;
- uncaught crash envelopes.

Every event is validated by a diagnostics schema before enqueueing. Redaction is a final defense, not the primary privacy boundary.

### Research capture

Research capture is attached at the raw touch boundary in `KeyboardInputView` and at the semantic recognition/correction boundaries in `IaidoInputMethodService` and the core-engine adapter.

Each trace records:

- a random trace ID and session ID;
- pointer action, pointer ID, relative event time, and normalized x/y coordinates;
- normalized keyboard surface dimensions and layout/language identifiers;
- multi-pointer ordering, cancellation, and gesture classification;
- algorithm version and relevant bounded settings;
- a hard cap on duration, points per pointer, total bytes, and one trace’s text span.

Coordinates are normalized and quantized before persistence. Absolute screen coordinates, window bounds, device identifiers, and host-app identity are excluded. The recorder must fail closed: if consent, bounds, or schema validation is unavailable, it drops the research event rather than recording an unbounded or ambiguous payload.

Correction records refer only to the affected span. They may include the recognized source, final user-selected/manual text, candidate alternatives, and correction action when the research consent is active. They do not include the sentence tail, editor selection context, or arbitrary extracted text unless a later consent version explicitly expands the catalog.

### Local queue and upload

Each plane has its own app-private queue directory and schema version. Events are appended as length-delimited JSON records and rolled into compressed batches using these limits:

- maximum 100 events per batch;
- maximum 256 KiB compressed batch size;
- maximum 7 days of local age;
- maximum 5 MiB per plane of queued data;
- oldest batches are discarded when the cap is reached.

WorkManager uploads only when the relevant consent is enabled and the network constraint is met. Diagnostics may use any validated network; research defaults to unmetered Wi-Fi and exposes that policy in Settings. A successful response acknowledges a batch ID; retries reuse the same ID and the server treats duplicate submissions as no-ops. Authentication failures disable retries and surface a repair status; transient failures use bounded exponential backoff.

## Server data flow

### Ingestion endpoints

The service exposes:

- `POST /v1/installations` to provision an installation’s write/deletion credentials;
- `POST /v1/diagnostics/batches` for validated diagnostics batches;
- `POST /v1/research/batches` for separately validated research batches;
- `DELETE /v1/installations/{installation_id}/diagnostics` for authenticated diagnostics deletion;
- `DELETE /v1/installations/{installation_id}/research` for authenticated research deletion.

Both ingestion routes enforce HTTPS at the deployment boundary, authenticated installation credentials, schema/version validation, maximum request size, per-install rate limits, and idempotent batch IDs. The research route never shares a database table or object-storage prefix with diagnostics.

### Storage and operator access

Diagnostics searchable metadata is stored in a diagnostics schema/table set. Research metadata is stored separately, and raw research batches are compressed into a research-only object-storage prefix. Operator queries require authenticated server-side credentials and produce audit records containing operator, query, data plane, and time range.

Default retention is 90 days for diagnostics and 365 days for research, configurable downward by deployment configuration. Deletion requests bypass normal retention and remove both metadata and raw objects. Export tools require an explicit data plane and time range so a diagnostics export cannot accidentally include research content.

The initial analysis surface is:

- aggregate SQL views for crash counts, failure rates, gesture outcomes, correction-action rates, and latency buckets;
- an authenticated NDJSON export command;
- a research export command that validates and emits reviewed fixture files for core-engine/IME tests;
- documentation for importing exports into DuckDB or another offline analysis tool.

## Event schemas

All records include `schema_version`, `event_id`, `batch_id`, `installation_id`, `session_id`, `occurred_at`, `app_version`, `build_type`, `android_api`, and `event_type`. The server rejects unknown required fields, invalid bounds, oversized strings, and plane-inappropriate fields.

Diagnostics payloads are limited to enums, booleans, bounded integers, coarse buckets, stable error codes, and redacted exception metadata. Example event types are `app_started`, `ime_session_started`, `gesture_outcome`, `suggestion_action`, `runtime_error`, and `crash`.

Research payloads add `trace_id`, bounded normalized pointer samples, layout/language IDs, algorithm/config versions, and affected-span correction fields. Raw text is accepted only by the research validator and only within explicit length and Unicode normalization limits. Diagnostics and research use distinct Kotlin sealed event types and distinct server validation models so a payload cannot cross planes through a shared generic map.

## Failure handling

- Telemetry failure must never block key input, recognition, correction, settings, or crash delegation.
- Queue I/O runs off the IME main thread except for the bounded synchronous crash write.
- Full queues, malformed events, serialization errors, network errors, and server rejections are counted locally and sampled into diagnostics only when diagnostics consent is active.
- Upload workers are safe to kill and restart; no event is deleted before an acknowledged batch response.
- Server outages degrade to bounded local buffering followed by oldest-first discard.
- Research recorder overhead is measured separately from typing latency and is disabled entirely when research consent is off.

## Verification

### Android unit tests

Cover consent transitions, policy-version changes, installation credential handling, diagnostics redaction, research schema validation, coordinate normalization/quantization, point/time/size caps, queue rollover, duplicate batch IDs, deletion behavior, and crash-envelope writing.

### Server tests

Cover installation provisioning, authentication scope, per-plane schema rejection, diagnostics rejection of text/raw traces, research acceptance of bounded traces/text, idempotent retries, rate/size limits, retention jobs, deletion of metadata and objects, export-plane isolation, and operator audit records.

### Instrumented/E2E tests

Use the existing real IME/E2E gesture harness to verify:

- diagnostics off produces no local or network telemetry;
- diagnostics on records only redacted semantic outcomes;
- research off records no raw trace or text;
- research on records normalized pointer events, multi-pointer cancellation, and the affected correction span;
- disabling either toggle deletes that plane’s pending queue and prevents future uploads;
- telemetry failures do not alter committed editor text, selection, gesture timing contracts, or correction behavior.

Run focused tests first, then the existing core/app JVM suites, debug assembly, connected IME suites where available, server tests, and `git diff --check`. Any connected-device or external-server limitation must remain explicitly reported as unverified.

## Rollout sequence

1. Ship phase 1 behind the diagnostics opt-in, with a development/staging collector and local schema fixtures.
2. Validate queue behavior, crash recovery, deletion, server isolation, and performance on emulator and a physical device before enabling production ingestion.
3. Add aggregate analysis/export and document the first diagnostic dashboards/queries.
4. Implement phase 2 only after phase 1’s consent, deletion, transport, and retention paths are proven.
5. Start research capture in local/staging mode, review actual sample payloads for text and path leakage, then enable production research ingestion.
6. Build a reviewed fixture-extraction workflow before using collected research data to change autocorrect or swipe-scoring behavior.
