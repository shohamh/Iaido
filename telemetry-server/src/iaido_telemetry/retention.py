from __future__ import annotations

from datetime import datetime, timedelta, timezone

from .config import Settings
from .db import TelemetryRepository
from .storage import ObjectStorage

#: Deployment configuration may only shorten these defaults (see
#: Settings.__post_init__), never lengthen them.
DEFAULT_RETENTION_DAYS = {
    "diagnostics": Settings.DEFAULT_DIAGNOSTICS_RETENTION_DAYS,
    "research": Settings.DEFAULT_RESEARCH_RETENTION_DAYS,
}


def purge_expired(
    database: TelemetryRepository,
    storage: ObjectStorage,
    settings: Settings,
    now: datetime,
) -> dict[str, int]:
    """Delete every batch (metadata + object + event facts) older than the
    plane's configured retention window, relative to ``now``.

    Diagnostics defaults to 90 days and research to 365 days; ``settings``
    may shorten either value but the boundary itself is always enforced
    per-plane so one plane's retention window can never affect the other's
    data. Every plane purge that removes at least one batch writes a single
    audit record (action="purge") summarizing what was removed. Returns the
    number of batches purged per plane.
    """
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)

    retention_days = {
        "diagnostics": settings.diagnostics_retention_days,
        "research": settings.research_retention_days,
    }

    purged_counts: dict[str, int] = {}
    for plane, days in retention_days.items():
        cutoff_ms = int((now - timedelta(days=days)).timestamp() * 1000)
        purged = 0
        for record in list(database.list_batches(plane)):
            if record.received_at_ms < cutoff_ms:
                storage.delete(record.object_key)
                database.delete_batch(plane, record.installation_id, record.batch_id)
                database.delete_event_facts_for_batch(
                    plane, record.installation_id, record.batch_id
                )
                purged += 1
        if purged:
            database.add_audit(
                action="purge",
                plane=plane,
                installation_id="*",
                actor="system",
                detail=(
                    f"purged {purged} batch(es) older than {days}d "
                    f"(cutoff_ms={cutoff_ms})"
                ),
            )
        purged_counts[plane] = purged
    return purged_counts
