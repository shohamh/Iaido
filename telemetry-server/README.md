# Iaido Telemetry Server

First-party FastAPI service that ingests, stores, and (per this document)
retires and exports Iaido's opt-in diagnostics and research telemetry.

Diagnostics and research are two entirely separate data planes: separate
Pydantic validation models, separate database tables, and separate
object-storage prefixes (`diagnostics/` and `research/`). No code path in
this service reads or writes across that boundary; every route, deletion,
retention purge, export, and aggregate query documented below always takes
an explicit plane and only ever touches that plane's table and prefix.

## Environment variables

No credential (database, object storage, or operator token) is ever embedded
in code -- everything below is read from the process environment at startup
(`Settings.from_environment()` in `src/iaido_telemetry/config.py`).

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `IAIDO_DATABASE_URL` | yes | -- | SQLAlchemy URL for the metadata database. Production must be `postgresql+psycopg://...`; anything else fails `validate_production()`. |
| `IAIDO_OPERATOR_TOKEN` | yes | -- | Bearer token operators use for `/v1/operator/*` routes (batch listing, deletion, export, aggregates). Rotate by redeploying with a new value; there is no in-band rotation endpoint. |
| `IAIDO_DASHBOARD_USERNAME` | no | `iaido` | Username for the operator dashboard's browser login. |
| `IAIDO_DASHBOARD_PASSWORD` | no | `""` (reuses `IAIDO_OPERATOR_TOKEN`) | Password for the operator dashboard's browser login; at least 12 characters when set. Leave empty to reuse the operator token rather than introduce a second, possibly weaker, secret. Bearer access to `/v1/operator/*` always uses `IAIDO_OPERATOR_TOKEN` regardless of this value. |
| `IAIDO_S3_ENDPOINT_URL` | yes (prod) | `""` | S3-compatible endpoint (SeaweedFS locally) for the `diagnostics/` and `research/` object prefixes. |
| `IAIDO_S3_BUCKET` | yes (prod) | `""` | Bucket that holds both prefixes. |
| `IAIDO_S3_ACCESS_KEY` | yes (prod) | `""` | S3 access key. |
| `IAIDO_S3_SECRET_KEY` | yes (prod) | `""` | S3 secret key. |
| `IAIDO_S3_REGION` | no | `us-east-1` | S3 region. |
| `IAIDO_MAX_REQUEST_BYTES` | no | `262144` | Hard cap on request body size (see `IngestionBoundaryMiddleware`). |
| `IAIDO_RATE_LIMIT_REQUESTS` | no | `120` | Requests allowed per identity per window on `POST`/`DELETE` routes. |
| `IAIDO_RATE_LIMIT_WINDOW_SECONDS` | no | `60` | Rate-limit window length. |
| `IAIDO_RATE_LIMIT_MAX_BUCKETS` | no | `10000` | Upper bound on tracked rate-limit identities (LRU-evicted). |
| `IAIDO_DIAGNOSTICS_RETENTION_DAYS` | no | `90` | Diagnostics batch retention. May only be **shortened** below the 90-day plan default -- `Settings` raises `ValueError` above it. |
| `IAIDO_RESEARCH_RETENTION_DAYS` | no | `365` | Research batch retention. May only be **shortened** below the 365-day plan default -- `Settings` raises `ValueError` above it. |

`IAIDO_S3_*` are required by `validate_production()` whenever the server
builds its own Postgres/S3 adapters (i.e. whenever `create_app()` is called
without test doubles), even though the dataclass itself allows empty
defaults for local unit tests that inject `SQLiteTestRepository` /
`LocalObjectStorage` directly.

**Telemetry base URL.** This server does not read its own public origin
from the environment -- that origin is whatever your reverse proxy / load
balancer exposes over HTTPS (the middleware rejects plain HTTP outright).
That same HTTPS origin is the value you set as `IAIDO_TELEMETRY_BASE_URL`
when building the Android client (see `app/build.gradle.kts`), so the app
talks to this deployment's `/v1/...` routes.

## Local Docker Compose startup

`docker-compose.yml` runs Postgres, SeaweedFS (a local stand-in for S3; MinIO's public
images can no longer be pulled), a one-shot bucket initializer, and the telemetry service itself.

```bash
export IAIDO_POSTGRES_PASSWORD=local-dev-password
export IAIDO_S3_ACCESS_KEY=local-dev-access-key
export IAIDO_S3_SECRET_KEY=local-dev-secret-key
export IAIDO_OPERATOR_TOKEN=local-dev-operator-token
docker compose up --build
```

The `telemetry` service is built from `Dockerfile` (source baked into the image, so no bind mount
is needed) and runs `uvicorn iaido_telemetry.app:create_app --factory` on port 8000, published to
**loopback only** (`127.0.0.1:8000`, or `IAIDO_TELEMETRY_PORT` to pick another host port). The
local object store accepts any credentials, so never publish it. Plain HTTP is refused with 426
by design; put a TLS-terminating proxy in front (see below). Optional overrides:
`IAIDO_S3_REGION`, `IAIDO_MAX_REQUEST_BYTES`,
`IAIDO_RATE_LIMIT_REQUESTS`, `IAIDO_RATE_LIMIT_WINDOW_SECONDS`,
`IAIDO_RATE_LIMIT_MAX_BUCKETS`, `IAIDO_DIAGNOSTICS_RETENTION_DAYS`,
`IAIDO_RESEARCH_RETENTION_DAYS` (see the table above for defaults).

Smoke test without a proxy (the header stands in for the proxy's TLS termination):

```bash
curl -X POST -H 'X-Forwarded-Proto: https' http://127.0.0.1:8000/v1/installations   # 201
```

### Local development: hot reload (optional)

The default stack bakes the source into the image, so code changes need
`docker compose up -d --build`. To get hot reload instead, add the override file, which mounts the
source and runs uvicorn with `--reload --reload-dir /service/src`:

```bash
docker compose -f docker-compose.yml -f docker-compose.hotreload.yml up -d --build
```

This needs a writable bind mount of this folder (`pip install -e .` writes `*.egg-info`), which
Docker Desktop on Windows may refuse ("path is not shared from the host"); in that case stay on
the default stack. `--reload` is a development affordance; production runs without it.

### Behind a TLS-terminating proxy on the same host

The compose file already publishes the port on loopback only; point the proxy at it. For a phone
or emulator on your own tailnet, use `tailscale serve`, which is **tailnet-only** HTTPS:

```bash
tailscale serve --bg 8000           # or: caddy reverse-proxy / nginx
curl -X POST https://<your-host>.<tailnet>.ts.net/v1/installations   # 201
tailscale serve --https=443 off     # remove it again
```

Never use `tailscale funnel` for this: Funnel publishes the service to the **public internet**,
and this server holds user telemetry. Only devices on your tailnet (the Tailscale app running on
the phone) can reach a `serve` URL.

Two details make this work and are easy to get wrong:

- The container runs uvicorn with `--proxy-headers --forwarded-allow-ips=*`,
  because the proxy connects from the Docker gateway rather than `127.0.0.1`.
  Without that, uvicorn drops the proxy's `X-Forwarded-Proto: https`, the app
  sees an `http` scheme, and `IngestionBoundaryMiddleware` answers **426**
  for every `/v1/` route. The port mapping is host-local, so only the
  local proxy can reach the container to spoof that header.
- `/` is the operator dashboard (Basic auth) and there is no `/health` route; probe the service
  with an authenticated operator route such as `GET /v1/operator/diagnostics/batches`, or by
  provisioning an installation.

### Pointing the app at the collector (owner, one-time)

The app only uploads when a collector URL is baked into the build; a build without one shows
telemetry as "unavailable in this build" in Settings.

1. Run `tailscale serve --bg 8000` (above) and note the `https://<your-host>.<tailnet>.ts.net` URL.
2. For CI/release builds: set the GitHub repository **variable** `IAIDO_TELEMETRY_BASE_URL` to that
   URL (Settings > Secrets and variables > Actions > Variables). The workflows pass it as
   `-PiaidoTelemetryBaseUrl`; if unset, the build has telemetry unavailable.
   This URL is baked into the public release and nightly APKs, so the collector must stay
   tailnet-only (`tailscale serve`, never `tailscale funnel`): otherwise every opted-in user's
   research data, from anyone who installs the APK, would land on this machine.
3. For local builds: add `iaidoTelemetryBaseUrl=https://<your-host>.<tailnet>.ts.net` to
   `~/.gradle/gradle.properties` (not the repo's `gradle.properties`, which only carries a commented
   example).
4. Android emulators may not resolve the tailnet name; see B9 in
   `docs/superpowers/diagnosis/2026-10-17-swipe-telemetry.md` for the hosts-file workaround. Real
   phones just need the Tailscale app running.

### Run the emulator end-to-end test

`app/src/androidTest/kotlin/com/iaido/app/SwipeTelemetryE2eTest.kt` enables research consent
through the real consent controller, swipes "hello" on the real Iaido keyboard, and polls
`GET /v1/operator/research/batches?app_version=<installed version>` (plus the export) until the
swipe `gesture_trace` arrives; it also checks the batch is absent under another version, that
nothing is sent with consent off, and that lost credentials are re-provisioned. Without a baked
URL and an operator token every test returns early (passes without doing anything).

1. Start the collector with throwaway dev credentials (Git Bash, from `telemetry-server/`):

   ```bash
   export IAIDO_POSTGRES_PASSWORD=local-dev-password IAIDO_S3_ACCESS_KEY=local-dev-access-key \
          IAIDO_S3_SECRET_KEY=local-dev-secret-key IAIDO_OPERATOR_TOKEN=local-dev-operator-token
   docker compose -p iaido-swipe-e2e up -d --build
   tailscale serve --bg 8000           # tailnet-only HTTPS; never `tailscale funnel`
   tailscale status --json | grep -m1 DNSName   # -> <host>.<tailnet>.ts.net
   ```

2. Boot the emulator and make the tailnet name resolve inside it (B9; repeat after every boot;
   needs a userdebug/Google APIs image for `adb root`; `<tailscale-ip>` is `tailscale ip -4`):

   ```bash
   A=$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe
   $LOCALAPPDATA/Android/Sdk/emulator/emulator.exe -avd IaidoApi35 -no-snapshot-save -no-boot-anim &
   $A wait-for-device && $A root && $A wait-for-device
   MSYS_NO_PATHCONV=1 $A shell 'cat /system/etc/hosts > /data/local/tmp/hosts;
     echo "<tailscale-ip> <host>.<tailnet>.ts.net" >> /data/local/tmp/hosts;
     chmod 644 /data/local/tmp/hosts; chcon u:object_r:system_file:s0 /data/local/tmp/hosts;
     mount -o bind /data/local/tmp/hosts /system/etc/hosts'
   ```

3. Run the test from the repo root. The URL is baked into the build; the operator token goes only
   on the command line (never commit it):

   ```bash
   ./gradlew :app:connectedDebugAndroidTest \
     -PiaidoTelemetryBaseUrl=https://<host>.<tailnet>.ts.net \
     -Pandroid.testInstrumentationRunnerArguments.class=com.iaido.app.SwipeTelemetryE2eTest \
     -Pandroid.testInstrumentationRunnerArguments.iaidoOperatorToken=local-dev-operator-token
   ```

4. See the trace in the dashboard: `https://<host>.<tailnet>.ts.net/?app_version=<versionName>`
   (Basic auth `iaido` / the operator token), then open the batch.

Stop everything with `docker compose -p iaido-swipe-e2e down -v` and
`tailscale serve --https=443 off`.

## Migrations

There is no separate migration tool yet: `create_app()` calls
`database.create_schema()` on startup, which runs
`Base.metadata.create_all(engine)` -- additive, idempotent `CREATE TABLE IF
NOT EXISTS`-equivalent DDL for every table declared in
`src/iaido_telemetry/db.py` (`installations`, `diagnostics_batches`,
`research_batches`, `diagnostics_event_facts`, `research_event_facts`,
`audit_records`). Restarting the service is therefore always safe to pick
up newly added tables.

Columns added later are handled by a small additive step in the same call: `create_schema()` adds
`app_version` and `algorithm_version` (indexed, default `unknown`) to both batch tables when they
are missing, and startup then backfills every still-`unknown` batch from its stored payload
(batches whose payload cannot be read stay `unknown`). If a future change needs a destructive
migration (renaming/dropping a column), introduce Alembic at that point rather than
hand-editing tables in production.

## Versions

Every batch is tagged at ingest with the `app_version` and `algorithm_version` of its newest
event (`unknown` when the batch is empty or has none; only research gesture and correction
payloads carry an algorithm version). `app_version` is a release such as `0.1.20` or a nightly
such as `0.1.20-nightly.123`.

- `GET /v1/operator/{plane}/batches?app_version=0.1.20` lists one version's batches (each row also
  shows its `app_version` and `algorithm_version`).
- `GET /v1/operator/{plane}/versions` returns `[{app_version, batches, events}]`, newest first.
- Exports are per version: `app_version` is required (see below). Versions never mix in an export.
- A retried batch is acknowledged without being stored twice, so it counts once.
- A batch can hold events of two versions (an upgrade mid-queue). The tag, the `?app_version=`
  filter and the `/versions` counts follow the batch tag (its newest event), so such a batch is
  listed and counted under the newer version only. Exports do not depend on the tag: they scan
  every batch in the time range and keep events whose own `app_version` matches, so the older
  events are exported under their own version and versions never mix.

## Retention

`src/iaido_telemetry/retention.py` exposes:

```python
purge_expired(database, storage, settings, now: datetime) -> dict[str, int]
```

It deletes every diagnostics batch older than
`settings.diagnostics_retention_days` (default 90) and every research batch
older than `settings.research_retention_days` (default 365), removing the
object-storage payload, the database batch row, and the associated
aggregate event-fact rows together, per plane. A plane's retention window
can never affect the other plane's data -- the two are computed and applied
independently. Each purge that removes at least one batch writes one
`audit_records` row (`action="purge"`) naming the plane and how many
batches were removed.

Run it as a scheduled job (cron, systemd timer, or your platform's
scheduled-task equivalent) against the live app state, e.g.:

```bash
python -c "
from datetime import datetime, timezone
from iaido_telemetry.app import create_app
from iaido_telemetry.retention import purge_expired

app = create_app()
print(purge_expired(app.state.database, app.state.storage, app.state.settings, datetime.now(timezone.utc)))
"
```

## Running a deletion

Two deletion paths exist, both idempotent (a second call is a no-op, not an
error) and both plane-scoped (deleting `research` never touches
`diagnostics` for the same installation, and vice versa):

- **Device self-deletion** -- `DELETE /v1/{plane}` with the installation's
  own deletion credential (`Authorization: Bearer <deletion_credential>`
  returned at installation time). This is what the Android app uses when a
  user disables a data plane.
- **Operator deletion** -- `DELETE
  /v1/operator/installations/{installation_id}/{plane}` with the operator
  bearer token, for support/compliance requests that name an installation
  directly:

  ```bash
  curl -X DELETE \
    -H "Authorization: Bearer $IAIDO_OPERATOR_TOKEN" \
    "https://telemetry.example.com/v1/operator/installations/$INSTALLATION_ID/research"
  ```

Both paths call the same `delete_installation_plane(database, storage,
installation_id, plane, actor=...)` function
(`src/iaido_telemetry/app.py`), which removes the object-storage payload,
the batch metadata row, and the aggregate event-fact rows for that plane in
one operation and always writes an `audit_records` row (`action="delete"`,
tagged with `actor="device"` or `actor="operator"`).

## Running an export

`src/iaido_telemetry/exports.py` exposes:

```python
export_plane(database, storage, settings, plane, start_ms, end_ms, output, *, operator_token, app_version) -> int
```

It requires an explicit plane, an explicit `[start_ms, end_ms)` time range
(by `received_at_ms`), exactly one `app_version` (`ValueError`, or HTTP 422, when absent), and a
valid operator token -- it raises
`ExportAuthError` on a bad token, independent of any HTTP-layer check, so
it is also safe to call from a standalone operator script. It writes
newline-delimited JSON (NDJSON), one line per event, to `output`, and never
reads the other plane's table or object prefix, so a research export can
never contain a diagnostics event (or vice versa) even for the same
installation and time range. Every call writes one `audit_records` row
(`action="export"`) naming the plane, time range, and batch count.

Over HTTP:

```bash
curl -G \
  -H "Authorization: Bearer $IAIDO_OPERATOR_TOKEN" \
  --data-urlencode "start_ms=1750000000000" \
  --data-urlencode "end_ms=1750086400000"   --data-urlencode "app_version=0.1.20" \
  "https://telemetry.example.com/v1/operator/research/export" \
  -o research-export.ndjson
```

## Operator dashboard

`GET /` renders a read-only HTML view of both planes from the same repository and object storage:
per-plane batch and event counts, the newest batches (each linking to its own page), the
diagnostics aggregate facts, research trace/correction counts, and the audit log. A version picker
(`/?app_version=...`) scopes the batch lists and counts to one app version, defaulting to the
latest; the aggregate tables are not versioned.

| Route | Contents |
|---|---|
| `/` | Overview: per-plane counts, newest batches, diagnostics aggregates (gesture outcomes, correction actions, latency buckets, runtime errors, crashes), research trace/correction counts, links, audit log |
| `/gestures` | Newest research gesture traces, each drawn over the virtual keyboard the trace was captured on |
| `/errors` | Newest diagnostics runtime errors and crashes: stable code, exception class, stack frames, and crash breadcrumbs |
| `/batches/{plane}/{installation_id}/{batch_id}` | One batch: envelope fields plus every stored payload, with traces rendered and errors shown as tracebacks |

The gesture view draws the same key geometry the keyboard uses (`KeyboardGeometry.kt`,
`KeyboardInputView.kt`): 10 columns, four rows of equal height, three centred letter rows, and a
weighted bottom row. Trace points are already normalized to that surface, so nothing is rescaled;
each pointer id gets its own polyline, with a ring at the start and a filled dot at the end.

Errors and crashes render from the diagnostics payloads. Diagnostics never carry exception
*messages* - a message can embed typed text - so a reader gets the exception class, the
`Class.method(File:line)` frames, and the enum-only breadcrumbs that led up to a crash.

Authentication is HTTP Basic with the configured dashboard login - `IAIDO_DASHBOARD_USERNAME`
(default `iaido`) and `IAIDO_DASHBOARD_PASSWORD` (default: the operator token) - so a browser can
prompt for it directly. `Authorization: Bearer <operator token>` also works, for scripts. A
dashboard password set through the environment must be at least 12 characters; leaving it empty
reuses the operator token instead of introducing a second, possibly weaker, secret.

```
https://<your-host>/        # the browser prompts for username and password
```

Changing the login on a running deployment is one environment change and a redeploy:

```bash
export IAIDO_DASHBOARD_USERNAME=iaido
export IAIDO_DASHBOARD_PASSWORD='<at least 12 characters>'
docker compose up -d telemetry
```

Notes:

- The overview is metadata only; payload text is rendered only on a batch page, where every value
  is HTML-escaped (research records can contain readable user-typed text).
- No JavaScript, no external assets, no new secret - the operator token is the only credential.
- The dashboard is a separate, explicitly per-plane view; batch links always carry the plane. It is
  not an export surface, so it never mixes the two planes into one stream.
- `IngestionBoundaryMiddleware` only covers `/v1/`, so the dashboard is served over whatever scheme
  the deployment terminates: keep it behind the same TLS proxy as `/v1/` and do not publish it on
  a plaintext interface.

## Reviewed research fixtures

`src/iaido_telemetry/fixture_export.py` turns operator-approved research records into one
deterministic, de-identified fixture bundle for core-engine and connected-IME regression tests:

```python
export_research_fixtures(database, storage, settings, start_ms, end_ms, review_manifest, output_dir, *, operator_token, app_version) -> Path
```

`app_version` is required (one version per bundle). `review_manifest` is required (a mapping of approved content hashes, or a path to a JSON manifest
shaped `{"approved": {"<sha256>": "<note>"}}`); a missing or empty manifest raises `ValueError`, so
unreviewed research data can never reach a fixture file. Approved records are revalidated against
the current research bounds, stripped of installation/session/batch ids and receive timestamps,
ordered by content hash, and written as `research-fixtures-v1.json` with deterministic
`fixture-0001`-style ids and one `audit_records` row (`action="export"`). The format, bounds, and
rejection rules are documented in [`../docs/research/schema-v1.md`](../docs/research/schema-v1.md),
with the human review workflow in [`../docs/research/README.md`](../docs/research/README.md).

## Aggregate views for operator analysis

`GET /v1/operator/aggregates/{crash-counts,runtime-error-rate,gesture-outcomes,correction-actions,latency-buckets}`
(all operator-token gated) return read-only counts/rates computed from
`diagnostics_event_facts` -- a small table populated at ingestion time that
stores only `event_type` and a bounded enum-like discriminator (e.g. a
gesture outcome, error code, or latency bucket) per event, never raw text
or coordinates. These are diagnostics-only signals per the telemetry design
(crash/runtime-error/gesture/suggestion/latency events are diagnostics
events), so every aggregate route reads only the diagnostics table -- a
research batch for the same installation can never contribute to an
aggregate count. `research_event_facts` exists in parallel with the same
shape, reserved for research-plane aggregates if a future consent version
needs them, but no route currently reads it, preserving the plane
boundary.

## Inspecting a sample payload without exposing production data

Point the server at local SQLite + filesystem storage instead of
Postgres/S3 (exactly what the test suite's `conftest.py` fixtures do), load
one fixture batch, and inspect it -- nothing here talks to a production
database or bucket:

```bash
python - <<'PY'
from pathlib import Path
import tempfile

from fastapi.testclient import TestClient

from iaido_telemetry.app import create_app
from iaido_telemetry.config import Settings
from iaido_telemetry.db import SQLiteTestRepository
from iaido_telemetry.storage import LocalObjectStorage

tmp = Path(tempfile.mkdtemp())
settings = Settings(
    database_url=f"sqlite:///{(tmp / 'sample.db').as_posix()}",
    operator_token="local-inspect-token",
)
app = create_app(
    settings,
    database=SQLiteTestRepository(settings.database_url),
    storage=LocalObjectStorage(tmp / "objects"),
)

with TestClient(app, base_url="https://testserver") as client:
    installation = client.post("/v1/installations").json()
    headers = {"Authorization": f"Bearer {installation['write_credential']}"}
    batch = {
        "schema_version": 1,
        "batch_id": "batch-1-1750000000000-00000000-0000-0000-0000-000000000001",
        "events": [{
            "schema_version": 1,
            "event_id": "00000000-0000-0000-0000-000000000010",
            "batch_id": "00000000-0000-0000-0000-000000000000",
            "installation_id": installation["installation_id"],
            "session_id": "00000000-0000-0000-0000-000000000002",
            "occurred_at_ms": 1_750_000_000_000,
            "app_version": "0.1.8",
            "build_type": "release",
            "android_api": 36,
            "event_type": "gesture_outcome",
            "payload": {"event_type": "gesture_outcome", "outcome": "ACCEPTED"},
        }],
    }
    client.post("/v1/diagnostics/batches", headers=headers, json=batch)
    batches = client.get(
        "/v1/operator/diagnostics/batches",
        headers={"Authorization": "Bearer local-inspect-token"},
    )
    print(batches.json())
PY
```

`tests/fixtures/android-research-batch.json` is a second, ready-made sample
you can load the same way against the `/v1/research/batches` route to
inspect a research-plane payload: it carries one `gesture_trace` with
normalized pointer samples and one `research_correction` with the affected
span, in exactly the shape the Android client sends. Both samples stay
entirely inside the temporary SQLite database and filesystem directory
created above, and are discarded with it.

## Tests

```bash
python -m pytest tests/test_deletion_retention_export.py -q
python -m pytest tests -q          # full suite: 118 tests
```

Runs deletion (metadata + object removal, idempotency, plane isolation,
audit records), retention (default/shortened windows, per-plane
independence), export (plane isolation, operator-token gating, audit
records), reviewed-fixture export (manifest gating, byte determinism,
de-identification, bounds rejection, plane isolation), ingestion/isolation
(both planes' schemas, bounds, and cross-plane rejection), and
aggregate-view (plane separation) coverage.

`tests/test_fixture_export.py` covers only the local export functions and
the HTTP routes through `TestClient`; nothing here has been run against a
deployed staging collector, and the `IAIDO_S3_*` real-S3 (non-SeaweedFS) addressing
noted above is still unverified outside the `LocalObjectStorage` test double.
