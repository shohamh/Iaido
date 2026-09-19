import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi.testclient import TestClient

from iaido_telemetry.config import Settings

from conftest import build_test_app, envelope, queue_batch_id


def test_installation_returns_plane_write_and_deletion_credentials_over_https(client):
    response = client.post("/v1/installations")

    assert response.status_code == 201
    body = response.json()
    assert set(body) == {"installation_id", "write_credential", "deletion_credential"}
    assert len(body["installation_id"]) >= 32
    assert len(body["write_credential"]) >= 32
    assert len(body["deletion_credential"]) >= 32
    assert body["write_credential"] != body["deletion_credential"]


def test_api_rejects_plain_http(settings, object_root):
    with TestClient(
        build_test_app(settings, object_root), base_url="http://testserver"
    ) as http_client:
        response = http_client.post("/v1/installations")

    assert response.status_code == 426


def test_research_requires_installation_write_credential(client):
    response = client.post(
        "/v1/research/batches",
        json={"batch_id": queue_batch_id(1), "events": []},
    )

    assert response.status_code == 401


def test_identical_batch_is_idempotent_and_conflicting_batch_is_rejected(
    client, installation, diagnostics_headers
):
    outer_batch_id = queue_batch_id(1)
    batch = {
        "schema_version": 1,
        "batch_id": outer_batch_id,
        "events": [envelope(installation_id=installation["installation_id"])],
    }

    first = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=batch
    )
    duplicate = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=batch
    )
    batch["events"][0]["payload"]["outcome"] = "REJECTED"
    conflict = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=batch
    )

    assert first.status_code == 202
    assert first.json() == {"batch_id": outer_batch_id, "accepted": True}
    assert duplicate.status_code == 202
    assert duplicate.json() == first.json()
    assert conflict.status_code == 409


def test_simultaneous_identical_retries_are_idempotent(tmp_path: Path):
    settings = Settings(
        database_url=f"sqlite:///{(tmp_path / 'concurrent.db').as_posix()}",
        operator_token="operator-test-token",
        max_request_bytes=64 * 1024,
        rate_limit_requests=100,
        rate_limit_window_seconds=60,
        rate_limit_max_buckets=100,
    )
    object_root = tmp_path / "objects"
    app = build_test_app(settings, object_root)
    with TestClient(
        app, base_url="https://testserver", raise_server_exceptions=False
    ) as concurrent_client:
        installation = concurrent_client.post("/v1/installations").json()
        headers = {"Authorization": f"Bearer {installation['write_credential']}"}
        batch = {
            "schema_version": 1,
            "batch_id": queue_batch_id(1),
            "events": [envelope(installation_id=installation["installation_id"])],
        }
        barrier = threading.Barrier(2)
        original_batch_lookup = app.state.database.batch

        def synchronized_batch_lookup(*args, **kwargs):
            record = original_batch_lookup(*args, **kwargs)
            if record is None:
                barrier.wait(timeout=5)
            return record

        app.state.database.batch = synchronized_batch_lookup
        with ThreadPoolExecutor(max_workers=2) as executor:
            responses = list(
                executor.map(
                    lambda _: concurrent_client.post(
                        "/v1/diagnostics/batches", headers=headers, json=batch
                    ),
                    range(2),
                )
            )

    assert [response.status_code for response in responses] == [202, 202]
    assert len(list((object_root / "diagnostics").rglob("*.json"))) == 1


def test_request_body_size_is_limited(tmp_path: Path):
    settings = Settings(
        database_url=f"sqlite:///{(tmp_path / 'size.db').as_posix()}",
        operator_token="operator-test-token",
        max_request_bytes=128,
        rate_limit_requests=100,
        rate_limit_window_seconds=60,
        rate_limit_max_buckets=100,
    )
    object_root = tmp_path / "objects"
    with TestClient(
        build_test_app(settings, object_root), base_url="https://testserver"
    ) as limited_client:
        response = limited_client.post(
            "/v1/installations",
            content=b"{" + b'"padding":"' + (b"x" * 256) + b'"}',
            headers={"Content-Type": "application/json"},
        )

    assert response.status_code == 413


def test_mutating_routes_are_rate_limited(tmp_path: Path):
    settings = Settings(
        database_url=f"sqlite:///{(tmp_path / 'rate.db').as_posix()}",
        operator_token="operator-test-token",
        max_request_bytes=1024,
        rate_limit_requests=1,
        rate_limit_window_seconds=60,
        rate_limit_max_buckets=100,
    )
    object_root = tmp_path / "objects"
    with TestClient(
        build_test_app(settings, object_root), base_url="https://testserver"
    ) as limited_client:
        first = limited_client.post("/v1/installations")
        second = limited_client.post("/v1/installations")

    assert first.status_code == 201
    assert second.status_code == 429


def test_plane_deletion_removes_metadata_and_object_and_returns_stable_receipt(
    client, installation, diagnostics_headers, settings, object_root
):
    outer_batch_id = queue_batch_id(1)
    batch = {
        "schema_version": 1,
        "batch_id": outer_batch_id,
        "events": [envelope(installation_id=installation["installation_id"])],
    }
    accepted = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=batch
    )
    deletion_headers = {
        "Authorization": f"Bearer {installation['deletion_credential']}"
    }

    first = client.delete("/v1/diagnostics", headers=deletion_headers)
    second = client.delete("/v1/diagnostics", headers=deletion_headers)

    assert accepted.status_code == 202
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json() == second.json()
    assert first.json()["plane"] == "diagnostics"
    assert first.json()["deleted"] is True
    assert not list((object_root / "diagnostics").rglob("*.json"))
    with sqlite3.connect(
        settings.database_url.removeprefix("sqlite:///")
    ) as connection:
        assert connection.execute(
            "SELECT COUNT(*) FROM diagnostics_batches"
        ).fetchone() == (0,)


def test_unknown_schema_version_is_rejected(client, installation, diagnostics_headers):
    batch = {
        "schema_version": 2,
        "batch_id": queue_batch_id(1),
        "events": [],
    }

    response = client.post(
        "/v1/diagnostics/batches", headers=diagnostics_headers, json=batch
    )

    assert response.status_code == 422
