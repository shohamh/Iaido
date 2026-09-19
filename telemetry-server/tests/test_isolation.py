import json
from pathlib import Path

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


def test_android_telemetry_batch_fixture_is_accepted_without_translation(
    client, installation, diagnostics_headers
):
    fixture = Path(__file__).parent / "fixtures" / "android-research-batch.json"
    batch = json.loads(fixture.read_text(encoding="utf-8"))
    for event in batch["events"]:
        event["installation_id"] = installation["installation_id"]
    response = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json=batch,
    )

    assert response.status_code == 202
    assert response.json() == {"batch_id": ANDROID_QUEUE_BATCH_ID, "accepted": True}


TRACE_ID = "30000000-0000-4000-8000-0000000000aa"


def research_envelope(installation_id: str, event_id: str, event_type: str, payload: dict):
    return envelope(
        installation_id=installation_id,
        event_id=event_id,
        event_type=event_type,
        payload=payload,
    )


def point(x: float, y: float, *, pointer_id: int = 4, action: int = 0, time_offset_ms: int = 0):
    return {
        "pointer_id": pointer_id,
        "action": action,
        "time_offset_ms": time_offset_ms,
        "x": x,
        "y": y,
    }


def trace_payload(**overrides) -> dict:
    payload = {
        "trace_id": TRACE_ID,
        "classification": "swipe",
        "language": "ENGLISH",
        "layout_id": "qwerty",
        "algorithm_version": 1,
        "points": [point(0.25, 0.25), point(0.31, 0.25, action=2, time_offset_ms=42)],
    }
    payload.update(overrides)
    return payload


def correction_payload(**overrides) -> dict:
    payload = {
        "correction_id": "30000000-0000-4000-8000-0000000000bb",
        "trace_id": TRACE_ID,
        "action": "manual_edit",
        "source_text": "teh",
        "final_text": "the",
        "candidates": ["the", "ten"],
        "algorithm_version": 1,
    }
    payload.update(overrides)
    return payload


def post_research(client, headers, events, sequence: int):
    return client.post(
        "/v1/research/batches",
        headers=headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(sequence),
            "events": events,
        },
    )


def test_research_accepts_bounded_text_traces_and_corrections(
    client, installation, diagnostics_headers
):
    installation_id = installation["installation_id"]
    accepted = post_research(
        client,
        diagnostics_headers,
        [
            research_envelope(
                installation_id,
                "00000000-0000-0000-0000-000000000011",
                "text_sample",
                {"text": "bounded sample"},
            ),
            research_envelope(
                installation_id,
                "00000000-0000-0000-0000-000000000012",
                "gesture_trace",
                trace_payload(),
            ),
            research_envelope(
                installation_id,
                "00000000-0000-0000-0000-000000000013",
                "research_correction",
                correction_payload(),
            ),
            research_envelope(
                installation_id,
                "00000000-0000-0000-0000-000000000014",
                "research_correction",
                correction_payload(trace_id=None),
            ),
        ],
        1,
    )

    assert accepted.status_code == 202
    assert accepted.json() == {"batch_id": queue_batch_id(1), "accepted": True}


@pytest.mark.parametrize(
    ("event_type", "payload"),
    [
        ("text_sample", {"text": "x" * 257}),
        ("text_sample", {"text": "sample", "clipboard": "not permitted"}),
        ("gesture_trace", trace_payload(points=[point(1.01, 0.5)])),
        ("gesture_trace", trace_payload(points=[point(0.5, -0.01)])),
        ("gesture_trace", trace_payload(points=[])),
        ("gesture_trace", trace_payload(points=[point(0.5, 0.5) for _ in range(513)])),
        (
            "gesture_trace",
            trace_payload(
                points=[point(0.5, 0.5, time_offset_ms=9), point(0.6, 0.5, time_offset_ms=1)]
            ),
        ),
        ("gesture_trace", trace_payload(classification="sentence")),
        ("gesture_trace", trace_payload(language="KLINGON")),
        ("gesture_trace", trace_payload(layout_id="")),
        ("gesture_trace", trace_payload(points=[point(0.5, 0.5, pointer_id=-1)])),
        ("gesture_trace", trace_payload(algorithm_version=0)),
        ("gesture_trace", trace_payload(trace_id="not-a-uuid")),
        ("research_correction", correction_payload(action="silent_rewrite")),
        ("research_correction", correction_payload(source_text="")),
        ("research_correction", correction_payload(final_text="x" * 65)),
        ("research_correction", correction_payload(candidates=["x"] * 6)),
        ("research_correction", correction_payload(candidates=["x" * 65])),
        ("research_correction", correction_payload(algorithm_version=0)),
        ("research_correction", correction_payload() | {"sentence_context": "the secret document"}),
        ("raw_touch_path", {"points": [point(0.0, 1.0)]}),
    ],
)
def test_research_rejects_out_of_bounds_and_unknown_payloads(
    client, installation, diagnostics_headers, event_type, payload
):
    response = post_research(
        client,
        diagnostics_headers,
        [
            research_envelope(
                installation["installation_id"],
                "00000000-0000-0000-0000-000000000020",
                event_type,
                payload,
            )
        ],
        2,
    )

    assert response.status_code == 422


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
