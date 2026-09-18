from conftest import envelope


def test_diagnostics_rejects_research_text_and_trace(client, diagnostics_headers):
    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "batch_id": "b1",
            "events": [{"event_type": "gesture", "text": "secret", "points": []}],
        },
    )

    assert response.status_code == 422


def test_diagnostics_rejects_readable_text_in_nested_payload(
    client, installation, diagnostics_headers
):
    event = envelope(installation_id=installation["installation_id"], batch_id="b1")
    event["payload"]["text"] = "readable secret"

    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": "b1", "events": [event]},
    )

    assert response.status_code == 422


def test_diagnostics_rejects_raw_trace_in_nested_payload(
    client, installation, diagnostics_headers
):
    event = envelope(installation_id=installation["installation_id"], batch_id="b1")
    event["payload"]["points"] = [{"x": 0.5, "y": 0.5}]

    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": "b1", "events": [event]},
    )

    assert response.status_code == 422


def test_research_accepts_only_bounded_text_and_normalized_trace(
    client, installation, diagnostics_headers
):
    text_event = envelope(
        installation_id=installation["installation_id"],
        batch_id="research-1",
        event_id="text-1",
        event_type="text_sample",
        payload={"event_type": "text_sample", "text": "bounded sample"},
    )
    trace_event = envelope(
        installation_id=installation["installation_id"],
        batch_id="research-1",
        event_id="trace-1",
        event_type="gesture_trace",
        payload={
            "event_type": "gesture_trace",
            "points": [{"x": 0.0, "y": 1.0}, {"x": 0.5, "y": 0.25}],
        },
    )

    accepted = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": "research-1",
            "events": [text_event, trace_event],
        },
    )
    too_long = text_event | {
        "event_id": "text-2",
        "batch_id": "research-2",
        "payload": {"event_type": "text_sample", "text": "x" * 257},
    }
    rejected_text = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": "research-2", "events": [too_long]},
    )
    out_of_bounds = trace_event | {
        "event_id": "trace-2",
        "batch_id": "research-3",
        "payload": {
            "event_type": "gesture_trace",
            "points": [{"x": 1.01, "y": 0.5}],
        },
    }
    rejected_trace = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": "research-3", "events": [out_of_bounds]},
    )
    extra_field = text_event | {
        "event_id": "text-3",
        "batch_id": "research-4",
        "payload": {
            "event_type": "text_sample",
            "text": "sample",
            "clipboard": "not permitted",
        },
    }
    rejected_extra = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": "research-4", "events": [extra_field]},
    )

    assert accepted.status_code == 202
    assert rejected_text.status_code == 422
    assert rejected_trace.status_code == 422
    assert rejected_extra.status_code == 422


def test_planes_use_separate_database_tables_and_object_prefixes(
    client, installation, diagnostics_headers, settings
):
    diagnostics_event = envelope(
        installation_id=installation["installation_id"], batch_id="same-id"
    )
    research_event = envelope(
        installation_id=installation["installation_id"],
        batch_id="same-id",
        event_id="research-event",
        event_type="text_sample",
        payload={"event_type": "text_sample", "text": "sample"},
    )

    diagnostics = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": "same-id",
            "events": [diagnostics_event],
        },
    )
    research = client.post(
        "/v1/research/batches",
        headers=diagnostics_headers,
        json={"schema_version": 1, "batch_id": "same-id", "events": [research_event]},
    )

    assert diagnostics.status_code == 202
    assert research.status_code == 202
    assert len(list((settings.storage_root / "diagnostics").rglob("*.json"))) == 1
    assert len(list((settings.storage_root / "research").rglob("*.json"))) == 1


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
