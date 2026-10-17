import base64
import io
import json

import pytest

from iaido_telemetry.exports import export_plane
from iaido_telemetry.fixture_export import event_hash, export_research_fixtures

from conftest import envelope, queue_batch_id

OPERATOR_TOKEN = "operator-test-token"
OPERATOR = {"Authorization": f"Bearer {OPERATOR_TOKEN}"}
TRACE_ID = "30000000-0000-4000-8000-0000000000aa"


def trace_event(installation_id, *, app_version, algorithm_version=1, event_id=None):
    return envelope(
        installation_id=installation_id,
        event_id=event_id or "00000000-0000-0000-0000-0000000000c1",
        event_type="gesture_trace",
        app_version=app_version,
        payload={
            "trace_id": TRACE_ID,
            "classification": "swipe",
            "language": "ENGLISH",
            "layout_id": "qwerty",
            "algorithm_version": algorithm_version,
            "points": [
                {"pointer_id": 0, "action": 0, "time_offset_ms": 0, "x": 0.25, "y": 0.25},
                {"pointer_id": 0, "action": 2, "time_offset_ms": 42, "x": 0.31, "y": 0.25},
            ],
        },
    )


def post(client, headers, plane, events, sequence):
    batch = {
        "schema_version": 1,
        "batch_id": queue_batch_id(sequence),
        "events": events,
    }
    response = client.post(f"/v1/{plane}/batches", headers=headers, json=batch)
    assert response.status_code == 202, response.text
    return batch


def post_research(client, installation, headers, sequence, *, app_version, algorithm_version=1):
    return post(
        client,
        headers,
        "research",
        [
            trace_event(
                installation["installation_id"],
                app_version=app_version,
                algorithm_version=algorithm_version,
                event_id=f"00000000-0000-0000-0000-{sequence:012d}",
            )
        ],
        sequence,
    )


def test_server_accepts_release_and_nightly_version_names(
    client, installation, diagnostics_headers
):
    versions = ("0.1.20", "0.1.20-nightly.123", "0.1.22-nightly.1500")
    for sequence, version in enumerate(versions, 1):
        post(
            client,
            diagnostics_headers,
            "diagnostics",
            [envelope(installation_id=installation["installation_id"], app_version=version)],
            sequence,
        )


@pytest.mark.parametrize(
    "bad",
    ["0.1", "0.1.2-", "0.1.2-nightly.", "0.1.2 nightly", "x" * 40, "0.1.2-nightly.1/../"],
)
def test_server_still_rejects_malformed_versions(client, installation, diagnostics_headers, bad):
    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(1),
            "events": [envelope(installation_id=installation["installation_id"], app_version=bad)],
        },
    )
    assert response.status_code == 422


def test_batches_store_app_version(client, installation, diagnostics_headers):
    post_research(
        client, installation, diagnostics_headers, 1,
        app_version="0.1.20-nightly.123", algorithm_version=3,
    )

    records = client.app.state.database.list_batches("research")
    assert [(r.app_version, r.algorithm_version) for r in records] == [
        ("0.1.20-nightly.123", "3")
    ]
    body = client.get("/v1/operator/research/batches", headers=OPERATOR).json()
    assert body["batches"][0]["app_version"] == "0.1.20-nightly.123"
    assert body["batches"][0]["algorithm_version"] == "3"


def test_batches_filter_by_app_version(client, installation, diagnostics_headers):
    post_research(client, installation, diagnostics_headers, 1, app_version="0.1.4")
    post_research(client, installation, diagnostics_headers, 2, app_version="0.1.5")

    body = client.get(
        "/v1/operator/research/batches", params={"app_version": "0.1.5"}, headers=OPERATOR
    ).json()
    assert [b["batch_id"] for b in body["batches"]] == [queue_batch_id(2)]
    everything = client.get("/v1/operator/research/batches", headers=OPERATOR).json()
    assert len(everything["batches"]) == 2


def test_versions_endpoint_counts_per_version(client, installation, diagnostics_headers):
    post_research(client, installation, diagnostics_headers, 1, app_version="0.1.4")
    post_research(client, installation, diagnostics_headers, 2, app_version="0.1.4")
    post_research(client, installation, diagnostics_headers, 3, app_version="0.1.5-nightly.9")

    response = client.get("/v1/operator/research/versions", headers=OPERATOR)
    assert response.status_code == 200
    assert sorted(response.json(), key=lambda row: row["app_version"]) == [
        {"app_version": "0.1.4", "batches": 2, "events": 2},
        {"app_version": "0.1.5-nightly.9", "batches": 1, "events": 1},
    ]
    assert client.get("/v1/operator/research/versions").status_code == 401


def test_retry_same_batch_counted_once(client, installation, diagnostics_headers):
    batch = post_research(client, installation, diagnostics_headers, 1, app_version="0.1.4")
    again = client.post("/v1/research/batches", headers=diagnostics_headers, json=batch)
    assert again.status_code == 202

    rows = client.get("/v1/operator/research/versions", headers=OPERATOR).json()
    assert rows == [{"app_version": "0.1.4", "batches": 1, "events": 1}]


def test_missing_version_stored_as_unknown(client, installation, diagnostics_headers):
    post(client, diagnostics_headers, "research", [], 1)

    record = client.app.state.database.list_batches("research")[0]
    assert record.app_version == "unknown"
    assert record.algorithm_version == "unknown"
    # diagnostics events carry no algorithm version at all
    post(
        client,
        diagnostics_headers,
        "diagnostics",
        [envelope(installation_id=installation["installation_id"])],
        2,
    )
    diagnostics = client.app.state.database.list_batches("diagnostics")[0]
    assert (diagnostics.app_version, diagnostics.algorithm_version) == ("0.1.4", "unknown")


def test_export_requires_app_version(client, installation, diagnostics_headers, settings):
    post_research(client, installation, diagnostics_headers, 1, app_version="0.1.4")
    post_research(client, installation, diagnostics_headers, 2, app_version="0.1.5")
    app = client.app

    with pytest.raises(ValueError, match="app_version"):
        export_plane(
            app.state.database, app.state.storage, settings, "research",
            0, 2_000_000_000_000, io.StringIO(), operator_token=OPERATOR_TOKEN,
        )

    buffer = io.StringIO()
    export_plane(
        app.state.database, app.state.storage, settings, "research",
        0, 2_000_000_000_000, buffer, operator_token=OPERATOR_TOKEN, app_version="0.1.5",
    )
    lines = [json.loads(line) for line in buffer.getvalue().splitlines()]
    assert [line["batch_id"] for line in lines] == [queue_batch_id(2)]

    missing = client.get(
        "/v1/operator/research/export",
        params={"start_ms": 0, "end_ms": 2_000_000_000_000},
        headers=OPERATOR,
    )
    assert missing.status_code == 422
    ok = client.get(
        "/v1/operator/research/export",
        params={"start_ms": 0, "end_ms": 2_000_000_000_000, "app_version": "0.1.4"},
        headers=OPERATOR,
    )
    assert ok.status_code == 200
    assert len(ok.text.splitlines()) == 1


def test_fixture_export_requires_app_version(
    client, installation, diagnostics_headers, settings, tmp_path
):
    post_research(client, installation, diagnostics_headers, 1, app_version="0.1.4")
    post_research(client, installation, diagnostics_headers, 2, app_version="0.1.5")
    app = client.app
    manifest = {
        event_hash(event): "ok"
        for record in app.state.database.list_batches("research")
        for event in json.loads(app.state.storage.get(record.object_key))["events"]
    }
    assert len(manifest) == 2

    def run(out, **kwargs):
        return export_research_fixtures(
            app.state.database, app.state.storage, settings,
            0, 2_000_000_000_000, manifest, out,
            operator_token=OPERATOR_TOKEN, **kwargs,
        )

    with pytest.raises(ValueError, match="app_version"):
        run(tmp_path / "none")
    bundle = json.loads(run(tmp_path / "v", app_version="0.1.5").read_text(encoding="utf-8"))
    assert len(bundle["fixtures"]) == 1


def basic_auth():
    token = base64.b64encode(f"iaido:{OPERATOR_TOKEN}".encode()).decode()
    return {"Authorization": f"Basic {token}"}


def test_dashboard_defaults_to_latest_version(client, installation, diagnostics_headers):
    post_research(client, installation, diagnostics_headers, 1, app_version="0.1.4")
    post_research(client, installation, diagnostics_headers, 2, app_version="0.1.10")
    post_research(client, installation, diagnostics_headers, 3, app_version="0.1.10-nightly.5")

    default = client.get("/", headers=basic_auth())
    assert default.status_code == 200
    assert 'name="app_version"' in default.text
    assert f"/{queue_batch_id(2)}" in default.text
    assert f"/{queue_batch_id(1)}" not in default.text
    # 0.1.10 sorts numerically above 0.1.4 and above its own nightly
    assert f"/{queue_batch_id(3)}" not in default.text

    picked = client.get("/", params={"app_version": "0.1.4"}, headers=basic_auth())
    assert f"/{queue_batch_id(1)}" in picked.text
    assert f"/{queue_batch_id(2)}" not in picked.text


def test_old_database_is_migrated_and_backfilled_from_stored_payload(settings, object_root):
    import sqlite3

    from conftest import build_test_app
    from iaido_telemetry.storage import LocalObjectStorage

    installation_id = "00000000-0000-0000-0000-0000000000aa"
    storage = LocalObjectStorage(object_root)
    batch_id = queue_batch_id(1)
    key = storage.object_key("research", installation_id, batch_id)
    storage.put_if_absent(
        key,
        json.dumps(
            {"events": [trace_event(installation_id, app_version="0.1.7", algorithm_version=2)]}
        ).encode(),
    )
    path = settings.database_url.removeprefix("sqlite:///")
    with sqlite3.connect(path) as connection:
        connection.execute(
            "CREATE TABLE research_batches (id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "installation_id VARCHAR(128) NOT NULL, batch_id VARCHAR(128) NOT NULL, "
            "checksum VARCHAR(64) NOT NULL, object_key VARCHAR(512) NOT NULL UNIQUE, "
            "received_at_ms BIGINT NOT NULL)"
        )
        connection.execute(
            "INSERT INTO research_batches (installation_id, batch_id, checksum, object_key, "
            "received_at_ms) VALUES (?, ?, 'c', ?, 1)",
            (installation_id, batch_id, key),
        )

    app = build_test_app(settings, object_root)

    record = app.state.database.list_batches("research")[0]
    assert (record.app_version, record.algorithm_version) == ("0.1.7", "2")
