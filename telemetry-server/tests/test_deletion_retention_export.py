import io
import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from iaido_telemetry import retention as retention_module
from iaido_telemetry.config import Settings
from iaido_telemetry.exports import ExportAuthError, export_plane

from conftest import build_test_app, envelope, queue_batch_id


OPERATOR_TOKEN = "operator-test-token"


def operator_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {OPERATOR_TOKEN}"}


def _sqlite_path(settings: Settings) -> str:
    return settings.database_url.removeprefix("sqlite:///")


def _post_diagnostics_batch(client, installation, diagnostics_headers, sequence=1):
    batch = {
        "schema_version": 1,
        "batch_id": queue_batch_id(sequence),
        "events": [envelope(installation_id=installation["installation_id"])],
    }
    response = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=batch
    )
    assert response.status_code == 202
    return batch


def _post_research_batch(client, installation, diagnostics_headers, sequence=1):
    batch = {
        "schema_version": 1,
        "batch_id": queue_batch_id(sequence),
        "events": [
            envelope(
                installation_id=installation["installation_id"],
                event_id="00000000-0000-0000-0000-000000000099",
                event_type="text_sample",
                payload={"text": "bounded sample"},
            )
        ],
    }
    response = client.post(
        "/v1/research/batches", headers=diagnostics_headers, json=batch
    )
    assert response.status_code == 202
    return batch


# Step 1: failing tests (contract example adapted to this service's operator
# deletion route and existing test fixtures) -- see task-7-brief.md.
def test_research_deletion_removes_metadata_and_raw_object(
    client, installation, diagnostics_headers, object_root
):
    _post_research_batch(client, installation, diagnostics_headers)
    installation_id = installation["installation_id"]

    receipt = client.delete(
        f"/v1/operator/installations/{installation_id}/research",
        headers=operator_headers(),
    )

    assert receipt.status_code == 200
    assert receipt.json()["plane"] == "research"
    assert receipt.json()["deleted"] is True
    assert not list((object_root / "research" / installation_id).rglob("*.json"))


def test_operator_deletion_removes_metadata_object_and_writes_audit_record(
    client, installation, diagnostics_headers, object_root, settings
):
    _post_research_batch(client, installation, diagnostics_headers)
    installation_id = installation["installation_id"]

    response = client.delete(
        f"/v1/operator/installations/{installation_id}/research",
        headers=operator_headers(),
    )

    assert response.status_code == 200
    with sqlite3.connect(_sqlite_path(settings)) as connection:
        assert connection.execute(
            "SELECT COUNT(*) FROM research_batches"
        ).fetchone() == (0,)
        audit_rows = connection.execute(
            "SELECT action, plane, installation_id FROM audit_records"
        ).fetchall()
    assert ("delete", "research", installation_id) in audit_rows


def test_operator_deletion_is_idempotent_and_isolated_between_planes(
    client, installation, diagnostics_headers, object_root, settings
):
    _post_diagnostics_batch(client, installation, diagnostics_headers, sequence=1)
    _post_research_batch(client, installation, diagnostics_headers, sequence=2)
    installation_id = installation["installation_id"]

    first = client.delete(
        f"/v1/operator/installations/{installation_id}/research",
        headers=operator_headers(),
    )
    second = client.delete(
        f"/v1/operator/installations/{installation_id}/research",
        headers=operator_headers(),
    )

    assert first.status_code == 200
    assert second.status_code == 200
    # Research is gone...
    assert not list((object_root / "research" / installation_id).rglob("*.json"))
    # ...but diagnostics data for the same installation is completely intact.
    assert list((object_root / "diagnostics" / installation_id).rglob("*.json"))
    with sqlite3.connect(_sqlite_path(settings)) as connection:
        assert connection.execute(
            "SELECT COUNT(*) FROM diagnostics_batches WHERE installation_id = ?",
            (installation_id,),
        ).fetchone() == (1,)
        assert connection.execute(
            "SELECT COUNT(*) FROM research_batches WHERE installation_id = ?",
            (installation_id,),
        ).fetchone() == (0,)


def test_operator_deletion_requires_operator_token(client, installation):
    response = client.delete(
        f"/v1/operator/installations/{installation['installation_id']}/research"
    )

    assert response.status_code == 401


def test_purge_expired_respects_default_retention_and_deployment_shortening(
    tmp_path: Path,
):
    settings = Settings(
        database_url=f"sqlite:///{(tmp_path / 'retention.db').as_posix()}",
        operator_token=OPERATOR_TOKEN,
        max_request_bytes=64 * 1024,
        rate_limit_requests=1000,
        rate_limit_window_seconds=60,
        rate_limit_max_buckets=100,
        diagnostics_retention_days=1,
    )
    object_root = tmp_path / "objects"
    app = build_test_app(settings, object_root)
    from fastapi.testclient import TestClient

    with TestClient(app, base_url="https://testserver") as client:
        installation = client.post("/v1/installations").json()
        headers = {"Authorization": f"Bearer {installation['write_credential']}"}
        _post_diagnostics_batch(client, installation, headers, sequence=1)
        _post_research_batch(client, installation, headers, sequence=2)

    old_received_at_ms = int(
        (datetime.now(timezone.utc) - timedelta(days=5)).timestamp() * 1000
    )
    with sqlite3.connect(_sqlite_path(settings)) as connection:
        connection.execute(
            "UPDATE diagnostics_batches SET received_at_ms = ?", (old_received_at_ms,)
        )
        connection.execute(
            "UPDATE research_batches SET received_at_ms = ?", (old_received_at_ms,)
        )
        connection.commit()

    purged = retention_module.purge_expired(
        app.state.database, app.state.storage, settings, now=datetime.now(timezone.utc)
    )

    # Diagnostics retention was shortened to 1 day, so a 5-day-old batch is purged...
    assert purged["diagnostics"] == 1
    # ...but research keeps its 365-day default, so the same-age batch survives.
    assert purged["research"] == 0
    assert not list(
        (object_root / "diagnostics" / installation["installation_id"]).rglob(
            "*.json"
        )
    )
    assert list(
        (object_root / "research" / installation["installation_id"]).rglob("*.json")
    )
    with sqlite3.connect(_sqlite_path(settings)) as connection:
        assert connection.execute(
            "SELECT COUNT(*) FROM diagnostics_batches"
        ).fetchone() == (0,)
        assert connection.execute(
            "SELECT COUNT(*) FROM research_batches"
        ).fetchone() == (1,)
        audit_rows = connection.execute(
            "SELECT action, plane FROM audit_records"
        ).fetchall()
    assert ("purge", "diagnostics") in audit_rows
    assert ("purge", "research") not in audit_rows


@pytest.mark.parametrize(
    ("field", "value"),
    [("diagnostics_retention_days", 91), ("research_retention_days", 400)],
)
def test_settings_reject_retention_longer_than_the_plan_defaults(field, value):
    kwargs = {
        "database_url": "sqlite:///unused.db",
        "operator_token": OPERATOR_TOKEN,
        field: value,
    }
    with pytest.raises(ValueError):
        Settings(**kwargs)


def test_settings_allow_shortening_retention_below_the_plan_defaults():
    settings = Settings(
        database_url="sqlite:///unused.db",
        operator_token=OPERATOR_TOKEN,
        diagnostics_retention_days=1,
        research_retention_days=30,
    )

    assert settings.diagnostics_retention_days == 1
    assert settings.research_retention_days == 30


def test_export_plane_never_leaks_other_plane_data_and_requires_operator_token(
    client, installation, diagnostics_headers, settings
):
    _post_diagnostics_batch(client, installation, diagnostics_headers, sequence=1)
    _post_research_batch(client, installation, diagnostics_headers, sequence=2)
    app = client.app

    with pytest.raises(ExportAuthError):
        export_plane(
            app.state.database,
            app.state.storage,
            settings,
            "research",
            0,
            2_000_000_000_000,
            io.StringIO(),
            operator_token="wrong-token",
        )

    buffer = io.StringIO()
    exported = export_plane(
        app.state.database,
        app.state.storage,
        settings,
        "research",
        0,
        2_000_000_000_000,
        buffer,
        operator_token=OPERATOR_TOKEN,
    )

    assert exported == 1
    lines = [json.loads(line) for line in buffer.getvalue().splitlines()]
    assert len(lines) == 1
    assert lines[0]["plane"] == "research"
    assert lines[0]["event"]["event_type"] == "text_sample"
    # The diagnostics gesture_outcome event for the same installation/time range
    # must never appear in a research export.
    assert all(line["plane"] == "research" for line in lines)
    assert all(line["event"]["event_type"] != "gesture_outcome" for line in lines)


def test_export_plane_writes_operator_audit_record(
    client, installation, diagnostics_headers, settings
):
    _post_diagnostics_batch(client, installation, diagnostics_headers, sequence=1)
    app = client.app

    export_plane(
        app.state.database,
        app.state.storage,
        settings,
        "diagnostics",
        0,
        2_000_000_000_000,
        io.StringIO(),
        operator_token=OPERATOR_TOKEN,
    )

    with sqlite3.connect(_sqlite_path(settings)) as connection:
        audit_rows = connection.execute(
            "SELECT action, plane, actor FROM audit_records"
        ).fetchall()
    assert ("export", "diagnostics", "operator") in audit_rows


def test_export_route_requires_operator_token_and_returns_ndjson(
    client, installation, diagnostics_headers
):
    _post_research_batch(client, installation, diagnostics_headers)

    unauthorized = client.get(
        "/v1/operator/research/export", params={"start_ms": 0, "end_ms": 2_000_000_000_000}
    )
    authorized = client.get(
        "/v1/operator/research/export",
        params={"start_ms": 0, "end_ms": 2_000_000_000_000},
        headers=operator_headers(),
    )

    assert unauthorized.status_code == 401
    assert authorized.status_code == 200
    assert authorized.headers["content-type"].startswith("application/x-ndjson")
    lines = [json.loads(line) for line in authorized.text.splitlines()]
    assert len(lines) == 1
    assert lines[0]["plane"] == "research"


def test_aggregate_views_respect_plane_separation(
    client, installation, diagnostics_headers
):
    _post_diagnostics_batch(client, installation, diagnostics_headers, sequence=1)
    accepted_second = {
        "schema_version": 1,
        "batch_id": queue_batch_id(2),
        "events": [
            envelope(
                installation_id=installation["installation_id"],
                event_id="00000000-0000-0000-0000-000000000021",
                payload={"event_type": "gesture_outcome", "outcome": "REJECTED"},
            )
        ],
    }
    accepted = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=accepted_second
    )
    assert accepted.status_code == 202
    _post_research_batch(client, installation, diagnostics_headers, sequence=3)

    gesture_outcomes = client.get(
        "/v1/operator/aggregates/gesture-outcomes", headers=operator_headers()
    )
    crash_counts = client.get(
        "/v1/operator/aggregates/crash-counts", headers=operator_headers()
    )
    runtime_error_rate = client.get(
        "/v1/operator/aggregates/runtime-error-rate", headers=operator_headers()
    )

    assert gesture_outcomes.status_code == 200
    assert gesture_outcomes.json() == {
        "plane": "diagnostics",
        "counts": {"ACCEPTED": 1, "REJECTED": 1},
    }
    # The research batch's text_sample event must never contribute to a
    # diagnostics-only aggregate, and no crash/runtime_error events exist yet.
    assert crash_counts.json() == {"plane": "diagnostics", "count": 0}
    assert runtime_error_rate.json()["errors"] == 0
    assert runtime_error_rate.json()["total"] == 2


def test_aggregate_routes_require_operator_token(client):
    response = client.get("/v1/operator/aggregates/gesture-outcomes")

    assert response.status_code == 401
