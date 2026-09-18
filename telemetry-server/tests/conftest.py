from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from iaido_telemetry.app import create_app
from iaido_telemetry.config import Settings


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        database_url=f"sqlite:///{(tmp_path / 'telemetry.db').as_posix()}",
        storage_root=tmp_path / "objects",
        operator_token="operator-test-token",
        max_request_bytes=64 * 1024,
        rate_limit_requests=100,
        rate_limit_window_seconds=60,
    )


@pytest.fixture
def client(settings: Settings):
    with TestClient(create_app(settings), base_url="https://testserver") as test_client:
        yield test_client


@pytest.fixture
def installation(client: TestClient) -> dict[str, str]:
    response = client.post("/v1/installations")
    assert response.status_code == 201
    return response.json()


@pytest.fixture
def diagnostics_headers(installation: dict[str, str]) -> dict[str, str]:
    return {"Authorization": f"Bearer {installation['write_credential']}"}


def envelope(
    *,
    installation_id: str,
    batch_id: str,
    event_id: str = "event-1",
    event_type: str = "gesture_outcome",
    payload: dict | None = None,
) -> dict:
    return {
        "schema_version": 1,
        "event_id": event_id,
        "batch_id": batch_id,
        "installation_id": installation_id,
        "session_id": "session-1",
        "occurred_at_ms": 1_750_000_000_000,
        "app_version": "0.1.4",
        "build_type": "release",
        "android_api": 36,
        "event_type": event_type,
        "payload": payload or {"event_type": "gesture_outcome", "outcome": "ACCEPTED"},
    }
