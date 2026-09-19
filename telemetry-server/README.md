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
| `IAIDO_S3_ENDPOINT_URL` | yes (prod) | `""` | S3-compatible endpoint (MinIO locally) for the `diagnostics/` and `research/` object prefixes. |
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

`docker-compose.yml` runs Postgres, MinIO (S3-compatible object storage), a
one-shot MinIO bucket initializer, and the telemetry service itself.

```bash
export IAIDO_POSTGRES_PASSWORD=local-dev-password
export IAIDO_S3_ACCESS_KEY=local-dev-access-key
export IAIDO_S3_SECRET_KEY=local-dev-secret-key
export IAIDO_OPERATOR_TOKEN=local-dev-operator-token
docker compose up --build
```

The `telemetry` service installs the package (`pip install -e .`) and runs
`uvicorn iaido_telemetry.app:create_app --factory` on port 8000 (only
exposed to other compose services via `expose`, not published to the host,
by design -- put a TLS-terminating proxy in front for anything beyond a
laptop). Optional overrides: `IAIDO_S3_REGION`, `IAIDO_MAX_REQUEST_BYTES`,
`IAIDO_RATE_LIMIT_REQUESTS`, `IAIDO_RATE_LIMIT_WINDOW_SECONDS`,
`IAIDO_RATE_LIMIT_MAX_BUCKETS`, `IAIDO_DIAGNOSTICS_RETENTION_DAYS`,
`IAIDO_RESEARCH_RETENTION_DAYS` (see the table above for defaults).

## Migrations

There is no separate migration tool yet: `create_app()` calls
`database.create_schema()` on startup, which runs
`Base.metadata.create_all(engine)` -- additive, idempotent `CREATE TABLE IF
NOT EXISTS`-equivalent DDL for every table declared in
`src/iaido_telemetry/db.py` (`installations`, `diagnostics_batches`,
`research_batches`, `diagnostics_event_facts`, `research_event_facts`,
`audit_records`). Restarting the service is therefore always safe to pick
up newly added tables. If a future change needs a real column-level
migration (renaming/dropping a column, backfilling data), introduce
Alembic at that point rather than hand-editing tables in production.

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
export_plane(database, storage, settings, plane, start_ms, end_ms, output, *, operator_token) -> int
```

It requires an explicit plane, an explicit `[start_ms, end_ms)` time range
(by `received_at_ms`), and a valid operator token -- it raises
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
  --data-urlencode "end_ms=1750086400000" \
  "https://telemetry.example.com/v1/operator/research/export" \
  -o research-export.ndjson
```

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
inspect a research-plane payload -- both stay entirely inside the temporary
SQLite database and filesystem directory created above, and are discarded
with it.

## Tests

```bash
python -m pytest tests/test_deletion_retention_export.py -q
```

Runs deletion (metadata + object removal, idempotency, plane isolation,
audit records), retention (default/shortened windows, per-plane
independence), export (plane isolation, operator-token gating, audit
records), and aggregate-view (plane separation) coverage. Run `python -m
pytest -q` for the full suite, including Task 3's ingestion/isolation
tests.
