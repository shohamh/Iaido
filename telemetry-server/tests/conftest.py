from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from iaido_telemetry.app import create_app
from iaido_telemetry.config import Settings
from iaido_telemetry.db import SQLiteTestRepository
from iaido_telemetry.storage import LocalObjectStorage


EVENT_BATCH_ID = "00000000-0000-0000-0000-000000000000"
DEFAULT_EVENT_ID = "00000000-0000-0000-0000-000000000010"
DEFAULT_SESSION_ID = "00000000-0000-0000-0000-000000000002"


def queue_batch_id(sequence: int) -> str:
    return f"batch-{sequence}-1750000000000-00000000-0000-0000-0000-{sequence:012d}"


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        database_url=f"sqlite:///{(tmp_path / 'telemetry.db').as_posix()}",
        operator_token="operator-test-token",
        max_request_bytes=64 * 1024,
        rate_limit_requests=100,
        rate_limit_window_seconds=60,
        rate_limit_max_buckets=100,
    )


@pytest.fixture
def object_root(tmp_path: Path) -> Path:
    return tmp_path / "objects"


def build_test_app(settings: Settings, object_root: Path):
    return create_app(
        settings,
        database=SQLiteTestRepository(settings.database_url),
        storage=LocalObjectStorage(object_root),
    )


@pytest.fixture
def client(settings: Settings, object_root: Path):
    with TestClient(
        build_test_app(settings, object_root), base_url="https://testserver"
    ) as test_client:
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
    batch_id: str = EVENT_BATCH_ID,
    event_id: str = DEFAULT_EVENT_ID,
    event_type: str = "gesture_outcome",
    payload: dict | None = None,
) -> dict:
    return {
        "schema_version": 1,
        "event_id": event_id,
        "batch_id": batch_id,
        "installation_id": installation_id,
        "session_id": DEFAULT_SESSION_ID,
        "occurred_at_ms": 1_750_000_000_000,
        "app_version": "0.1.4",
        "build_type": "release",
        "android_api": 36,
        "event_type": event_type,
        "payload": payload or {"event_type": "gesture_outcome", "outcome": "ACCEPTED"},
    }
