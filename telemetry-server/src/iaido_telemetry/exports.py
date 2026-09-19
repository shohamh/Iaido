from __future__ import annotations

import json
from typing import IO, Literal

from .auth import credentials_match
from .config import Settings
from .db import TelemetryRepository
from .storage import PLANES, ObjectStorage

Plane = Literal["diagnostics", "research"]


class ExportAuthError(Exception):
    """Raised when an export is attempted without a valid operator token."""


def export_plane(
    database: TelemetryRepository,
    storage: ObjectStorage,
    settings: Settings,
    plane: Plane,
    start_ms: int,
    end_ms: int,
    output: IO[str],
    *,
    operator_token: str,
) -> int:
    """Write every event received in [start_ms, end_ms) for ``plane`` to
    ``output`` as newline-delimited JSON (NDJSON), one line per event.

    Requires a valid operator token (checked independently of any HTTP-layer
    authentication, so this function is safe to call directly from operator
    tooling/scripts). Only ever reads ``plane``'s own batches/objects -- the
    other plane's tables and object-storage prefix are never touched, so it
    is structurally impossible for an export to include the other plane's
    data even if it exists for the same installation and time range.

    Writes a single audit record (action="export") describing the plane and
    time range that were exported. Returns the number of batches exported.
    """
    if plane not in PLANES:
        raise ValueError("unknown telemetry plane")
    if not credentials_match(operator_token, settings.operator_token):
        raise ExportAuthError("invalid operator credential")
    if start_ms > end_ms:
        raise ValueError("start_ms must not be greater than end_ms")

    exported = 0
    for record in database.list_batches(plane):
        if not (start_ms <= record.received_at_ms < end_ms):
            continue
        raw = storage.get(record.object_key)
        batch = json.loads(raw)
        for event in batch.get("events", []):
            line = {
                "plane": plane,
                "installation_id": record.installation_id,
                "batch_id": record.batch_id,
                "received_at_ms": record.received_at_ms,
                "event": event,
            }
            output.write(json.dumps(line, sort_keys=True))
            output.write("\n")
        exported += 1

    database.add_audit(
        action="export",
        plane=plane,
        installation_id="*",
        actor="operator",
        detail=f"exported {exported} batch(es) in [{start_ms}, {end_ms})",
    )
    return exported
