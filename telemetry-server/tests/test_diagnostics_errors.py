import pytest

from conftest import envelope, queue_batch_id


ERROR = {"type": "IllegalStateException", "frames": ["RecognitionController.recognize(RecognitionController.kt:87)"]}


def diagnostics_batch(installation_id: str, event_type: str, payload: dict, sequence: int = 1, event_id: str | None = None):
    batch_id = queue_batch_id(sequence)
    return {
        "schema_version": 1,
        "batch_id": batch_id,
        "events": [
            envelope(
                installation_id=installation_id,
                event_id=event_id or "00000000-0000-0000-0000-0000000000e1",
                event_type=event_type,
                payload=payload,
            )
        ],
    }


def post(client, headers, batch):
    return client.post("/v1/diagnostics/batches", headers=headers, json=batch)


def test_runtime_error_is_accepted_and_counted(client, installation, diagnostics_headers):
    batch = diagnostics_batch(
        installation["installation_id"],
        "runtime_error",
        {"event_type": "runtime_error", "code": "RECOGNITION_FAILED", "error": ERROR},
    )

    assert post(client, diagnostics_headers, batch).status_code == 202

    assert client.get(
        "/v1/operator/aggregates/runtime-error-rate", headers={"Authorization": "Bearer operator-test-token"}
    ).json() == {
        "plane": "diagnostics",
        "errors": 1,
        "total": 1,
        "rate": 1.0,
    }


def test_crash_is_accepted_with_breadcrumbs_and_counted(
    client, installation, diagnostics_headers
):
    batch = diagnostics_batch(
        installation["installation_id"],
        "crash",
        {
            "event_type": "crash",
            "error": ERROR,
            "breadcrumbs": [
                {"kind": "APP_START", "count": 1},
                {"kind": "GESTURE_OUTCOME", "count": 2, "gesture_kind": "SWIPE", "outcome": "REJECTED"},
                {"kind": "RECOGNITION_LATENCY", "count": 1, "latency_bucket": "OVER_1000_MS"},
                {"kind": "RUNTIME_ERROR", "count": 1, "error_code": "UNKNOWN", "error": ERROR},
            ],
        },
    )

    assert post(client, diagnostics_headers, batch).status_code == 202

    counts = client.get(
        "/v1/operator/aggregates/crash-counts", headers={"Authorization": "Bearer operator-test-token"}
    ).json()
    assert counts == {"plane": "diagnostics", "count": 1}


def test_crash_events_are_discriminated_by_exception_class(
    client, installation, diagnostics_headers, settings
):
    batch = diagnostics_batch(
        installation["installation_id"],
        "crash",
        {"event_type": "crash", "error": {"type": "NullPointerException", "frames": []}},
    )
    assert post(client, diagnostics_headers, batch).status_code == 202

    from iaido_telemetry.db import SQLiteTestRepository

    repository = SQLiteTestRepository(settings.database_url)
    assert repository.event_discriminator_counts("diagnostics", "crash") == {
        "NullPointerException": 1
    }


@pytest.mark.parametrize(
    ("event_type", "payload"),
    [
        ("runtime_error", {"event_type": "runtime_error", "code": "NOT_A_CODE", "error": ERROR}),
        ("runtime_error", {"event_type": "runtime_error", "error": ERROR}),
        ("runtime_error", {"event_type": "runtime_error", "code": "UNKNOWN", "error": {"type": "", "frames": []}}),
        (
            "runtime_error",
            {"event_type": "runtime_error", "code": "UNKNOWN", "error": {"type": "IllegalStateException", "frames": [], "message": "typed secret"}},
        ),
        (
            "runtime_error",
            {"event_type": "runtime_error", "code": "UNKNOWN", "error": {"type": "IllegalStateException", "frames": ["C:\\Users\\me\\App.kt:12"]}},
        ),
        (
            "runtime_error",
            {"event_type": "runtime_error", "code": "UNKNOWN", "error": {"type": "IllegalStateException", "frames": ["Foo.bar(" + "x" * 200 + ".kt:1)"]}},
        ),
        (
            "runtime_error",
            {"event_type": "runtime_error", "code": "UNKNOWN", "error": {"type": "IllegalStateException", "frames": ["Foo.bar(Foo.kt:1)"] * 17}},
        ),
        (
            "crash",
            {"event_type": "crash", "error": ERROR, "breadcrumbs": [{"kind": "NOT_A_KIND", "count": 1}]},
        ),
        (
            "crash",
            {"event_type": "crash", "error": ERROR, "breadcrumbs": [{"kind": "APP_START", "count": 0}]},
        ),
        (
            "crash",
            {"event_type": "crash", "error": ERROR, "breadcrumbs": [{"kind": "APP_START", "count": 1}] * 33},
        ),
        ("gesture_outcome", {"event_type": "gesture_outcome", "outcome": "ACCEPTED", "error": ERROR}),
        ("crash", {"event_type": "crash", "error": ERROR, "sentence_context": "typed text"}),
        ("gesture_outcome", {"event_type": "gesture_outcome", "outcome": "ACCEPTED", "code": "UNKNOWN", "error": ERROR}),
    ],
)
def test_malformed_diagnostics_events_are_rejected(
    client, installation, diagnostics_headers, event_type, payload
):
    batch = diagnostics_batch(installation["installation_id"], event_type, payload, sequence=2)

    assert post(client, diagnostics_headers, batch).status_code == 422


def test_error_frames_reject_absolute_paths_and_accept_placeholders(
    client, installation, diagnostics_headers
):
    accepted = diagnostics_batch(
        installation["installation_id"],
        "runtime_error",
        {
            "event_type": "runtime_error",
            "code": "UNKNOWN",
            "error": {"type": "IllegalStateException", "frames": ["<redacted>", "Foo.bar(Foo.kt:1)"]},
        },
        sequence=3,
    )

    assert post(client, diagnostics_headers, accepted).status_code == 202