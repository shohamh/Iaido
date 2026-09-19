# Research fixtures

How reviewed research records become replayable test fixtures. The format is documented in
[`schema-v1.md`](schema-v1.md).

Research data is only ever captured after a user opts in to the separate research consent, and it
can contain readable typing content from the affected span plus normalized touch traces. Fixtures
are therefore produced by an **operator-reviewed** export: nothing reaches a fixture file until a
human has inspected the record and approved its content hash.

## 1. Inspect candidate records

List the research batches in the time range you want to review, then export that plane as NDJSON
(both routes take the operator bearer token from the server environment):

```powershell
curl "$IAIDO_TELEMETRY_BASE_URL/v1/operator/research/batches" -H "Authorization: Bearer $IAIDO_OPERATOR_TOKEN"
curl "$IAIDO_TELEMETRY_BASE_URL/v1/operator/research/export?start_ms=<start>&end_ms=<end>" `
  -H "Authorization: Bearer $IAIDO_OPERATOR_TOKEN" -o review.ndjson
```

Read the output and reject anything sensitive: names, addresses, credentials, medical or financial
content, or anything you cannot justify as a regression example. Research records are bounded to
the affected word/span, but a reviewed sample is still the last line of defence.

## 2. Approve a manifest

Write the approved content hashes (the `sha256` of each event's canonical JSON, computed by
`iaido_telemetry.fixture_export.event_hash`) to a manifest:

```json
{ "approved": { "<sha256>": "swipe regression for qwerty reel", "<sha256>": "flow-correction example" } }
```

A list (`{"approved": ["<sha256>"]}`) is also accepted. An empty or missing manifest fails the
export: unreviewed research data must never reach a fixture file.

## 3. Export the fixture bundle

There is no CLI yet; call the export from operator tooling (Python REPL, script, or job) where the
server's database, object storage, and operator token are configured:

```python
from iaido_telemetry.fixture_export import export_research_fixtures

path = export_research_fixtures(
    database, storage, settings,
    start_ms=..., end_ms=...,
    review_manifest="review-manifest.json",
    output_dir="fixtures-out",
    operator_token=os.environ["IAIDO_OPERATOR_TOKEN"],
)
```

It writes `research-fixtures-v1.json`, strips installation/session/batch ids and receive
timestamps, assigns deterministic fixture ids in content-hash order, and records an `export` audit
row. Exporting the same reviewed rows twice produces byte-identical output.

## 4. Add fixtures to tests

Copy the bundle into a test resource location (`core-engine/src/testFixtures/resources/` for
core-engine tests) and decode it with `ResearchFixtureCodec`:

```kotlin
val bundle = ResearchFixtureCodec.decode(
    javaClass.getResourceAsStream("/research-fixtures-v1.json")!!.bufferedReader().use { it.readText() },
)
val trace = bundle.fixtures.first { it.kind == ResearchFixtureKind.GESTURE_TRACE }.trace!!
```

The codec rejects unsupported versions, unknown or missing fields, out-of-range coordinates,
over-long spans, over-count candidate lists, and duplicate fixture ids, so a fixture that drifts
from the documented schema fails loudly instead of silently weakening a test.

A sample bundle ships as `research-fixtures-v1.sample.json` and is asserted by
`app/src/test/kotlin/com/iaido/app/ResearchFixtureCodecTest.kt`.

## 5. Delete the source records when required

Fixtures are de-identified copies; the reviewed source records remain in the research plane until
their retention window expires. When the review policy requires it, delete them once the fixture is
extracted:

```powershell
curl -X DELETE "$IAIDO_TELEMETRY_BASE_URL/v1/operator/installations/<installation-id>/research" `
  -H "Authorization: Bearer $IAIDO_OPERATOR_TOKEN"
```

Deletion removes both metadata and stored batch objects for that installation and plane, and writes
a `delete` audit row. Diagnostics data for the same installation is untouched.
