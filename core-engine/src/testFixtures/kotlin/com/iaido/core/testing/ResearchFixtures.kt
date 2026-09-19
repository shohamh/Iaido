package com.iaido.core.testing

import com.iaido.core.language.Language
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Fixture kinds a reviewed research record can be exported as. */
enum class ResearchFixtureKind(val wireName: String) {
    GESTURE_TRACE("gesture_trace"),
    CORRECTION("correction"),
}

/** One normalized pointer sample of a replayed gesture. */
data class ResearchFixturePoint(
    val pointerId: Int,
    val action: Int,
    val timeOffsetMs: Long,
    val x: Float,
    val y: Float,
)

/** A replayed gesture trace: shape plus the coarse context needed to interpret it. */
data class ResearchFixtureTrace(
    val classification: String,
    val language: Language,
    val layoutId: String,
    val algorithmVersion: Int,
    val points: List<ResearchFixturePoint>,
)

/** A replayed correction example: only the affected span and the decision that produced it. */
data class ResearchFixtureCorrection(
    val action: String,
    val sourceText: String,
    val finalText: String,
    val candidates: List<String>,
    val algorithmVersion: Int,
)

/**
 * One reviewed fixture. Exactly one of [trace]/[correction] is set, matching [kind] - the codec
 * rejects a fixture that carries the other kind's fields.
 */
data class ResearchFixture(
    val fixtureId: String,
    val kind: ResearchFixtureKind,
    val trace: ResearchFixtureTrace?,
    val correction: ResearchFixtureCorrection?,
)

/** One deterministic, de-identified fixture bundle as written by the telemetry server's export. */
data class ResearchFixtureBundle(
    val schemaVersion: Int,
    val fixtures: List<ResearchFixture>,
)

/**
 * Codec for the reviewed research-fixture bundle (`docs/research/schema-v1.md`), the format the
 * telemetry server's `export_research_fixtures` writes and pure core-engine/IME regression tests
 * replay.
 *
 * Decoding is strict: unknown fields, missing fields, unsupported versions or kinds, duplicate
 * fixture ids, non-finite or out-of-range coordinates, over-long spans, and over-count candidate
 * lists are all rejected with an [IllegalArgumentException] naming the offending field. Nothing is
 * silently dropped, because a fixture that quietly loses a field would let a regression test pass
 * against data that no longer matches what the device recorded.
 */
object ResearchFixtureCodec {
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_POINTS = 512
    const val MAX_SPAN_CODE_POINTS = 64
    const val MAX_CANDIDATES = 5

    /** Gesture families the device tags a trace with (see `ResearchTraceClassification`). */
    val classifications = setOf(
        "swipe",
        "tap",
        "flick",
        "split",
        "command",
        "punctuation",
        "backspace",
        "failed",
        "cancelled",
    )

    /** Correction actions the device records (see `CorrectionAction`'s wire names). */
    val correctionActions = setOf("candidate_selected", "manual_edit", "undo", "flow_correction")

    private val json = Json

    fun decode(jsonText: String): ResearchFixtureBundle {
        val root = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Research fixture bundle must be a JSON object", error)
        }

        requireKeys(root, setOf("schema_version", "fixtures"))
        val schemaVersion = root.getValue("schema_version").jsonPrimitive.int
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported research fixture schema version: $schemaVersion"
        }
        val fixtures = root.getValue("fixtures").jsonArray.map(::decodeFixture)
        require(fixtures.isNotEmpty()) { "Research fixture bundles must contain at least one fixture" }
        require(fixtures.map { it.fixtureId }.distinct().size == fixtures.size) {
            "Research fixture ids must be unique"
        }
        return ResearchFixtureBundle(schemaVersion = schemaVersion, fixtures = fixtures)
    }

    private fun decodeFixture(element: JsonElement): ResearchFixture {
        val fixture = element.jsonObject
        val kindName = fixture["kind"]?.jsonPrimitive?.contentOrNull
        val kind = ResearchFixtureKind.entries.firstOrNull { it.wireName == kindName }
            ?: throw IllegalArgumentException("Unsupported research fixture kind: $kindName")

        val traceFields = setOf("classification", "language", "layout_id", "algorithm_version", "points")
        val correctionFields =
            setOf("action", "source_text", "final_text", "candidates", "algorithm_version")
        val allowed = setOf("fixture_id", "kind") + when (kind) {
            ResearchFixtureKind.GESTURE_TRACE -> traceFields
            ResearchFixtureKind.CORRECTION -> correctionFields
        }
        requireKeys(fixture, allowed)

        val fixtureId = fixture.getValue("fixture_id").jsonPrimitive.content
        require(fixtureId.isNotBlank()) { "Research fixture ids must not be blank" }

        return ResearchFixture(
            fixtureId = fixtureId,
            kind = kind,
            trace = if (kind == ResearchFixtureKind.GESTURE_TRACE) decodeTrace(fixture) else null,
            correction = if (kind == ResearchFixtureKind.CORRECTION) decodeCorrection(fixture) else null,
        )
    }

    private fun decodeTrace(fixture: JsonObject): ResearchFixtureTrace {
        val classification = fixture.getValue("classification").jsonPrimitive.content
        require(classification in classifications) {
            "Unsupported research fixture classification: $classification"
        }
        val languageName = fixture.getValue("language").jsonPrimitive.content
        val language = Language.entries.firstOrNull { it.name == languageName }
            ?: throw IllegalArgumentException("Unsupported research fixture language: $languageName")
        val layoutId = fixture.getValue("layout_id").jsonPrimitive.content
        require(layoutId.isNotBlank()) { "Research fixture layout ids must not be blank" }
        val algorithmVersion = fixture.getValue("algorithm_version").jsonPrimitive.int
        require(algorithmVersion >= 1) { "Research fixture algorithm versions start at 1" }

        val points = fixture.getValue("points").jsonArray.map(::decodePoint)
        require(points.isNotEmpty() && points.size <= MAX_POINTS) {
            "Research fixture traces must contain 1-$MAX_POINTS points"
        }
        require(points.zipWithNext().all { (earlier, later) -> later.timeOffsetMs >= earlier.timeOffsetMs }) {
            "Research fixture trace times must be monotonic"
        }
        return ResearchFixtureTrace(
            classification = classification,
            language = language,
            layoutId = layoutId,
            algorithmVersion = algorithmVersion,
            points = points,
        )
    }

    private fun decodePoint(element: JsonElement): ResearchFixturePoint {
        val point = element.jsonObject
        requireKeys(point, setOf("pointer_id", "action", "time_offset_ms", "x", "y"))
        val pointerId = point.getValue("pointer_id").jsonPrimitive.int
        require(pointerId >= 0) { "Research fixture pointer ids must not be negative" }
        val action = point.getValue("action").jsonPrimitive.int
        require(action >= 0) { "Research fixture pointer actions must not be negative" }
        val timeOffsetMs = point.getValue("time_offset_ms").jsonPrimitive.long
        require(timeOffsetMs >= 0) { "Research fixture pointer times must be relative to the trace start" }
        val x = point.getValue("x").jsonPrimitive.float
        val y = point.getValue("y").jsonPrimitive.float
        require(x.isFinite() && y.isFinite()) { "Research fixture coordinates must be finite" }
        require(x in 0f..1f && y in 0f..1f) {
            "Research fixture coordinates must be normalized to [0, 1]"
        }
        return ResearchFixturePoint(
            pointerId = pointerId,
            action = action,
            timeOffsetMs = timeOffsetMs,
            x = x,
            y = y,
        )
    }

    private fun decodeCorrection(fixture: JsonObject): ResearchFixtureCorrection {
        val action = fixture.getValue("action").jsonPrimitive.content
        require(action in correctionActions) {
            "Unsupported research fixture correction action: $action"
        }
        val algorithmVersion = fixture.getValue("algorithm_version").jsonPrimitive.int
        require(algorithmVersion >= 1) { "Research fixture algorithm versions start at 1" }

        val sourceText = boundedSpan(fixture.getValue("source_text").jsonPrimitive.content, "source_text")
        val finalText = boundedSpan(fixture.getValue("final_text").jsonPrimitive.content, "final_text")
        val candidates = fixture.getValue("candidates").jsonArray.map {
            boundedSpan(it.jsonPrimitive.content, "candidates")
        }
        require(candidates.size <= MAX_CANDIDATES) {
            "Research fixture corrections carry at most $MAX_CANDIDATES candidates"
        }
        return ResearchFixtureCorrection(
            action = action,
            sourceText = sourceText,
            finalText = finalText,
            candidates = candidates,
            algorithmVersion = algorithmVersion,
        )
    }

    private fun boundedSpan(text: String, field: String): String {
        require(text.isNotEmpty()) { "Research fixture $field must not be empty" }
        require(text.codePointCount(0, text.length) <= MAX_SPAN_CODE_POINTS) {
            "Research fixture $field must be at most $MAX_SPAN_CODE_POINTS code points"
        }
        return text
    }

    private fun requireKeys(
        payload: JsonObject,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val unknown = payload.keys - required - optional
        require(unknown.isEmpty()) { "Unsupported research fixture fields: $unknown" }
        val missing = required - payload.keys
        require(missing.isEmpty()) { "Missing research fixture fields: $missing" }
    }
}
