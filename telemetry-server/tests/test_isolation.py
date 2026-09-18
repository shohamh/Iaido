import pytest

from conftest import envelope, queue_batch_id


ANDROID_QUEUE_BATCH_ID = "batch-7-1750000000000-10000000-0000-4000-8000-000000000007"
ANDROID_EVENT_BATCH_ID = "20000000-0000-4000-8000-000000000008"


def test_diagnostics_rejects_research_text_and_trace(client, diagnostics_headers):
    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "batch_id": queue_batch_id(1),
            "events": [{"event_type": "gesture", "text": "secret", "points": []}],
        },
    )

    assert response.status_code == 422


def test_diagnostics_rejects_readable_text_in_nested_payload(
    client, installation, diagnostics_headers
):
    event = envelope(installation_id=installation["installation_id"])
    event["payload"]["text"] = "readable secret"

    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": queue_batch_id(1), "events": [event]},
    )

    assert response.status_code == 422


def test_diagnostics_rejects_raw_trace_in_nested_payload(
    client, installation, diagnostics_headers
):
    event = envelope(installation_id=installation["installation_id"])
    event["payload"]["points"] = [{"x": 0.5, "y": 0.5}]

    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": queue_batch_id(1), "events": [event]},
    )

    assert response.status_code == 422


@pytest.mark.parametrize(
    ("field", "readable_value"),
    [
        ("event_id", "candidate"),
        ("batch_id", "clipboard"),
        ("session_id", "host-name"),
        ("app_version", "candidate"),
        ("build_type", "clipboard"),
    ],
)
def test_diagnostics_rejects_readable_metadata_channels(
    client, installation, diagnostics_headers, field, readable_value
):
    event = envelope(
        installation_id=installation["installation_id"],
        batch_id=ANDROID_EVENT_BATCH_ID,
    )
    event[field] = readable_value

    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": ANDROID_QUEUE_BATCH_ID,
            "events": [event],
        },
    )

    assert response.status_code == 422


def test_android_shaped_research_batch_is_normalized_at_server_boundary(
    client, installation, diagnostics_headers
):
    event = envelope(
        installation_id=installation["installation_id"],
        batch_id=ANDROID_EVENT_BATCH_ID,
        event_id="30000000-0000-4000-8000-000000000009",
        event_type="text_sample",
        payload={"text": "bounded sample"},
    )

    response = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": ANDROID_QUEUE_BATCH_ID,
            "events": [event],
        },
    )

    assert response.status_code == 202
    assert response.json() == {"batch_id": ANDROID_QUEUE_BATCH_ID, "accepted": True}


def test_research_accepts_only_bounded_text_and_normalized_trace(
    client, installation, diagnostics_headers
):
    outer_batch_id = queue_batch_id(1)
    text_event = envelope(
        installation_id=installation["installation_id"],
        event_id="00000000-0000-0000-0000-000000000011",
        event_type="text_sample",
        payload={"text": "bounded sample"},
    )
    trace_event = envelope(
        installation_id=installation["installation_id"],
        event_id="00000000-0000-0000-0000-000000000012",
        event_type="gesture_trace",
        payload={
            "points": [{"x": 0.0, "y": 1.0}, {"x": 0.5, "y": 0.25}],
        },
    )

    accepted = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": outer_batch_id,
            "events": [text_event, trace_event],
        },
    )
    too_long = text_event | {
        "event_id": "00000000-0000-0000-0000-000000000013",
        "payload": {"text": "x" * 257},
    }
    rejected_text = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(2),
            "events": [too_long],
        },
    )
    out_of_bounds = trace_event | {
        "event_id": "00000000-0000-0000-0000-000000000014",
        "payload": {
            "points": [{"x": 1.01, "y": 0.5}],
        },
    }
    rejected_trace = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(3),
            "events": [out_of_bounds],
        },
    )
    extra_field = text_event | {
        "event_id": "00000000-0000-0000-0000-000000000015",
        "payload": {
            "text": "sample",
            "clipboard": "not permitted",
        },
    }
    rejected_extra = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(4),
            "events": [extra_field],
        },
    )

    assert accepted.status_code == 202
    assert rejected_text.status_code == 422
    assert rejected_trace.status_code == 422
    assert rejected_extra.status_code == 422


def test_planes_use_separate_database_tables_and_object_prefixes(
    client, installation, diagnostics_headers, object_root
):
    outer_batch_id = queue_batch_id(1)
    diagnostics_event = envelope(installation_id=installation["installation_id"])
    research_event = envelope(
        installation_id=installation["installation_id"],
        event_id="00000000-0000-0000-0000-000000000016",
        event_type="text_sample",
        payload={"text": "sample"},
    )

    diagnostics = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": outer_batch_id,
            "events": [diagnostics_event],
        },
    )
    research = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": outer_batch_id,
            "events": [research_event],
        },
    )

    assert diagnostics.status_code == 202
    assert research.status_code == 202
    assert len(list((object_root / "diagnostics").rglob("*.json"))) == 1
    assert len(list((object_root / "research").rglob("*.json"))) == 1


def test_installation_write_credential_cannot_read_operator_data(
    client, diagnostics_headers
):
    response = client.get(
        "/v1/operator/diagnostics/batches", headers=diagnostics_headers
    )

    assert response.status_code == 401


def test_operator_route_uses_only_server_configured_bearer_token(client, installation):
    missing = client.get("/v1/operator/research/batches")
    deletion_credential = client.get(
        "/v1/operator/research/batches",
        headers={"Authorization": f"Bearer {installation['deletion_credential']}"},
    )
    operator = client.get(
        "/v1/operator/research/batches",
        headers={"Authorization": "Bearer operator-test-token"},
    )

    assert missing.status_code == 401
    assert deletion_credential.status_code == 401
    assert operator.status_code == 200
    assert operator.json() == {"plane": "research", "batches": []}
