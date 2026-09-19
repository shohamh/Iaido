import json
from pathlib import Path

import pytest

from iaido_telemetry.config import Settings
from iaido_telemetry.db import SQLiteTestRepository
from iaido_telemetry.exports import ExportAuthError
from iaido_telemetry.fixture_export import (
    BUNDLE_NAME,
    event_hash,
    export_research_fixtures,
    load_review_manifest,
)
from iaido_telemetry.storage import LocalObjectStorage

from conftest import envelope, queue_batch_id


OPERATOR_TOKEN = "operator-test-token"
TRACE_ID = "30000000-0000-4000-8000-0000000000aa"
CORRECTION_ID = "30000000-0000-4000-8000-0000000000bb"
WINDOW_START_MS = 1_700_000_000_000
WINDOW_END_MS = 1_900_000_000_000


def repository(settings: Settings) -> SQLiteTestRepository:
    return SQLiteTestRepository(settings.database_url)


def trace_event(installation_id: str) -> dict:
    return envelope(
        installation_id=installation_id,
        event_id="00000000-0000-0000-0000-0000000000c1",
        event_type="gesture_trace",
        payload={
            "trace_id": TRACE_ID,
            "classification": "swipe",
            "language": "ENGLISH",
            "layout_id": "qwerty",
            "algorithm_version": 1,
            "points": [
                {"pointer_id": 4, "action": 0, "time_offset_ms": 0, "x": 0.25, "y": 0.25},
                {"pointer_id": 4, "action": 2, "time_offset_ms": 42, "x": 0.31, "y": 0.25},
            ],
        },
    )


def correction_event(installation_id: str) -> dict:
    return envelope(
        installation_id=installation_id,
        event_id="00000000-0000-0000-0000-0000000000c2",
        event_type="research_correction",
        payload={
            "correction_id": CORRECTION_ID,
            "trace_id": TRACE_ID,
            "action": "manual_edit",
            "source_text": "teh",
            "final_text": "the",
            "candidates": ["the", "ten"],
            "algorithm_version": 1,
        },
    )


def post_research(client, installation, headers, events, sequence: int):
    response = client.post(
        "/v1/research/batches",
        headers=headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(sequence),
            "events": events,
        },
    )
    assert response.status_code == 202
    return response


def stored_events(settings: Settings, object_root: Path) -> list[dict]:
    storage = LocalObjectStorage(object_root)
    events: list[dict] = []
    for record in repository(settings).list_batches("research"):
        events.extend(json.loads(storage.get(record.object_key)).get("events", []))
    return events


def export(settings: Settings, object_root: Path, manifest, output_dir: Path, token=OPERATOR_TOKEN):
    return export_research_fixtures(
        repository(settings),
        LocalObjectStorage(object_root),
        settings,
        WINDOW_START_MS,
        WINDOW_END_MS,
        manifest,
        output_dir,
        operator_token=token,
    )


def test_review_manifest_is_required_before_any_export(tmp_path, settings, object_root):
    with pytest.raises(ValueError, match="review manifest"):
        export(settings, object_root, None, tmp_path)
    with pytest.raises(ValueError, match="review manifest"):
        export(settings, object_root, {}, tmp_path)
    empty_manifest = tmp_path / "empty.json"
    empty_manifest.write_text(json.dumps({"approved": []}), encoding="utf-8")
    with pytest.raises(ValueError, match="review manifest"):
        load_review_manifest(empty_manifest)


def test_export_requires_an_operator_token(tmp_path, settings, object_root):
    with pytest.raises(ExportAuthError):
        export(settings, object_root, {event_hash(trace_event("x")): "ok"}, tmp_path, token="wrong")


def test_same_reviewed_rows_produce_identical_bundle_bytes(
    client, installation, diagnostics_headers, settings, object_root, tmp_path
):
    installation_id = installation["installation_id"]
    post_research(
        client,
        installation,
        diagnostics_headers,
        [trace_event(installation_id), correction_event(installation_id)],
        1,
    )
    manifest = {event_hash(event): "reviewed" for event in stored_events(settings, object_root)}
    assert len(manifest) == 2

    first = export(settings, object_root, manifest, tmp_path / "a")
    second = export(settings, object_root, manifest, tmp_path / "b")

    assert first.name == BUNDLE_NAME
    assert first.read_bytes() == second.read_bytes()


def test_only_approved_records_are_exported_and_identifiers_are_stripped(
    client, installation, diagnostics_headers, settings, object_root, tmp_path
):
    installation_id = installation["installation_id"]
    post_research(
        client,
        installation,
        diagnostics_headers,
        [trace_event(installation_id), correction_event(installation_id)],
        1,
    )
    approved_trace = event_hash(trace_event(installation_id))

    bundle_path = export(settings, object_root, {approved_trace: "reviewed"}, tmp_path)
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    text = bundle_path.read_text(encoding="utf-8")

    assert bundle["schema_version"] == 1
    assert [fixture["fixture_id"] for fixture in bundle["fixtures"]] == ["fixture-0001"]
    assert bundle["fixtures"][0]["kind"] == "gesture_trace"
    assert bundle["fixtures"][0]["points"][0] == {
        "pointer_id": 4,
        "action": 0,
        "time_offset_ms": 0,
        "x": 0.25,
        "y": 0.25,
    }
    # The unapproved correction (and its readable span) never reaches the bundle, and neither
    # does any identifier, credential, batch, or receive timestamp.
    assert "teh" not in text
    assert CORRECTION_ID not in text
    assert installation_id not in text
    assert "occurred_at_ms" not in text
    assert "received_at_ms" not in text
    assert "session_id" not in text


def test_fixture_ids_follow_content_order_not_insertion_order(
    client, installation, diagnostics_headers, settings, object_root, tmp_path
):
    installation_id = installation["installation_id"]
    post_research(
        client,
        installation,
        diagnostics_headers,
        [trace_event(installation_id), correction_event(installation_id)],
        1,
    )

    trace_only = json.loads(
        export(settings, object_root, {event_hash(trace_event(installation_id)): "reviewed"}, tmp_path / "trace")
        .read_text(encoding="utf-8")
    )
    correction_only = json.loads(
        export(
            settings,
            object_root,
            {event_hash(correction_event(installation_id)): "reviewed"},
            tmp_path / "correction",
        ).read_text(encoding="utf-8")
    )

    # Ids are assigned from the approved content itself, so approving a different subset yields a
    # fresh, dense, deterministic numbering rather than a gap left by the filtered-out record.
    assert [fixture["fixture_id"] for fixture in trace_only["fixtures"]] == ["fixture-0001"]
    assert trace_only["fixtures"][0]["kind"] == "gesture_trace"
    assert [fixture["fixture_id"] for fixture in correction_only["fixtures"]] == ["fixture-0001"]
    assert correction_only["fixtures"][0]["kind"] == "correction"
    assert correction_only["fixtures"][0]["source_text"] == "teh"


def test_export_reads_only_the_research_plane(
    client, installation, diagnostics_headers, settings, object_root, tmp_path
):
    diagnostics_event = envelope(installation_id=installation["installation_id"])
    response = client.post(
        "/v1/diagnostics/batches",
        headers=diagnostics_headers,
        json={
            "schema_version": 1,
            "batch_id": queue_batch_id(1),
            "events": [diagnostics_event],
        },
    )
    assert response.status_code == 202

    bundle = json.loads(
        export(settings, object_root, {event_hash(diagnostics_event): "reviewed"}, tmp_path)
        .read_text(encoding="utf-8")
    )

    assert bundle["fixtures"] == []


def test_export_writes_an_audit_record(
    client, installation, diagnostics_headers, settings, object_root, tmp_path
):
    installation_id = installation["installation_id"]
    post_research(
        client,
        installation,
        diagnostics_headers,
        [trace_event(installation_id)],
        1,
    )

    export(settings, object_root, {event_hash(trace_event(installation_id)): "reviewed"}, tmp_path)

    audits = repository(settings).audit_rows(action="export", plane="research")
    assert len(audits) == 1
    assert audits[0].actor == "operator"
    assert "1 reviewed fixture" in audits[0].detail


def test_approved_record_outside_the_current_bounds_fails_the_export(
    installation, settings, object_root, tmp_path
):
    installation_id = installation["installation_id"]
    out_of_bounds = trace_event(installation_id)
    out_of_bounds["payload"]["points"] = [
        {"pointer_id": 0, "action": 0, "time_offset_ms": 0, "x": 5.0, "y": 0.5}
    ]
    too_long = correction_event(installation_id)
    too_long["payload"]["final_text"] = "a" * 65
    storage = LocalObjectStorage(object_root)
    repo = repository(settings)
    for index, event in enumerate((out_of_bounds, too_long), start=9):
        batch_id = queue_batch_id(index)
        key = storage.object_key("research", installation_id, batch_id)
        storage.put_if_absent(
            key,
            json.dumps({"schema_version": 1, "batch_id": batch_id, "events": [event]}).encode(),
        )
        repo.add_batch("research", installation_id, batch_id, f"checksum-{index}", key)

    with pytest.raises(ValueError, match="bounds"):
        export(settings, object_root, {event_hash(out_of_bounds): "reviewed"}, tmp_path / "a")
    with pytest.raises(ValueError, match="bounds"):
        export(settings, object_root, {event_hash(too_long): "reviewed"}, tmp_path / "b")


def test_review_manifest_accepts_a_json_file(tmp_path, settings, object_root):
    digest = event_hash(trace_event("00000000-0000-0000-0000-000000000003"))
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps({"approved": [digest]}), encoding="utf-8")

    assert load_review_manifest(manifest_path) == {digest: ""}
