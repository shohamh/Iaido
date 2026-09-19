package com.iaido.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TelemetryEventTest {
    @Test
    fun `diagnostics event cannot carry research text or trace fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode("""{"event_type":"gesture_outcome","text":"secret","points":[[1,2]]}""")
        }
    }

    @Test
    fun `diagnostics event codec round trips a bounded outcome`() {
        val event = DiagnosticsEvent.GestureOutcome(DiagnosticsOutcome.ACCEPTED)

        assertEquals(event, DiagnosticsEventCodec.decode(DiagnosticsEventCodec.encode(event)))
    }

    @Test
    fun `diagnostics event codec rejects outcomes outside the allowlist`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode("""{"event_type":"gesture_outcome","outcome":"secret"}""")
        }
    }

    @Test
    fun `telemetry envelope rejects unsupported schema versions`() {
        assertThrows(IllegalArgumentException::class.java) {
            TelemetryEnvelope(
                schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION + 1,
                eventId = "event",
                batchId = "batch",
                installationId = "installation",
                sessionId = "session",
                occurredAtMs = 1L,
                appVersion = "0.1.4",
                buildType = "debug",
                androidApi = 36,
                eventType = "gesture_outcome",
                payload = kotlinx.serialization.json.buildJsonObject {},
            )
        }
    }

    @Test
    fun `research text is bounded at construction`() {
        BoundedResearchText("a".repeat(BoundedResearchText.MAX_LENGTH))

        assertThrows(IllegalArgumentException::class.java) {
            BoundedResearchText("a".repeat(BoundedResearchText.MAX_LENGTH + 1))
        }
    }

    @Test
    fun `research trace samples use normalized finite keyboard pointers`() {
        val sample = ResearchTraceSample(pointerId = 4, action = 0, timeOffsetMs = 0L, x = 0.25f, y = 0.75f)

        assertEquals(0.25f, sample.x)
        assertEquals(0.75f, sample.y)
        assertThrows(IllegalArgumentException::class.java) {
            ResearchTraceSample(pointerId = 4, action = 0, timeOffsetMs = 0L, x = -0.01f, y = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchTraceSample(pointerId = 4, action = 0, timeOffsetMs = 0L, x = 0.5f, y = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchTraceSample(pointerId = -1, action = 0, timeOffsetMs = 0L, x = 0.5f, y = 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchTraceSample(pointerId = 0, action = 0, timeOffsetMs = -1L, x = 0.5f, y = 0.5f)
        }
    }

    @Test
    fun `bounded traces reject unknown classifications, empty points, and unordered times`() {
        assertThrows(IllegalArgumentException::class.java) { boundedTraceOf(classification = "sentence") }
        assertThrows(IllegalArgumentException::class.java) { boundedTraceOf(points = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            boundedTraceOf(
                points = listOf(
                    ResearchTraceSample(0, 2, 9L, 0.5f, 0.5f),
                    ResearchTraceSample(0, 1, 1L, 0.5f, 0.5f),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            boundedTraceOf(
                points = List(BoundedResearchTrace.MAX_POINTS + 1) {
                    ResearchTraceSample(0, 0, it.toLong(), 0.5f, 0.5f)
                },
            )
        }
    }

    @Test
    fun `bounded corrections reject empty, oversized, and over-count spans`() {
        assertThrows(IllegalArgumentException::class.java) {
            correctionOf(sourceText = "", finalText = "the")
        }
        assertThrows(IllegalArgumentException::class.java) {
            correctionOf(sourceText = "teh", finalText = "a".repeat(MAX_SPAN_CODE_POINTS + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            correctionOf(sourceText = "teh", finalText = "the", candidates = List(6) { "the" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            correctionOf(sourceText = "teh", finalText = "the", candidates = listOf("a".repeat(65)))
        }
    }

    @Test
    fun `research codec round trips every event shape`() {
        val events = listOf(
            ResearchEvent.TextSample(BoundedResearchText("bounded sample")),
            ResearchEvent.GestureTrace(boundedTraceOf()),
            ResearchEvent.Correction(correctionOf(sourceText = "teh", finalText = "the")),
        )

        events.forEach { event ->
            val eventType = ResearchEventCodec.eventType(event)
            assertEquals(event, ResearchEventCodec.decode(eventType, ResearchEventCodec.payload(event)))
        }
    }

    @Test
    fun `research codec rejects unknown, missing, and out-of-bounds payload fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResearchEventCodec.decode("raw_touch_path", buildJsonObject {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchEventCodec.decode(
                ResearchEventCodec.GESTURE_TRACE,
                buildJsonObject {
                    put("trace_id", "trace")
                    put("sentence_context", "the secret document")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ResearchEventCodec.decode(
                ResearchEventCodec.TEXT_SAMPLE,
                buildJsonObject {
                    put("text", "sample")
                    put("points", JsonArray(emptyList()))
                },
            )
        }
    }

    @Test
    fun `research correction payload omits trace id entirely when absent`() {
        val withoutTrace = ResearchEventCodec.payload(
            ResearchEvent.Correction(correctionOf(sourceText = "teh", finalText = "the")),
        )
        val withTrace = ResearchEventCodec.payload(
            ResearchEvent.Correction(correctionOf(sourceText = "teh", finalText = "the", traceId = "trace-1")),
        )

        assertFalse(withoutTrace.containsKey("trace_id"))
        assertEquals("trace-1", withTrace.getValue("trace_id").toString().trim('"'))
    }

    @Test
    fun `diagnostics codec round trips gesture, runtime error, and crash events`() {
        val error = RedactedThrowable(
            "IllegalStateException",
            listOf("RecognitionController.recognize(RecognitionController.kt:87)", "<redacted>"),
        )
        val events = listOf(
            DiagnosticsEvent.GestureOutcome(DiagnosticsOutcome.REJECTED),
            DiagnosticsEvent.RuntimeError(DiagnosticsRuntimeErrorCode.RECOGNITION_FAILED, error),
            DiagnosticsEvent.Crash(
                error = error,
                breadcrumbs = listOf(
                    DiagnosticsBreadcrumb(DiagnosticsBreadcrumbKind.APP_START),
                    DiagnosticsBreadcrumb(
                        kind = DiagnosticsBreadcrumbKind.GESTURE_OUTCOME,
                        count = 3,
                        gestureKind = DiagnosticsGestureKind.SWIPE,
                        outcome = DiagnosticsOutcome.ACCEPTED,
                    ),
                    DiagnosticsBreadcrumb(
                        kind = DiagnosticsBreadcrumbKind.RUNTIME_ERROR,
                        errorCode = DiagnosticsRuntimeErrorCode.UNKNOWN,
                        error = error,
                    ),
                ),
            ),
        )

        events.forEach { event ->
            val encoded = DiagnosticsEventCodec.encode(event)
            assertEquals(event, DiagnosticsEventCodec.decode(encoded))
            assertEquals(
                DiagnosticsEventCodec.eventType(event),
                Json.parseToJsonElement(encoded).jsonObject.getValue("event_type").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `diagnostics codec rejects malformed or unbounded error payloads`() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode(
                """{"event_type":"runtime_error","code":"NOT_A_CODE","error":{"type":"X","frames":[]}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode(
                """{"event_type":"runtime_error","error":{"type":"X","frames":[]}}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode(
                """{"event_type":"crash","error":{"type":"X","frames":["C:\\Users\\me\\A.kt:1"]},"breadcrumbs":[]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode(
                """{"event_type":"crash","error":{"type":"X","frames":[],"message":"typed secret"},"breadcrumbs":[]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode(
                """{"event_type":"crash","error":{"type":"X","frames":[]},"breadcrumbs":[{"kind":"APP_START","count":0}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEventCodec.decode(
                """{"event_type":"crash","error":{"type":"X","frames":[]},"breadcrumbs":[{"kind":"RUNTIME_ERROR","count":1,"error_code":"UNKNOWN","sentence":"typed text"}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticsEvent.Crash(
                error = RedactedThrowable("X", listOf("<redacted>")),
                breadcrumbs = List(MAX_BREADCRUMBS + 1) { DiagnosticsBreadcrumb(DiagnosticsBreadcrumbKind.APP_START) },
            )
        }
    }

    private fun boundedTraceOf(
        classification: String = ResearchTraceClassification.SWIPE,
        points: List<ResearchTraceSample> = listOf(ResearchTraceSample(4, 0, 0L, 0.25f, 0.25f)),
    ) = BoundedResearchTrace(
        traceId = "10000000-0000-4000-8000-0000000000aa",
        classification = classification,
        language = com.iaido.core.language.Language.ENGLISH,
        layoutId = "qwerty",
        algorithmVersion = 1,
        points = points,
    )

    private fun correctionOf(
        sourceText: String,
        finalText: String,
        candidates: List<String> = emptyList(),
        traceId: String? = null,
    ) = BoundedResearchCorrection(
        correctionId = "10000000-0000-4000-8000-0000000000bb",
        traceId = traceId,
        action = CorrectionAction.MANUAL_EDIT,
        sourceText = sourceText,
        finalText = finalText,
        candidates = candidates,
        algorithmVersion = 1,
    )
}
