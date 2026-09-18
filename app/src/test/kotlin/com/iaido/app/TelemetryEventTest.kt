package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `research trace uses normalized finite keyboard pointers`() {
        val trace = ResearchEvent.GestureTrace(
            NormalizedGestureTrace(listOf(NormalizedKeyboardPointer(0.25f, 0.75f))),
        )

        assertEquals(0.25f, trace.trace.points.single().x)
        assertEquals(0.75f, trace.trace.points.single().y)
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedKeyboardPointer(-0.01f, 0.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NormalizedKeyboardPointer(0.5f, Float.NaN)
        }
    }
}
