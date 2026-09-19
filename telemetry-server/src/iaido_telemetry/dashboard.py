"""Operator dashboard: a dependency-free HTML view over the stored telemetry planes.

The dashboard is a thin read-only projection of what is already in the repository and object
storage. It renders both planes on one page but keeps them in clearly labelled, separate sections,
and every batch link carries an explicit plane, so nothing here can mix diagnostics and research
data. It has no external assets, no JavaScript, and no new secret: the operator token is the only
credential, accepted as HTTP Basic (browser prompt) or Bearer.

Every stored value is HTML-escaped before rendering - research records can contain readable
user-typed text, which must never be interpreted as markup.
"""

from __future__ import annotations

import html
import json
import time
from datetime import datetime, timezone

from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import HTMLResponse

from .auth import basic_or_bearer, credentials_match
from .config import Settings
from .db import TelemetryRepository
from .storage import ObjectStorage

PLANES = ("diagnostics", "research")
RECENT_BATCHES = 25
RECENT_AUDIT_ROWS = 25

# event_type -> label, for the aggregate facts recorded at ingestion.
AGGREGATE_FACTS = (
    ("gesture_outcome", "Gesture outcomes"),
    ("suggestion_action", "Correction actions"),
    ("latency", "Latency buckets"),
    ("runtime_error", "Runtime errors"),
    ("crash", "Crashes"),
)

STYLES = """
body { background: #ffffff; color: #1b1b1b; font: 14px/1.5 system-ui, -apple-system, Segoe UI, sans-serif; margin: 0 auto; max-width: 1100px; padding: 24px; }
h1 { font-size: 20px; margin: 0 0 4px; }
h2 { font-size: 16px; margin: 28px 0 8px; }
h3 { font-size: 14px; margin: 16px 0 4px; }
p.meta { color: #5f6368; margin: 0 0 20px; }
a { color: #0b57d0; }
table { border-collapse: collapse; width: 100%; margin: 8px 0 16px; background: #ffffff; }
th, td { border: 1px solid #d0d0d0; padding: 4px 8px; text-align: left; vertical-align: top; color: #1b1b1b; }
th { background: #f1f3f4; font-weight: 600; }
code, pre { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; color: #1b1b1b; }
pre { background: #f7f7f7; border: 1px solid #e0e0e0; padding: 8px; overflow-x: auto; }
.note { background: #fff8e1; border: 1px solid #f0d98c; color: #1b1b1b; padding: 8px 12px; }
.empty { color: #5f6368; font-style: italic; }
"""


def _esc(value: object) -> str:
    return html.escape(str(value), quote=True)


def _timestamp(received_at_ms: int | None) -> str:
    if not received_at_ms:
        return "-"
    return datetime.fromtimestamp(received_at_ms / 1000, tz=timezone.utc).strftime(
        "%Y-%m-%d %H:%M:%SZ"
    )


def _page(title: str, body: str) -> str:
    return (
        "<!doctype html>\n"
        '<html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>{_esc(title)}</title><style>{STYLES}</style></head>"
        f"<body>{body}</body></html>\n"
    )


def _table(headers: list[str], rows: list[list[str]], *, empty: str) -> str:
    if not rows:
        return f'<p class="empty">{_esc(empty)}</p>'
    head = "".join(f"<th>{_esc(header)}</th>" for header in headers)
    body = "".join(
        "<tr>" + "".join(f"<td>{cell}</td>" for cell in row) + "</tr>" for row in rows
    )
    return f"<table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


def render_dashboard(
    database: TelemetryRepository,
    *,
    now_ms: int | None = None,
) -> str:
    """Render the overview page: per-plane summaries, recent batches, aggregates, audit log."""
    generated_at = _timestamp(now_ms if now_ms is not None else int(time.time() * 1000))
    parts = [
        "<h1>Iaido telemetry</h1>",
        f'<p class="meta">Read-only operator view. Generated {_esc(generated_at)} (UTC). '
        "Both planes are listed separately; open a batch to inspect its stored events.</p>",
        '<p class="note">Research batches can contain readable typing content from the affected '
        "span. Treat everything on this page as sensitive and delete it when it is no longer "
        "needed.</p>",
    ]

    for plane in PLANES:
        batches = list(database.list_batches(plane))
        events = database.total_event_count(plane)
        newest = max((record.received_at_ms for record in batches), default=None)
        parts.append(f"<h2>{_esc(plane.capitalize())}</h2>")
        parts.append(
            "<p>"
            f"<strong>{len(batches)}</strong> batch(es), <strong>{events}</strong> event(s) "
            f"recorded, newest {_esc(_timestamp(newest))}.</p>"
        )
        rows = [
            [
                _esc(_timestamp(record.received_at_ms)),
                f'<a href="/batches/{_esc(plane)}/{_esc(record.installation_id)}/{_esc(record.batch_id)}">'
                f"{_esc(record.batch_id)}</a>",
                f"<code>{_esc(record.installation_id)}</code>",
                f"<code>{_esc(record.checksum[:16])}…</code>",
            ]
            for record in sorted(batches, key=lambda item: item.received_at_ms, reverse=True)[
                :RECENT_BATCHES
            ]
        ]
        parts.append(
            _table(
                ["Received (UTC)", "Batch", "Installation", "Checksum"],
                rows,
                empty=f"No {plane} batches stored yet.",
            )
        )

    parts.append("<h2>Diagnostics aggregates</h2>")
    parts.append(
        "<p>Counts recorded per event at ingestion; they never include raw text or touch "
        "points.</p>"
    )
    aggregate_rows = []
    for event_type, label in AGGREGATE_FACTS:
        counts = database.event_discriminator_counts("diagnostics", event_type)
        total = database.event_counts("diagnostics", event_type)
        if not counts and not total:
            continue
        rendered = ", ".join(f"{_esc(key)}: {_esc(value)}" for key, value in sorted(counts.items()))
        aggregate_rows.append([_esc(label), _esc(total), rendered or "-"])
    parts.append(_table(["Metric", "Events", "Breakdown"], aggregate_rows, empty="No diagnostics events recorded yet."))

    parts.append("<h2>Audit log</h2>")
    parts.append(
        "<p>Deletions, retention purges, and exports, newest first.</p>"
    )
    audit_rows = [
        [
            _esc(_timestamp(row.created_at_ms)),
            _esc(row.action),
            _esc(row.plane),
            f"<code>{_esc(row.installation_id)}</code>",
            _esc(row.actor),
            _esc(row.detail),
        ]
        for row in list(database.audit_rows())[-RECENT_AUDIT_ROWS:][::-1]
    ]
    parts.append(
        _table(
            ["When (UTC)", "Action", "Plane", "Installation", "Actor", "Detail"],
            audit_rows,
            empty="No operator activity recorded yet.",
        )
    )

    return _page("Iaido telemetry", "".join(parts))


def render_batch(
    database: TelemetryRepository,
    storage: ObjectStorage,
    plane: str,
    installation_id: str,
    batch_id: str,
) -> str:
    """Render one stored batch: its envelope fields and every event it contains."""
    if plane not in PLANES:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown plane")
    record = database.batch(plane, installation_id, batch_id)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown batch")
    batch = json.loads(storage.get(record.object_key))

    parts = [
        f'<p><a href="/">← all batches</a></p>',
        f"<h1>{_esc(plane)} batch</h1>",
        f'<p class="meta">Received {_esc(_timestamp(record.received_at_ms))} (UTC).</p>',
        _table(
            ["Field", "Value"],
            [
                ["Batch id", f"<code>{_esc(record.batch_id)}</code>"],
                ["Installation", f"<code>{_esc(record.installation_id)}</code>"],
                ["Object key", f"<code>{_esc(record.object_key)}</code>"],
                ["Checksum", f"<code>{_esc(record.checksum)}</code>"],
                ["Schema version", _esc(batch.get("schema_version", "-"))],
                ["Events", _esc(len(batch.get("events", [])))],
            ],
            empty="No batch metadata.",
        ),
    ]

    envelope_rows = []
    for index, event in enumerate(batch.get("events", [])):
        envelope_rows.append(
            [
                _esc(index),
                _esc(event.get("event_type", "-")),
                _esc(_timestamp(event.get("occurred_at_ms"))),
                f"<code>{_esc(event.get('event_id', '-'))}</code>",
                f"<code>{_esc(event.get('session_id', '-'))}</code>",
                _esc(event.get("app_version", "-")),
                _esc(event.get("build_type", "-")),
                _esc(event.get("android_api", "-")),
            ]
        )
    parts.append("<h2>Envelopes</h2>")
    parts.append(
        _table(
            ["#", "Event type", "Occurred (UTC)", "Event id", "Session", "App", "Build", "API"],
            envelope_rows,
            empty="No events in this batch.",
        )
    )

    parts.append("<h2>Payloads</h2>")
    parts.append(
        '<p class="note">Raw stored payloads, escaped. Diagnostics payloads carry enums and '
        "buckets only; research payloads can contain the affected span and normalized touch "
        "points.</p>"
    )
    for index, event in enumerate(batch.get("events", [])):
        payload = json.dumps(event.get("payload", {}), indent=2, sort_keys=True, ensure_ascii=False)
        parts.append(
            f"<h3>#{_esc(index)} {_esc(event.get('event_type', '-'))}</h3>"
            f"<pre>{_esc(payload)}</pre>"
        )

    return _page(f"{plane} batch {batch_id}", "".join(parts))


def register_dashboard(
    app: FastAPI,
    settings: Settings,
    database: TelemetryRepository,
    storage: ObjectStorage,
) -> None:
    """Register the operator dashboard routes on [app]."""

    def require_operator(request: Request) -> None:
        username, credential = basic_or_bearer(request)
        if username is None:
            authorized = credentials_match(credential, settings.operator_token)
        else:
            authorized = credentials_match(
                username, settings.dashboard_username
            ) and credentials_match(credential, settings.effective_dashboard_password)
        if not authorized:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid operator credential",
                headers={"WWW-Authenticate": 'Basic realm="Iaido telemetry", charset="UTF-8"'},
            )

    @app.get(
        "/",
        response_class=HTMLResponse,
        dependencies=[Depends(require_operator)],
    )
    def dashboard() -> HTMLResponse:
        return HTMLResponse(render_dashboard(database))

    @app.get(
        "/batches/{plane}/{installation_id}/{batch_id}",
        response_class=HTMLResponse,
        dependencies=[Depends(require_operator)],
    )
    def batch_detail(plane: str, installation_id: str, batch_id: str) -> HTMLResponse:
        return HTMLResponse(render_batch(database, storage, plane, installation_id, batch_id))
