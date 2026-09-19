# Reviewed research fixture schema v1

A fixture bundle is one deterministic, de-identified JSON file produced by
`telemetry-server/src/iaido_telemetry/fixture_export.py` and consumed by
`core-engine/src/testFixtures/kotlin/com/iaido/core/testing/ResearchFixtures.kt`
(`ResearchFixtureCodec`). Fixtures replay reviewed research records — normalized gesture traces and
bounded correction examples — in core-engine and connected-IME regression tests.

The bundle contains only what a replay needs. Installation ids, session ids, batch ids,
credentials, operator metadata, and receive timestamps are stripped during export, and fixture ids
are assigned from the approved content itself (`fixture-0001`, `fixture-0002`, …) in content-hash
order, so exporting the same reviewed rows twice produces byte-identical files.

## Bundle

```json
{
  "schema_version": 1,
  "fixtures": [
    {
      "fixture_id": "fixture-0001",
      "kind": "gesture_trace",
      "classification": "swipe",
      "language": "ENGLISH",
      "layout_id": "qwerty",
      "algorithm_version": 1,
      "points": [
        {"pointer_id": 4, "action": 0, "time_offset_ms": 0, "x": 0.25, "y": 0.25},
        {"pointer_id": 4, "action": 2, "time_offset_ms": 42, "x": 0.31, "y": 0.25}
      ]
    },
    {
      "fixture_id": "fixture-0002",
      "kind": "correction",
      "action": "manual_edit",
      "source_text": "teh",
      "final_text": "the",
      "candidates": ["the", "ten"],
      "algorithm_version": 1
    }
  ]
}
```

`schema_version` is required and currently must be `1`. `fixtures` is required, non-empty, and
every `fixture_id` must be unique and non-blank.

## `kind: "gesture_trace"`

| Field | Type | Bound |
|---|---|---|
| `classification` | enum | `swipe`, `tap`, `flick`, `split`, `command`, `punctuation`, `backspace`, `failed`, `cancelled` |
| `language` | enum | `ENGLISH`, `HEBREW` |
| `layout_id` | string | non-blank, ≤ 32 characters |
| `algorithm_version` | integer | ≥ 1 |
| `points` | array | 1–512 samples, `time_offset_ms` non-decreasing |
| `points[].pointer_id` | integer | ≥ 0 |
| `points[].action` | integer | ≥ 0 (Android `MotionEvent` action family) |
| `points[].time_offset_ms` | integer | ≥ 0, relative to the trace's first sample |
| `points[].x`, `points[].y` | number | finite, `[0, 1]`, normalized to the keyboard surface |

`classification` distinguishes a real swipe-training example from a command, backspace, cancelled,
or failed gesture; a fixture consumer must not train on a non-typing classification.

## `kind: "correction"`

| Field | Type | Bound |
|---|---|---|
| `action` | enum | `candidate_selected`, `manual_edit`, `undo`, `flow_correction` |
| `source_text` | string | 1–64 Unicode code points |
| `final_text` | string | 1–64 Unicode code points |
| `candidates` | array of strings | ≤ 5 entries, each 1–64 code points |
| `algorithm_version` | integer | ≥ 1 |

Only the affected span is present. Surrounding sentence or document context is never captured,
exported, or representable in this schema.

## Rejection rules

Both the export and the Kotlin codec reject rather than repair:

- unsupported `schema_version`;
- missing, empty, or duplicate `fixture_id`s;
- unknown `kind`, unknown `classification`, unknown `action`, unknown `language`;
- any field not listed above for that kind, at any nesting level (unknown keys are errors, not
  ignored);
- missing required fields;
- non-finite or out-of-`[0, 1]` coordinates, negative pointer ids/actions/times, non-monotonic
  trace times, empty or over-count point lists;
- empty or over-long spans, over-count candidate lists;
- an approved source record that no longer satisfies the current bounds (the export fails so a
  stale fixture cannot silently enter a test suite).

The server validates reviewed records through the same Pydantic models the ingestion route uses
(`iaido_telemetry.schemas`), so a fixture can never carry a shape the device could not have sent.
