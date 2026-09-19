"""Reviewed research-fixture export.

Turns operator-approved research events into one deterministic, de-identified bundle that pure
core-engine and connected-IME tests can replay. Nothing here is reachable from the device-facing
ingestion routes: an operator token, an explicit review manifest, and an explicit time range are
all required, and only the research plane is ever read.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from pathlib import Path

from pydantic import ValidationError

from .config import Settings
from .db import TelemetryRepository
from .exports import require_operator_token
from .schemas import ResearchEnvelope
from .storage import ObjectStorage

BUNDLE_NAME = "research-fixtures-v1.json"
SCHEMA_VERSION = 1

# Fixture kinds a reviewed record can become. ``text_sample`` is accepted on the wire but is not
# a fixture kind: nothing on the device emits it, and a fixture it could produce would carry
# readable text without the trace/decision context a regression test needs.
GESTURE_TRACE = "gesture_trace"
CORRECTION = "correction"


def event_hash(event: Mapping[str, object]) -> str:
    """Stable content hash of one stored event, used to approve records for export."""
    canonical = json.dumps(event, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def load_review_manifest(manifest: object) -> dict[str, str]:
    """Normalize a review manifest into ``{content_hash: reviewer_note}``.

    Accepts either an in-memory mapping or a path to a JSON manifest shaped as
    ``{"approved": ["<hash>", ...]}`` or ``{"approved": {"<hash>": "<note>"}}``. A missing or
    empty manifest is an error: unreviewed research data must never reach a fixture file.
    """
    if manifest is None:
        raise ValueError("a review manifest is required before exporting research fixtures")

    if isinstance(manifest, (str, Path)):
        raw = json.loads(Path(manifest).read_text(encoding="utf-8"))
        approved = raw.get("approved") if isinstance(raw, dict) else raw
        if isinstance(approved, list):
            entries = {str(item): "" for item in approved}
        elif isinstance(approved, dict):
            entries = {str(key): str(value) for key, value in approved.items()}
        else:
            raise ValueError("review manifest must list approved record hashes")
    elif isinstance(manifest, Mapping):
        entries = {str(key): str(value) for key, value in manifest.items()}
    else:
        raise ValueError("review manifest must be a mapping or a path to a JSON manifest")

    if not entries:
        raise ValueError("a review manifest is required before exporting research fixtures")
    return entries


def export_research_fixtures(
    database: TelemetryRepository,
    storage: ObjectStorage,
    settings: Settings,
    start_ms: int,
    end_ms: int,
    review_manifest: object,
    output_dir: Path,
    *,
    operator_token: str,
) -> Path:
    """Write every reviewed research record in ``[start_ms, end_ms)`` to one fixture bundle.

    Records are de-identified (no installation, session, batch, credential, or receipt data, and
    no receive timestamps), ordered by content hash, and assigned deterministic fixture ids in
    that order, so exporting the same reviewed rows twice produces byte-identical output. An
    approved record that violates the current research bounds fails the export instead of being
    written in a shape tests would then replay.
    """
    require_operator_token(settings, operator_token)
    if start_ms > end_ms:
        raise ValueError("start_ms must not be greater than end_ms")

    approved = load_review_manifest(review_manifest)
    directory = Path(output_dir)
    directory.mkdir(parents=True, exist_ok=True)

    reviewed: dict[str, dict] = {}
    for record in database.list_batches("research"):
        if not (start_ms <= record.received_at_ms < end_ms):
            continue
        batch = json.loads(storage.get(record.object_key))
        for event in batch.get("events", []):
            digest = event_hash(event)
            if digest in approved and digest not in reviewed:
                reviewed[digest] = _fixture_for(event)

    fixtures = []
    for index, digest in enumerate(sorted(reviewed), start=1):
        fixtures.append({"fixture_id": f"fixture-{index:04d}", **reviewed[digest]})

    bundle = {"schema_version": SCHEMA_VERSION, "fixtures": fixtures}
    target = directory / BUNDLE_NAME
    target.write_text(
        json.dumps(bundle, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    database.add_audit(
        action="export",
        plane="research",
        installation_id="*",
        actor="operator",
        detail=f"exported {len(fixtures)} reviewed fixture(s) in [{start_ms}, {end_ms})",
    )
    return target


def _fixture_for(event: Mapping[str, object]) -> dict:
    """Validate one reviewed event against the current research bounds and de-identify it."""
    try:
        envelope = ResearchEnvelope.model_validate(event)
    except ValidationError as error:
        raise ValueError(
            f"reviewed record violates current research bounds: {error}"
        ) from error

    payload = envelope.payload
    if envelope.event_type == "gesture_trace":
        return {
            "kind": GESTURE_TRACE,
            "classification": payload.classification,
            "language": payload.language,
            "layout_id": payload.layout_id,
            "algorithm_version": payload.algorithm_version,
            "points": [point.model_dump() for point in payload.points],
        }
    if envelope.event_type == "research_correction":
        return {
            "kind": CORRECTION,
            "action": payload.action,
            "source_text": payload.source_text,
            "final_text": payload.final_text,
            "candidates": list(payload.candidates),
            "algorithm_version": payload.algorithm_version,
        }
    raise ValueError(f"reviewed record is not a supported fixture kind: {envelope.event_type}")
