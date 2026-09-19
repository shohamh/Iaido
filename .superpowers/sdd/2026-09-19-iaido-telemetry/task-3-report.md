# Task 3 report: first-party telemetry ingestion service

## Status

Complete. Task 3 was implemented test-first and committed. The implementation is server-scoped; no Android or core-engine file changed.

Delivered:

- Separate strict diagnostics/research Pydantic contracts, metadata tables, and object prefixes.
- Hashed installation write/deletion credentials and environment-only operator authentication.
- HTTPS-only ingestion, streamed body-size limits, and client/credential rate limits.
- Idempotent plane-specific batches, including simultaneous identical retries; conflicting content returns `409`.
- Plane deletion of metadata and objects with a deterministic stable receipt.
- Internal-only Compose exposure for placement behind an HTTPS reverse proxy.

## Commit

- `0513157 feat: add separated telemetry ingestion service`

## Changed files

- `telemetry-server/.gitignore`
- `telemetry-server/docker-compose.yml`
- `telemetry-server/pyproject.toml`
- `telemetry-server/src/iaido_telemetry/__init__.py`
- `telemetry-server/src/iaido_telemetry/app.py`
- `telemetry-server/src/iaido_telemetry/auth.py`
- `telemetry-server/src/iaido_telemetry/config.py`
- `telemetry-server/src/iaido_telemetry/db.py`
- `telemetry-server/src/iaido_telemetry/schemas.py`
- `telemetry-server/src/iaido_telemetry/storage.py`
- `telemetry-server/tests/conftest.py`
- `telemetry-server/tests/test_ingestion.py`
- `telemetry-server/tests/test_isolation.py`

The report was written after the implementation commit so it could contain the exact hash. Existing untracked SDD artifacts were preserved and not staged.

## Test-first evidence

### Required initial RED

From `telemetry-server`:

```powershell
python -m pytest tests/test_ingestion.py tests/test_isolation.py -q
```

Exit code 1:

```text
ImportError while loading conftest 'C:\Users\Shoham\workspace\ninjakeys\.worktrees\telemetry\telemetry-server\tests\conftest.py'.
tests\conftest.py:6: in <module>
    from iaido_telemetry.app import create_app
E   ModuleNotFoundError: No module named 'iaido_telemetry'
```

This was the expected failure because the service package was absent.

### Concurrent-idempotency RED/GREEN

Self-review found a uniqueness race for simultaneous identical retries. Its regression test failed before the fix:

```powershell
python -m pytest tests/test_ingestion.py::test_simultaneous_identical_retries_are_idempotent -q
```

Exit code 1, relevant output:

```text
F                                                                        [100%]
>       assert [response.status_code for response in responses] == [202, 202]
E       assert [202, 500] == [202, 202]
E         At index 1 diff: 500 != 202
1 failed, 1 warning in 0.42s
```

After the fix, the same command exited 0:

```text
.                                                                        [100%]
1 passed, 1 warning in 0.33s
```

## Exact final pytest command/output

From `telemetry-server`:

```powershell
python -m pytest tests/test_ingestion.py tests/test_isolation.py -q
```

Pre-commit final run, exit code 0:

```text
................                                                         [100%]
============================== warnings summary ===============================
..\..\..\..\..\AppData\Roaming\Python\Python313\site-packages\starlette\formparsers.py:12
  C:\Users\Shoham\AppData\Roaming\Python\Python313\site-packages\starlette\formparsers.py:12: PendingDeprecationWarning: Please use `import python_multipart` instead.
    import multipart

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
16 passed, 1 warning in 1.33s
```

Post-commit rerun, exit code 0:

```text
................                                                         [100%]
============================== warnings summary ===============================
..\..\..\..\..\AppData\Roaming\Python\Python313\site-packages\starlette\formparsers.py:12
  C:\Users\Shoham\AppData\Roaming\Python\Python313\site-packages\starlette\formparsers.py:12: PendingDeprecationWarning: Please use `import python_multipart` instead.
    import multipart

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
16 passed, 1 warning in 1.40s
```

## Additional verification

```powershell
python -m ruff check telemetry-server
```

```text
All checks passed!
```

```powershell
python -m ruff format --check telemetry-server
```

```text
10 files already formatted
```

```powershell
$env:IAIDO_OPERATOR_TOKEN='compose-validation-only'; docker compose -f telemetry-server\docker-compose.yml config --quiet
```

Exit code 0, no output.

`git diff --cached --check` also exited 0 with no output before commit. The staged Android/core-engine diff was empty. A token-reference search found references only under `telemetry-server`; no operator secret or operator configuration was added to Android.

## Self-review against binding constraints

- Diagnostics accepts only `gesture_outcome`; strict models reject readable text, points, traces, and all other extra fields with `422`.
- Research accepts only bounded text (1-256 characters) or normalized traces (1-512 points; coordinates in `[0, 1]`); extra fields are forbidden.
- Plane metadata and objects are physically separated. The same installation/batch ID can exist independently in both planes.
- Write and deletion credentials are distinct. Only the environment-configured operator token can access operator routes.
- Unknown schema versions and invalid credentials are rejected; oversized and over-rate requests are rejected at the boundary.
- Identical retries return the stable acknowledgement, while conflicting checksums return `409`.
- Plane deletion removes metadata and objects and returns the same deterministic receipt on repeated calls.
- Existing Android best-effort/non-blocking telemetry behavior was untouched.

No unresolved Task 3 correctness finding remains.

## Concerns / deployment notes

- One third-party warning remains from the installed Starlette version (`multipart` import pending deprecation). It is not emitted by Task 3 code and does not fail the suite.
- The app rejects requests whose ASGI scheme is not HTTPS. Compose uses `expose`, not host `ports`; deployment requires an HTTPS reverse proxy that forwards the scheme. Direct plaintext ingestion receives `426`.
- Compose uses PostgreSQL plus MinIO (S3-compatible object storage). The
  in-process rate limiter is deliberately bounded, but a multi-instance
  deployment would require a shared/distributed rate-limit implementation.

## Fix round 1 - 2026-09-19

### Reconciled existing state

The requested worktree was clean for `telemetry-server` at takeover: there were no
unstaged or staged server changes to retain. The only untracked content was the
existing `.superpowers/sdd/2026-09-19-iaido-telemetry/` task material, which was
preserved. Commit `94904b6 fix: harden telemetry server contracts` was already
present and supplies the reviewer-requested production stack and contract
hardening, so this fix round retains it rather than reverting or duplicating it.

### Closed findings

- Production construction now selects only `PostgresRepository` plus
  `S3ObjectStorage`; `Settings.from_environment()` rejects non-PostgreSQL URLs
  and incomplete S3 configuration. `SQLiteTestRepository` and
  `LocalObjectStorage` remain explicit injected test adapters. Compose runs
  PostgreSQL, MinIO, bucket initialization, and the telemetry service.
- Diagnostics metadata is limited to UUID-shaped event/session/embedded-batch
  identifiers, numeric semantic app versions, and the fixed build-type enum;
  diagnostics payloads remain outcome-only and strict.
- Added `tests/fixtures/android-research-batch.json` and a contract-fixture
  regression. It posts the Android `TelemetryBatch`/`TelemetryEnvelope` JSON
  directly (with only the test installation ID bound to its issued credential),
  retaining both outer and embedded batch IDs and the `text_sample` and
  `gesture_trace` discriminators.
- `RateLimiter` removes stale buckets on each request and evicts least-recently
  used identities at `rate_limit_max_buckets`; its deterministic clock test
  covers both paths.

All prior boundaries remain intact: plane-specific tables/prefixes, hashed
write and deletion credentials, environment-only operator access, HTTPS,
request-size and rate limits, and checksum-based idempotency.

### Fix-round verification

Focused contract fixture:

```powershell
python -m pytest tests/test_isolation.py::test_android_telemetry_batch_fixture_is_accepted_without_translation -q
```

Exit code 0:

```text
.                                                                        [100%]
1 passed, 1 warning in 0.29s
```

Full Task 3 checks from `telemetry-server`:

```powershell
python -m pytest -q
```

Exit code 0:

```text
.........................                                                [100%]
25 passed, 1 warning in 1.83s
```

The sole warning is the existing Starlette `multipart` pending-deprecation
warning from the installed dependency.

```powershell
python -m ruff check .
```

```text
All checks passed!
```

```powershell
python -m ruff format --check .
```

```text
11 files already formatted
```
