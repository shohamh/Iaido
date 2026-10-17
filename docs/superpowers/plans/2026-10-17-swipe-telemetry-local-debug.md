# Swipe Telemetry Local Debug Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Swipe traces from an opted-in device reach the local collector and are browsable/exportable per app version, proven by emulator tests.

**Architecture:** The existing research plane and FastAPI collector are kept. Add version columns/filters on the server, bake the collector URL into the build, surface "unavailable" honestly in Settings, and add an instrumented test that injects swipes and asserts via the operator API.

**Tech Stack:** Kotlin/Android (WorkManager, DataStore), FastAPI + SQLAlchemy + Postgres/MinIO in Docker, Tailscale serve, Gradle, GitHub Actions.

**Spec:** docs/superpowers/specs/2026-10-17-swipe-telemetry-local-debug-design.md

## Global Constraints
- Work on branch `dev`; never touch `main`. Do NOT stage or commit the user's pre-existing uncommitted edits to `AppUpdateClient.kt`, `SettingsActivity.kt` and the untracked `ReleaseKnownNotNewerTest.kt` (stage only files you changed; if you must edit SettingsActivity, stage only your hunks).
- Research and diagnostics planes stay isolated (separate tables/prefixes/models).
- No credentials in code; the collector URL comes only from a Gradle property / env.
- The collector requires HTTPS; never weaken `IngestionBoundaryMiddleware`.
- Untracked `acc_*.log` files at the repo root are not part of this work.
- Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus
- Empty base URL: Settings shows telemetry unavailable and consent cannot be enabled; no crash.
- Batch with unknown/missing app_version: stored as `unknown`, listed, never dropped.
- Export requested without a version: rejected with a clear error.
- Same batch re-uploaded (retry): idempotent, counted once per version.
- Two app versions in one installation: counts and exports don't mix.

---

### Task 1: Diagnose the end-to-end path
**Files:** Create `docs/superpowers/diagnosis/2026-10-17-swipe-telemetry.md`
**Interfaces:** Produces a findings list (each break: symptom, root cause, file:line) consumed by Tasks 3-5.
- [ ] Step 1: Bring up the collector (`telemetry-server` compose, local env vars from its README), publish with `tailscale serve --bg 8000`; record the https URL (`tailscale status --json` DNSName) and verify `POST /v1/installations` returns 201 through it.
- [ ] Step 2: Build a debug APK with `-PiaidoTelemetryBaseUrl=<that url>`, install on `IaidoApi35`, enable research consent via the app's real code path, inject a swipe, and trace where data stops (logcat, WorkManager state, collector logs, operator `GET /v1/operator/research/batches`).
- [ ] Step 3: Write every break found to the diagnosis doc; fix none yet. Commit the doc.

### Task 2: Server version chunking (TDD)
**Files:** Modify `telemetry-server/src/iaido_telemetry/{db.py,app.py,exports.py,fixture_export.py,dashboard.py}`; Test `telemetry-server/tests/test_versioning.py`
**Interfaces:**
- Produces: `app_version: str` and `algorithm_version: str` columns (indexed, default `"unknown"`) on both batch tables, set at ingest from the batch's events; `GET /v1/operator/{plane}/batches?app_version=` filter; `GET /v1/operator/{plane}/versions -> [{app_version, batches, events}]`; `export_plane(..., app_version: str)` and `export_research_fixtures(..., app_version: str)` require the version (ValueError / HTTP 422 if absent); dashboard version picker defaulting to the latest.
- [ ] Step 1: Failing tests: `test_batches_store_app_version`, `test_batches_filter_by_app_version`, `test_versions_endpoint_counts_per_version`, `test_export_requires_app_version`, `test_fixture_export_requires_app_version`, `test_retry_same_batch_counted_once`, `test_missing_version_stored_as_unknown`, `test_dashboard_defaults_to_latest_version`.
- [ ] Step 2: Run `python -m pytest telemetry-server/tests/test_versioning.py -q` -> FAIL.
- [ ] Step 3: Implement; additive column migration at startup in `create_schema()` (add columns if missing, backfill from stored payload else `unknown`).
- [ ] Step 4: Run `python -m pytest telemetry-server/tests -q` -> all PASS. Update the README. Commit.

### Task 3: Bake URL into builds and honest Settings state
**Files:** Modify `app/build.gradle.kts`, `.github/workflows/android-release.yml`, `.github/workflows/android.yml`, `app/src/main/kotlin/com/iaido/app/TelemetrySettingsSection.kt`, `gradle.properties` (commented example only), `telemetry-server/README.md`; Test `app/src/test/kotlin/com/iaido/app/TelemetrySettingsSectionTest.kt`
**Interfaces:** Produces: CI passes `-PiaidoTelemetryBaseUrl="${{ vars.IAIDO_TELEMETRY_BASE_URL }}"`; Settings shows an "unavailable in this build" state when the URL is blank.
- [ ] Step 1: Failing unit tests `blankBaseUrlShowsUnavailableAndDisablesToggles`, `configuredBaseUrlShowsToggles`.
- [ ] Step 2: Implement; `./gradlew :app:testDebugUnitTest --tests '*TelemetrySettingsSectionTest*'` -> PASS.
- [ ] Step 3: Document the owner's one-time steps (set the repo variable, `tailscale serve`) in `telemetry-server/README.md`. Commit.

### Task 4: Fix the breaks found in Task 1
**Files:** as dictated by `docs/superpowers/diagnosis/2026-10-17-swipe-telemetry.md`
**Interfaces:** Consumes the diagnosis list; produces a working swipe -> collector path.
- [ ] Step 1: For each break, write a failing JVM or server test reproducing it.
- [ ] Step 2: Fix; run `./gradlew :app:testDebugUnitTest :core-engine:test` and `python -m pytest telemetry-server/tests -q` -> PASS. Commit per break.

### Task 5: Emulator end-to-end test
**Files:** Create `app/src/androidTest/kotlin/com/iaido/app/SwipeTelemetryE2eTest.kt` (uses the existing `PointerInjector`, `ImeEditorDriver` and the telemetry consent controller); Modify `telemetry-server/README.md` (how to run)
**Interfaces:** Consumes `BuildConfig.IAIDO_TELEMETRY_BASE_URL` and instrumentation arg `iaidoOperatorToken`; returns early (skips) when either is blank.
- [ ] Step 1: Tests `aSwipeWithResearchConsentReachesTheCollectorTaggedWithTheInstalledVersion` (enable RESEARCH consent via the real controller, inject a swipe on the IME, run the upload, poll operator `/v1/operator/research/batches?app_version=<BuildConfig.VERSION_NAME>` until a batch with a swipe trace appears, assert the local queue drained) and `consentOffSendsNothing`.
- [ ] Step 2: Run on `IaidoApi35` against the Docker collector via the tailscale URL (`./gradlew :app:connectedDebugAndroidTest -PiaidoTelemetryBaseUrl=<url> -Pandroid.testInstrumentationRunnerArguments.class=com.iaido.app.SwipeTelemetryE2eTest -Pandroid.testInstrumentationRunnerArguments.iaidoOperatorToken=<token>`) -> PASS; include the output in the report.
- [ ] Step 3: Open the dashboard and confirm the trace is visible under the exact version. Commit and push `dev`.
