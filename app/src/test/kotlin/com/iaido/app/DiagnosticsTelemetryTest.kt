package com.iaido.app

import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticsTelemetryTest {
    @Test
    fun `disabled diagnostics never append or schedule`() {
        withRuntime(enabled = false) { runtime, queue, scheduled ->
            runtime.recordGesture(DiagnosticsGestureKind.SWIPE, DiagnosticsOutcome.ACCEPTED)
            runtime.flush()

            assertEquals(0, queue.pendingBatches().size)
            assertFalse(scheduled())
            assertTrue(runtime.breadcrumbs().isEmpty())
        }
    }

    @Test
    fun `gesture helper serializes through the existing diagnostics event contract`() {
        withRuntime(enabled = true) { runtime, queue, scheduled ->
            runtime.recordGesture(DiagnosticsGestureKind.SPLIT, DiagnosticsOutcome.REJECTED)
            runtime.flush()

            val event = queue.pendingBatches().single().events.single()
            assertEquals("gesture_outcome", event.eventType)
            assertEquals(
                buildJsonObject {
                    put("event_type", JsonPrimitive("gesture_outcome"))
                    put("outcome", JsonPrimitive("REJECTED"))
                },
                event.payload,
            )
            assertTrue(scheduled())
        }
    }

    @Test
    fun `semantic breadcrumbs are bounded and contain only typed values`() {
        withRuntime(enabled = true, maxBreadcrumbs = 3) { runtime, _, _ ->
            runtime.recordAppStart()
            runtime.recordImeSessionStart()
            runtime.recordRecognitionLatency(80L)
            runtime.recordSuggestionAction(
                DiagnosticsSuggestionAction.REPLACEMENT,
                DiagnosticsOutcome.ACCEPTED,
            )

            assertEquals(
                listOf(
                    DiagnosticsBreadcrumbKind.IME_SESSION_START,
                    DiagnosticsBreadcrumbKind.RECOGNITION_LATENCY,
                    DiagnosticsBreadcrumbKind.SUGGESTION_ACTION,
                ),
                runtime.breadcrumbs().map(DiagnosticsBreadcrumb::kind),
            )
            assertEquals(
                RecognitionLatencyBucket.FROM_50_TO_99_MS,
                runtime.breadcrumbs()[1].latencyBucket,
            )
            assertEquals(1, runtime.breadcrumbs()[2].count)
        }
    }

    @Test
    fun `runtime errors are redacted before entering crash metadata`() {
        withRuntime(enabled = true) { runtime, _, _ ->
            runtime.recordRuntimeError(
                DiagnosticsRuntimeErrorCode.RECOGNITION_FAILED,
                IllegalStateException("secret editor text"),
            )

            val breadcrumb = runtime.breadcrumbs().single()
            assertEquals(DiagnosticsRuntimeErrorCode.RECOGNITION_FAILED, breadcrumb.errorCode)
            assertEquals("exception", breadcrumb.error?.type)
            assertNull(breadcrumb.error?.message)
            assertEquals("<redacted>", breadcrumb.error?.stackTrace)
            assertFalse(breadcrumb.toString().contains("secret editor text"))
        }
    }

    @Test
    fun `telemetry failures never escape into keyboard behavior`() {
        val runtime = DiagnosticsTelemetry(
            diagnosticsEnabled = { true },
            appendToQueue = { throw IllegalStateException("disk unavailable") },
            scheduleUpload = { throw IllegalStateException("scheduler unavailable") },
            envelopeFactory = { throw IllegalStateException("identity unavailable") },
        )

        assertDoesNotThrow {
            runtime.recordGesture(DiagnosticsGestureKind.SWIPE, DiagnosticsOutcome.ACCEPTED)
            runtime.flush()
        }
    }

    private fun withRuntime(
        enabled: Boolean,
        maxBreadcrumbs: Int = 32,
        block: (DiagnosticsTelemetry, TelemetryQueue, () -> Boolean) -> Unit,
    ) {
        val directory = Files.createTempDirectory("diagnostics-runtime").toFile()
        try {
            val queue = TelemetryQueue(
                directory = directory,
                plane = TelemetryPlane.DIAGNOSTICS,
                clock = { 1_000L },
                limits = QueueLimits(),
            )
            var scheduled = false
            val runtime = DiagnosticsTelemetry(
                diagnosticsEnabled = { enabled },
                appendToQueue = queue::append,
                scheduleUpload = { scheduled = true },
                envelopeFactory = ::diagnosticEnvelope,
                maxBreadcrumbs = maxBreadcrumbs,
            )

            block(runtime, queue) { scheduled }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun diagnosticEnvelope(event: DiagnosticsEvent): TelemetryEnvelope {
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(
            DiagnosticsEventCodec.encode(event),
        ).let { it as kotlinx.serialization.json.JsonObject }
        return TelemetryEnvelope(
            schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = "00000000-0000-0000-0000-000000000001",
            batchId = "00000000-0000-0000-0000-000000000002",
            installationId = "00000000-0000-0000-0000-000000000003",
            sessionId = "00000000-0000-0000-0000-000000000004",
            occurredAtMs = 1_000L,
            appVersion = "0.1.3",
            buildType = "debug",
            androidApi = 36,
            eventType = "gesture_outcome",
            payload = payload,
        )
    }
}
