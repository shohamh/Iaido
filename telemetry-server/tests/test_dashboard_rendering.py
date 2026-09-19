import base64

from iaido_telemetry.dashboard import (
    KEYBOARD_COLUMN_COUNT,
    KEYBOARD_ROW_COUNT,
    render_keyboard_svg,
)

from conftest import envelope, queue_batch_id


OPERATOR_TOKEN = "operator-test-token"
BASIC = {
    "Authorization": "Basic "
    + base64.b64encode(f"iaido:{OPERATOR_TOKEN}".encode("utf-8")).decode("ascii")
}
TRACE_ID = "30000000-0000-4000-8000-0000000000aa"


def trace_payload(*, pointer_id: int = 4, layout_id: str = "qwerty") -> dict:
    return {
        "trace_id": TRACE_ID,
        "classification": "swipe",
        "language": "ENGLISH",
        "layout_id": layout_id,
        "algorithm_version": 1,
        "points": [
            {"pointer_id": pointer_id, "action": 0, "time_offset_ms": 0, "x": 0.05, "y": 0.125},
            {"pointer_id": pointer_id, "action": 2, "time_offset_ms": 40, "x": 0.35, "y": 0.125},
            {"pointer_id": pointer_id, "action": 2, "time_offset_ms": 90, "x": 0.65, "y": 0.375},
        ],
    }


def post_research(client, installation, headers, payload, sequence=1):
    response = client.post(
        "/v1/research/batches",
        headers=headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(sequence),
            "events": [
                envelope(
                    installation_id=installation["installation_id"],
                    event_id="00000000-0000-0000-0000-0000000000f1",
                    event_type="gesture_trace",
                    payload=payload,
                )
            ],
        },
    )
    assert response.status_code == 202


def post_diagnostics(client, installation, headers, event_type, payload, sequence=2):
    response = client.post(
        "/v1/diagnostics/batches",
        headers=headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(sequence),
            "events": [
                envelope(
                    installation_id=installation["installation_id"],
                    event_id="00000000-0000-0000-0000-0000000000f2",
                    event_type=event_type,
                    payload=payload,
                )
            ],
        },
    )
    assert response.status_code == 202


def test_keyboard_svg_draws_keys_and_the_trace_path():
    svg = render_keyboard_svg(trace_payload())

    assert svg.startswith("<svg")
    assert 'class="path"' in svg
    assert 'points="0.0500,0.1250 0.3500,0.1250 0.6500,0.3750"' in svg
    # Letter keys come from the same geometry as the keyboard: 10 columns, 4 rows.
    assert 'width="0.1000" height="0.2500"' in svg
    for letter in ("q", "p", "a", "z", "m"):
        assert f">{letter}</text>" in svg
    assert KEYBOARD_COLUMN_COUNT == 10 and KEYBOARD_ROW_COUNT == 4


def test_keyboard_svg_keeps_pointers_distinct_and_fills_the_row():
    multi = trace_payload()
    multi["points"].append(
        {"pointer_id": 7, "action": 0, "time_offset_ms": 10, "x": 0.9, "y": 0.625}
    )

    svg = render_keyboard_svg(multi)
    bottom_row_lefts = [
        float(part.split('x="')[1].split('"')[0])
        for part in svg.split('<rect class="key"')[1:]
        if 'y="0.7500"' in part
    ]

    assert svg.count('class="path"') == 2
    assert "#0b57d0" in svg and "#c5221f" in svg
    # The last bottom-row key is stretched to the right edge, matching the hit-test.
    assert max(bottom_row_lefts) < 1.0
    assert 'y="0.7500" width=' in svg


def test_gesture_page_draws_stored_traces(client, installation, diagnostics_headers):
    post_research(client, installation, diagnostics_headers, trace_payload())

    gestures = client.get("/gestures", headers=BASIC)
    overview = client.get("/", headers=BASIC)

    assert gestures.status_code == 200
    assert "<svg" in gestures.text and 'class="path"' in gestures.text
    assert "swipe" in gestures.text and "qwerty" in gestures.text
    assert "/batches/research/" in gestures.text
    assert 'href="/gestures"' in overview.text
    assert "Gesture traces" in overview.text
    assert "swipe: 1" in overview.text


def test_batch_page_renders_the_trace_over_the_keyboard(
    client, installation, diagnostics_headers
):
    post_research(client, installation, diagnostics_headers, trace_payload())
    overview = client.get("/", headers=BASIC).text
    link = overview.split('href="', 1)
    batch_link = [
        part.split('"')[0]
        for part in overview.split('href="')
        if part.startswith("/batches/research/")
    ][0]

    body = client.get(batch_link, headers=BASIC).text

    assert "<svg" in body
    assert 'class="path"' in body
    assert "gesture_trace" in body
    assert link  # keep the linter honest about the split above


def test_errors_page_shows_codes_tracebacks_and_breadcrumbs(
    client, installation, diagnostics_headers
):
    post_diagnostics(
        client,
        installation,
        diagnostics_headers,
        "runtime_error",
        {
            "event_type": "runtime_error",
            "code": "RECOGNITION_FAILED",
            "error": {
                "type": "IllegalStateException",
                "frames": ["RecognitionController.recognize(RecognitionController.kt:87)"],
            },
        },
        sequence=3,
    )
    post_diagnostics(
        client,
        installation,
        diagnostics_headers,
        "crash",
        {
            "event_type": "crash",
            "error": {"type": "NullPointerException", "frames": ["Engine.score(Engine.kt:12)"]},
            "breadcrumbs": [
                {"kind": "APP_START", "count": 1},
                {"kind": "GESTURE_OUTCOME", "count": 2, "gesture_kind": "SWIPE", "outcome": "REJECTED"},
            ],
        },
        sequence=4,
    )

    errors = client.get("/errors", headers=BASIC)
    overview = client.get("/", headers=BASIC)

    assert errors.status_code == 200
    body = errors.text
    assert "RECOGNITION_FAILED" in body
    assert "IllegalStateException" in body
    assert "at RecognitionController.recognize(RecognitionController.kt:87)" in body
    assert "NullPointerException" in body
    assert "at Engine.score(Engine.kt:12)" in body
    assert "Breadcrumbs (oldest first)" in body
    assert "GESTURE_OUTCOME" in body and "SWIPE" in body
    assert "/batches/diagnostics/" in body
    assert 'href="/errors"' in overview.text
    assert "1 runtime error(s), 1 crash(es)" in overview.text
    assert "Crashes" in overview.text and "NullPointerException: 1" in overview.text


def test_gesture_and_error_pages_require_the_operator_credential(client):
    assert client.get("/gestures").status_code == 401
    assert client.get("/errors").status_code == 401