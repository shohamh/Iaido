package com.iaido.app

import androidx.work.NetworkType
import com.iaido.core.language.Language
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plane-isolation contract tests: research correction capture (Task 9) must never let readable
 * text or research-only fields cross into the diagnostics queue, and diagnostics activity must
 * never touch the research queue either. Also covers the research-only Wi-Fi/unmetered upload
 * policy added in this task.
 */
class TelemetryPlaneIsolationTest {
    @Test
    fun `diagnostics queue never receives research correction text`() {
        withRuntime { runtime ->
            runtime.research.record(sampleCorrection())

            assertTrue(runtime.diagnosticsQueue.pendingBatches().isEmpty())
        }
    }

    @Test
    fun `research queue never receives diagnostics-only breadcrumb events`() {
        withRuntime { runtime ->
            runtime.diagnostics.recordGesture(DiagnosticsGestureKind.SWIPE, DiagnosticsOutcome.ACCEPTED)
            runtime.diagnostics.flush()

            assertTrue(runtime.researchQueue.pendingBatches().isEmpty())
        }
    }

    @Test
    fun `research correction text only ever lands in the research plane queue`() {
        withRuntime { runtime ->
            runtime.research.record(sampleCorrection())

            val researchEvents = runtime.researchQueue.pendingBatches().single().events
            assertEquals("research_correction", researchEvents.single().eventType)
            assertTrue(researchEvents.single().payload.toString().contains("teh"))
            assertTrue(runtime.diagnosticsQueue.pendingBatches().isEmpty())
        }
    }

    @Test
    fun `disabling research deletes only the research queue, never diagnostics`() {
        withRuntime { runtime ->
            runtime.diagnostics.recordGesture(DiagnosticsGestureKind.SWIPE, DiagnosticsOutcome.ACCEPTED)
            runtime.diagnostics.flush()
            runtime.research.record(sampleCorrection())

            assertFalse(runtime.diagnosticsQueue.pendingBatches().isEmpty())
            assertFalse(runtime.researchQueue.pendingBatches().isEmpty())

            runtime.researchQueue.deleteAll()

            assertTrue(runtime.researchQueue.pendingBatches().isEmpty())
            assertFalse(runtime.diagnosticsQueue.pendingBatches().isEmpty())
        }
    }

    @Test
    fun `research gesture traces only ever land in the research plane queue`() {
        withRuntime { runtime ->
            ResearchCorrectionRecorderProvider.installForTest(recorder = null, consentEnabled = true)
            try {
                ResearchCorrectionRecorderProvider.installSinkForTest { event ->
                    runtime.researchQueue.append(researchEnvelope(event))
                }

                ResearchCorrectionRecorderProvider.recordTrace(sampleTrace())

                val event = runtime.researchQueue.pendingBatches().single().events.single()
                assertEquals("gesture_trace", event.eventType)
                val trace = sampleTrace()
                assertEquals(trace.traceId, event.payload.getValue("trace_id").jsonPrimitive.content)
                assertEquals(1, event.payload.getValue("points").jsonArray.size)
                assertTrue(runtime.diagnosticsQueue.pendingBatches().isEmpty())
            } finally {
                ResearchCorrectionRecorderProvider.resetForTest()
            }
        }
    }

    @Test
    fun `a captured trace is dropped entirely while research consent is off`() {
        withRuntime { runtime ->
            ResearchCorrectionRecorderProvider.installForTest(recorder = null, consentEnabled = false)
            try {
                ResearchCorrectionRecorderProvider.installSinkForTest { event ->
                    runtime.researchQueue.append(researchEnvelope(event))
                }

                ResearchCorrectionRecorderProvider.recordTrace(sampleTrace())

                assertTrue(runtime.researchQueue.pendingBatches().isEmpty())
                assertTrue(runtime.diagnosticsQueue.pendingBatches().isEmpty())
            } finally {
                ResearchCorrectionRecorderProvider.resetForTest()
            }
        }
    }

    @Test
    fun `a correction of text committed by a captured gesture carries that trace id`() {
        val trace = sampleTrace()
        val bounded = boundedCorrectionRecord(
            CorrectionInput(
                action = CorrectionAction.CANDIDATE_SELECTED,
                sourceText = "teh",
                finalText = "the",
                algorithmVersion = 1,
                traceId = trace.traceId,
            ),
        )!!

        assertEquals(trace.traceId, bounded.traceId)
        assertEquals(
            trace.traceId,
            ResearchEventCodec.payload(ResearchEvent.Correction(bounded))
                .getValue("trace_id")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `research uploads default to unmetered wifi-only while diagnostics stays on any network`() {
        assertEquals(
            NetworkType.UNMETERED,
            networkTypeFor(TelemetryPlane.RESEARCH, researchWifiOnly = true),
        )
        assertEquals(
            NetworkType.CONNECTED,
            networkTypeFor(TelemetryPlane.RESEARCH, researchWifiOnly = false),
        )
        assertEquals(
            NetworkType.CONNECTED,
            networkTypeFor(TelemetryPlane.DIAGNOSTICS, researchWifiOnly = true),
        )
    }

    private fun withRuntime(block: (Runtime) -> Unit) {
        val directory = Files.createTempDirectory("plane-isolation-runtime").toFile()
        try {
            val diagnosticsQueue = TelemetryQueue(
                directory = directory,
                plane = TelemetryPlane.DIAGNOSTICS,
                clock = { 1_000L },
                limits = QueueLimits(),
            )
            val researchQueue = TelemetryQueue(
                directory = directory,
                plane = TelemetryPlane.RESEARCH,
                clock = { 1_000L },
                limits = QueueLimits(),
            )
            val diagnostics = DiagnosticsTelemetry(
                diagnosticsEnabled = { true },
                appendToQueue = diagnosticsQueue::append,
                scheduleUpload = {},
                envelopeFactory = ::diagnosticEnvelope,
            )
            val research = ResearchCorrectionRecorder(
                enabled = { true },
                appendToQueue = { record -> researchQueue.append(correctionEnvelope(record)) },
                scheduleUpload = {},
            )

            block(Runtime(diagnostics, research, diagnosticsQueue, researchQueue))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sampleCorrection(): CorrectionInput = CorrectionInput(
        action = CorrectionAction.CANDIDATE_SELECTED,
        sourceText = "teh",
        finalText = "the",
        candidates = listOf("the", "them"),
        algorithmVersion = 1,
    )

    private fun sampleTrace() = ResearchTrace(
        traceId = "10000000-0000-4000-8000-0000000000aa",
        classification = ResearchTraceClassification.SWIPE,
        language = Language.ENGLISH,
        layoutId = "qwerty",
        algorithmVersion = 1,
        points = listOf(ResearchTraceSample(4, 0, 0L, 0.25f, 0.25f)),
    )

    private fun researchEnvelope(event: ResearchEvent): TelemetryEnvelope = TelemetryEnvelope(
        schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
        eventId = "00000000-0000-0000-0000-000000000009",
        batchId = "00000000-0000-0000-0000-000000000002",
        installationId = "00000000-0000-0000-0000-000000000003",
        sessionId = "00000000-0000-0000-0000-000000000004",
        occurredAtMs = 1_000L,
        appVersion = "0.1.8",
        buildType = "debug",
        androidApi = 36,
        eventType = ResearchEventCodec.eventType(event),
        payload = ResearchEventCodec.payload(event),
    )

    private fun correctionEnvelope(record: BoundedResearchCorrection): TelemetryEnvelope =
        researchEnvelope(ResearchEvent.Correction(record))

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
            appVersion = "0.1.8",
            buildType = "debug",
            androidApi = 36,
            eventType = "gesture_outcome",
            payload = payload,
        )
    }

    private data class Runtime(
        val diagnostics: DiagnosticsTelemetry,
        val research: ResearchCorrectionRecorder,
        val diagnosticsQueue: TelemetryQueue,
        val researchQueue: TelemetryQueue,
    )
}
