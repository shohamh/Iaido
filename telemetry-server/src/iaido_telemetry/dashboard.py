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

RESEARCH_AGGREGATE_FACTS = (
    ("gesture_trace", "Gesture traces"),
    ("research_correction", "Corrections"),
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
.card { display: inline-block; vertical-align: top; width: 340px; margin: 0 16px 16px 0; }
figure.trace { margin: 0 0 8px; }
.error { margin: 0 0 16px; }
.traceback { background: #f7f7f7; border: 1px solid #e0e0e0; padding: 8px; white-space: pre; }
h4 { font-size: 13px; margin: 8px 0 4px; }
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


# --- Virtual keyboard -------------------------------------------------------
# Mirrors app/src/main/kotlin/com/iaido/app/KeyboardGeometry.kt and KeyboardInputView.kt: a
# 10-column, 4-row surface (each key is as tall as it is wide, so a row is 1/4 of the surface
# height), three letter rows with centred offsets, and a weighted bottom row. Research trace
# points are normalized to exactly that surface, so the SVG can draw the keys and the path in the
# same [0, 1] space with no scaling.
KEYBOARD_COLUMN_COUNT = 10
KEYBOARD_ROW_COUNT = 4
LETTER_ROWS = {
    "qwerty": (("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)),
    "hebrew": (
        ("\u05e7\u05e8\u05d0\u05d8\u05d5\u05df\u05dd\u05e4", 1.5),
        ("\u05e9\u05d3\u05d2\u05db\u05e2\u05d9\u05d7\u05dc\u05da\u05e3", 1.5),
        ("\u05d6\u05e1\u05d1\u05d4\u05e0\u05de\u05e6\u05ea\u05e5", 1.5),
    ),
}
BOTTOM_ROWS = {
    "qwerty": (("globe", 1.0), ("settings", 1.0), ("'", 1.0), ("?", 1.0), (",", 1.0), (".", 1.0), ("space", 3.0), ("del", 1.0)),
    "hebrew": (("globe", 1.0), ("settings", 1.0), ("\u00b3", 1.0), ("\u00b4", 1.0), ("space", 3.0), ("del", 1.0)),
}
KEY_LABELS = {"globe": "\U0001F310", "settings": "\u2699", "space": "\u2423", "del": "\u232B"}
POINTER_COLORS = ("#0b57d0", "#c5221f", "#137333", "#8430ce", "#b06000", "#0e7490")

KEY_LABEL_FONT_SIZE = 0.055
# A glyph's visual centre sits roughly 0.35em above its baseline. `dominant-baseline: middle` would
# say that for us, but support differs between renderers (it is ignored by some mobile browsers, and
# the labels then drift to the bottom of their keys), so the offset is applied explicitly here and
# every engine places a label at the same spot.
KEY_LABEL_BASELINE_OFFSET = KEY_LABEL_FONT_SIZE * 0.35

# SVG presentation lives with the stylesheet's palette; the trace is drawn as one polyline per
# pointer so a multi-touch gesture keeps its fingers visually distinct.
SVG_STYLE = f"""
.key {{ fill: none; stroke: #9aa0a6; stroke-width: 0.006; }}
.key-label {{ fill: #5f6368; font-size: {KEY_LABEL_FONT_SIZE}px; text-anchor: middle; }}
.point {{ fill: #ffffff; stroke-width: 0.012; }}
.path {{ fill: none; stroke-width: 0.022; stroke-linecap: round; stroke-linejoin: round; }}
.frame {{ fill: #ffffff; stroke: #d0d0d0; stroke-width: 0.006; }}
"""


def _svg_keys(layout_id: str) -> list[str]:
    column_units = KEYBOARD_COLUMN_COUNT if layout_id == "qwerty" else 11
    rows = LETTER_ROWS.get(layout_id, LETTER_ROWS["qwerty"])
    parts: list[str] = []
    for row_index, (letters, offset_units) in enumerate(rows):
        top = row_index / KEYBOARD_ROW_COUNT
        height = 1 / KEYBOARD_ROW_COUNT
        for index, letter in enumerate(letters):
            left = (index + offset_units) / column_units
            width = 1 / column_units
            parts.append(
                f'<rect class="key" x="{left:.4f}" y="{top:.4f}" '
                f'width="{width:.4f}" height="{height:.4f}"/>'
                f'<text class="key-label" x="{left + width / 2:.4f}" '
                f'y="{top + height / 2 + KEY_LABEL_BASELINE_OFFSET:.4f}">{_esc(letter)}</text>'
            )
    bottom_top = (KEYBOARD_ROW_COUNT - 1) / KEYBOARD_ROW_COUNT
    bottom_height = 1 / KEYBOARD_ROW_COUNT
    cursor = 0.0
    keys = BOTTOM_ROWS.get(layout_id, BOTTOM_ROWS["qwerty"])
    for index, (name, weight) in enumerate(keys):
        left = cursor / column_units
        is_last = index == len(keys) - 1
        right = 1.0 if is_last else (cursor + weight) / column_units
        cursor += weight
        parts.append(
            f'<rect class="key" x="{left:.4f}" y="{bottom_top:.4f}" width="{right - left:.4f}" '
            f'height="{bottom_height:.4f}"/>'
            f'<text class="key-label" x="{(left + right) / 2:.4f}" '
            f'y="{bottom_top + bottom_height / 2 + KEY_LABEL_BASELINE_OFFSET:.4f}">'
            f"{_esc(KEY_LABELS.get(name, name))}</text>"
        )
    return parts


def render_keyboard_svg(payload: dict, *, size: int = 320) -> str:
    """Draw one stored gesture trace over a virtual keyboard.

    Keys come from the same geometry the keyboard uses ([LETTER_ROWS], [BOTTOM_ROWS],
    [KEYBOARD_COLUMN_COUNT], [KEYBOARD_ROW_COUNT]); trace points are already normalized to that
    surface, so the path is drawn in the same [0, 1] space. One polyline per pointer id keeps a
    multi-pointer gesture readable, with a ring at the start and a filled dot at the end.
    """
    layout_id = payload.get("layout_id", "qwerty")
    points = payload.get("points", [])
    by_pointer: dict[int, list[dict]] = {}
    for point in points:
        by_pointer.setdefault(int(point.get("pointer_id", 0)), []).append(point)

    paths = []
    for order, (pointer_id, samples) in enumerate(sorted(by_pointer.items())):
        colour = POINTER_COLORS[order % len(POINTER_COLORS)]
        coordinates = " ".join(f"{float(s['x']):.4f},{float(s['y']):.4f}" for s in samples)
        paths.append(
            f'<polyline class="path" stroke="{colour}" points="{coordinates}"/>'
        )
        first, last = samples[0], samples[-1]
        paths.append(
            f'<circle class="point" stroke="{colour}" cx="{float(first["x"]):.4f}" '
            f'cy="{float(first["y"]):.4f}" r="0.018"/>'
        )
        paths.append(
            f'<circle class="point" fill="{colour}" stroke="{colour}" cx="{float(last["x"]):.4f}" '
            f'cy="{float(last["y"]):.4f}" r="0.026"/>'
        )

    title = (
        f"{payload.get('classification', 'trace')} · {layout_id} · {len(points)} points · "
        f"{len(by_pointer)} pointer(s)"
    )
    return (
        f'<svg viewBox="0 0 1 1" width="{size}" height="{size}" role="img" '
        f'aria-label="{_esc(title)}">'
        f"<style>{SVG_STYLE}</style>"
        f'<rect class="frame" x="0" y="0" width="1" height="1"/>'
        + "".join(_svg_keys(layout_id))
        + "".join(paths)
        + "</svg>"
    )


def _recent_events(database: TelemetryRepository, storage: ObjectStorage, plane: str, *, batches: int, wanted: set[str] | None = None):
    """Yield (record, event) newest batch first, optionally filtered to [wanted] event types."""
    records = sorted(database.list_batches(plane), key=lambda item: item.received_at_ms, reverse=True)
    for record in records[:batches]:
        try:
            batch = json.loads(storage.get(record.object_key))
        except Exception:
            continue
        for event in batch.get("events", []):
            if wanted is None or event.get("event_type") in wanted:
                yield record, event


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

    parts.append("<h2>Gestures and errors</h2>")
    parts.append(
        "<p>Gesture traces are drawn over the keyboard surface they were captured on; runtime "
        "errors and crashes show the exception class, its frames, and the breadcrumbs that led "
        "up to a crash.</p>"
    )
    research_rows = []
    for event_type, label in RESEARCH_AGGREGATE_FACTS:
        total = database.event_counts("research", event_type)
        if not total:
            continue
        counts = database.event_discriminator_counts("research", event_type)
        rendered = ", ".join(f"{_esc(key)}: {_esc(value)}" for key, value in sorted(counts.items()))
        research_rows.append([_esc(label), _esc(total), rendered or "-"])
    parts.append(
        _table(
            ["Research metric", "Events", "Breakdown"],
            research_rows,
            empty="No research events recorded yet.",
        )
    )
    parts.append(
        f'<p><a href="/gestures">All gesture traces</a> · '
        f'<a href="/errors">All errors and crashes</a> '
        f"({_esc(database.event_counts('diagnostics', 'runtime_error'))} runtime error(s), "
        f"{_esc(database.event_counts('diagnostics', 'crash'))} crash(es))</p>"
    )

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
        payload = event.get("payload", {})
        if event.get("event_type") == "gesture_trace":
            parts.append(
                f"<h3>#{_esc(index)} gesture_trace — {_esc(payload.get('classification', ''))} "
                f"({_esc(payload.get('layout_id', ''))}, {_esc(len(payload.get('points', [])))} points, "
                f"{_esc(payload.get('algorithm_version', ''))})</h3>"
            )
            parts.append(f'<figure class="trace">{render_keyboard_svg(payload, size=360)}</figure>')
        elif event.get("event_type") == "runtime_error":
            parts.append(f"<h3>#{_esc(index)} runtime_error</h3>")
            parts.append(_error_block(payload))
        elif event.get("event_type") == "crash":
            parts.append(f"<h3>#{_esc(index)} crash</h3>")
            parts.append(_error_block(payload, breadcrumbs=payload.get("breadcrumbs", [])))
        else:
            parts.append(f"<h3>#{_esc(index)} {_esc(event.get('event_type', '-'))}</h3>")
        parts.append(
            f"<pre>{_esc(json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False))}</pre>"
        )

    return _page(f"{plane} batch {batch_id}", "".join(parts))


def _breadcrumb_detail(breadcrumb: dict) -> str:
    detail = [
        f"{key}={breadcrumb[key]}"
        for key in ("gesture_kind", "outcome", "latency_bucket", "suggestion_action", "error_code")
        if breadcrumb.get(key)
    ]
    error = breadcrumb.get("error")
    if isinstance(error, dict) and error.get("type"):
        detail.append(f"error={error['type']}")
    return ", ".join(detail)


def _error_block(payload: dict, *, breadcrumbs: list[dict] | None = None) -> str:
    """Render a runtime error or crash: stable code, exception class, traceback, breadcrumbs."""
    error = payload.get("error", {})
    frames = error.get("frames", [])
    parts = ['<div class="error">']
    if payload.get("code"):
        parts.append(f'<p><strong>Code</strong> <code>{_esc(payload["code"])}</code></p>')
    parts.append(f'<p><strong>Exception</strong> <code>{_esc(error.get("type", "-"))}</code></p>')
    if frames:
        traceback = "\n".join(f"    at {frame}" for frame in frames)
        parts.append(f'<pre class="traceback">{_esc(traceback)}</pre>')
    else:
        parts.append('<p class="empty">No frames were recorded for this exception.</p>')
    if breadcrumbs:
        rows = [
            [_esc(item.get("kind", "-")), _esc(item.get("count", "-")), _esc(_breadcrumb_detail(item))]
            for item in breadcrumbs
        ]
        parts.append("<h4>Breadcrumbs (oldest first)</h4>")
        parts.append(_table(["Kind", "Count", "Detail"], rows, empty="No breadcrumbs recorded."))
    parts.append("</div>")
    return "".join(parts)


def render_gestures(database: TelemetryRepository, storage: ObjectStorage, *, limit: int = 24) -> str:
    """Render the newest stored gesture traces, each drawn over the virtual keyboard."""
    parts = [
        '<p><a href="/">← all batches</a></p>',
        "<h1>Gestures</h1>",
        '<p class="meta">Newest research traces first, drawn over the keyboard surface the trace '
        "was captured on. The ring marks where a pointer started and the filled dot where it "
        "ended.</p>",
        '<p class="note">Traces are normalized to the keyboard surface; they can be correlated '
        "with readable typing content in the same batch. Treat this page as sensitive.</p>",
    ]
    cards = []
    for record, event in _recent_events(
        database, storage, "research", batches=64, wanted={"gesture_trace"}
    ):
        if len(cards) >= limit:
            break
        payload = event.get("payload", {})
        points = payload.get("points", [])
        duration = max((point.get("time_offset_ms", 0) for point in points), default=0)
        pointers = len({point.get("pointer_id", 0) for point in points})
        cards.append(
            '<div class="card">'
            f'<figure class="trace">{render_keyboard_svg(payload)}</figure>'
            f'<p><strong>{_esc(payload.get("classification", "-"))}</strong> · '
            f'{_esc(payload.get("language", "-"))} · {_esc(payload.get("layout_id", "-"))} · '
            f'{_esc(len(points))} points · {_esc(pointers)} pointer(s) · {_esc(duration)} ms · '
            f'algorithm v{_esc(payload.get("algorithm_version", "-"))}</p>'
            f'<p class="meta">{_esc(_timestamp(event.get("occurred_at_ms")))} · '
            f'<a href="/batches/research/{_esc(record.installation_id)}/{_esc(record.batch_id)}">batch</a>'
            "</p></div>"
        )
    parts.append("".join(cards) if cards else '<p class="empty">No gesture traces stored yet.</p>')
    return _page("Iaido gestures", "".join(parts))


def render_errors(database: TelemetryRepository, storage: ObjectStorage, *, limit: int = 50) -> str:
    """Render the newest diagnostics runtime errors and crashes, with their tracebacks."""
    runtime_errors = database.event_counts("diagnostics", "runtime_error")
    crashes = database.event_counts("diagnostics", "crash")
    parts = [
        '<p><a href="/">← all batches</a></p>',
        "<h1>Errors and crashes</h1>",
        f'<p class="meta">{_esc(runtime_errors)} runtime error event(s), {_esc(crashes)} crash '
        "event(s) recorded. Newest first.</p>",
        '<p class="note">Diagnostics carry no exception message - only the class and file/line '
        "frames - so nothing typed by a user can appear here. Crashes are reported on the launch "
        "after the crash.</p>",
    ]
    entries = []
    for record, event in _recent_events(
        database, storage, "diagnostics", batches=64, wanted={"runtime_error", "crash"}
    ):
        if len(entries) >= limit:
            break
        payload = event.get("payload", {})
        entries.append(
            f'<h3>{_esc(event.get("event_type", "-"))} · '
            f'{_esc(_timestamp(event.get("occurred_at_ms")))} UTC</h3>'
            + _error_block(payload, breadcrumbs=payload.get("breadcrumbs", []))
            + f'<p class="meta">app {_esc(event.get("app_version", "-"))} '
            f'({_esc(event.get("build_type", "-"))}, API {_esc(event.get("android_api", "-"))}) · '
            f'<a href="/batches/diagnostics/{_esc(record.installation_id)}/{_esc(record.batch_id)}">batch</a></p>'
        )
    parts.append("".join(entries) if entries else '<p class="empty">No errors or crashes stored yet.</p>')
    return _page("Iaido errors", "".join(parts))


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
        "/gestures",
        response_class=HTMLResponse,
        dependencies=[Depends(require_operator)],
    )
    def gestures() -> HTMLResponse:
        return HTMLResponse(render_gestures(database, storage))

    @app.get(
        "/errors",
        response_class=HTMLResponse,
        dependencies=[Depends(require_operator)],
    )
    def errors() -> HTMLResponse:
        return HTMLResponse(render_errors(database, storage))

    @app.get(
        "/batches/{plane}/{installation_id}/{batch_id}",
        response_class=HTMLResponse,
        dependencies=[Depends(require_operator)],
    )
    def batch_detail(plane: str, installation_id: str, batch_id: str) -> HTMLResponse:
        return HTMLResponse(render_batch(database, storage, plane, installation_id, batch_id))
