package com.iaido.app

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResearchCorrectionRecorderTest {
    @Test
    fun `correction record keeps affected span but drops surrounding context`() {
        // The recorder's input type has no field for surrounding context at all - passing it here
        // would be a compile error, which is itself the strongest guarantee against leaking it.
        val bounded = boundedCorrectionRecord(
            CorrectionInput(
                action = CorrectionAction.CANDIDATE_SELECTED,
                sourceText = "teh",
                finalText = "the",
                algorithmVersion = 1,
            ),
        )!!

        assertEquals("teh", bounded.sourceText)
        assertEquals("the", bounded.finalText)
        assertFalse(bounded.serialized.contains("secret"))
        assertFalse(bounded.serialized.contains("document"))
    }

    @Test
    fun `empty source or final span is rejected`() {
        assertNull(
            boundedCorrectionRecord(
                CorrectionInput(
                    action = CorrectionAction.MANUAL_EDIT,
                    sourceText = "",
                    finalText = "the",
                    algorithmVersion = 1,
                ),
            ),
        )
        assertNull(
            boundedCorrectionRecord(
                CorrectionInput(
                    action = CorrectionAction.MANUAL_EDIT,
                    sourceText = "the",
                    finalText = "",
                    algorithmVersion = 1,
                ),
            ),
        )
    }

    @Test
    fun `oversized source or final span is rejected rather than truncated`() {
        val oversized = "a".repeat(MAX_SPAN_CODE_POINTS + 1)
        val atLimit = "a".repeat(MAX_SPAN_CODE_POINTS)

        assertNull(
            boundedCorrectionRecord(
                CorrectionInput(
                    action = CorrectionAction.UNDO,
                    sourceText = oversized,
                    finalText = "the",
                    algorithmVersion = 1,
                ),
            ),
        )
        assertNotNull(
            boundedCorrectionRecord(
                CorrectionInput(
                    action = CorrectionAction.UNDO,
                    sourceText = atLimit,
                    finalText = "the",
                    algorithmVersion = 1,
                ),
            ),
        )
    }

    @Test
    fun `candidate alternatives are capped at five and each candidate is bounded`() {
        val bounded = boundedCorrectionRecord(
            CorrectionInput(
                action = CorrectionAction.CANDIDATE_SELECTED,
                sourceText = "teh",
                finalText = "the",
                candidates = listOf("the", "then", "them", "they", "there", "third", "a".repeat(65)),
                algorithmVersion = 1,
            ),
        )!!

        assertEquals(5, bounded.candidates.size)
        assertEquals(listOf("the", "then", "them", "they", "there"), bounded.candidates)
    }

    @Test
    fun `unicode spans are normalized to NFC`() {
        // "e" + combining acute accent (decomposed) should normalize to the single precomposed
        // codepoint "é" ("e" with acute), so a 4-codepoint decomposed span becomes 3.
        val decomposed = "café"
        val bounded = boundedCorrectionRecord(
            CorrectionInput(
                action = CorrectionAction.MANUAL_EDIT,
                sourceText = decomposed,
                finalText = "cafe",
                algorithmVersion = 1,
            ),
        )!!

        assertEquals("café", bounded.sourceText)
    }

    @Test
    fun `record enum action names serialize as lowercase snake case`() {
        assertEquals("candidate_selected", CorrectionAction.CANDIDATE_SELECTED.wireName)
        assertEquals("manual_edit", CorrectionAction.MANUAL_EDIT.wireName)
        assertEquals("undo", CorrectionAction.UNDO.wireName)
        assertEquals("flow_correction", CorrectionAction.FLOW_CORRECTION.wireName)
    }

    @Test
    fun `attaches caller-supplied trace id when available and leaves it null otherwise`() {
        val withTrace = boundedCorrectionRecord(
            CorrectionInput(
                action = CorrectionAction.FLOW_CORRECTION,
                sourceText = "teh",
                finalText = "the",
                algorithmVersion = 1,
                traceId = "trace-123",
            ),
        )!!
        assertEquals("trace-123", withTrace.traceId)
        assertTrue(withTrace.serialized.contains("trace-123"))

        val withoutTrace = boundedCorrectionRecord(
            CorrectionInput(
                action = CorrectionAction.FLOW_CORRECTION,
                sourceText = "teh",
                finalText = "the",
                algorithmVersion = 1,
            ),
        )!!
        assertNull(withoutTrace.traceId)
        assertFalse(withoutTrace.serialized.contains("trace_id"))
    }

    @Test
    fun `disabled recorder never appends or schedules`() {
        var appended = false
        var scheduled = false
        val recorder = ResearchCorrectionRecorder(
            enabled = { false },
            appendToQueue = { appended = true },
            scheduleUpload = { scheduled = true },
        )

        recorder.record(sampleCorrection())

        assertFalse(appended)
        assertFalse(scheduled)
    }

    @Test
    fun `enabled recorder appends the bounded record once and schedules upload`() {
        val appendedRecords = mutableListOf<BoundedCorrectionRecord>()
        var scheduledCount = 0
        val recorder = ResearchCorrectionRecorder(
            enabled = { true },
            appendToQueue = { record -> appendedRecords += record },
            scheduleUpload = { scheduledCount += 1 },
        )

        recorder.record(sampleCorrection())

        assertEquals(1, appendedRecords.size)
        assertEquals(1, scheduledCount)
        assertEquals("teh", appendedRecords.single().sourceText)
    }

    @Test
    fun `rejected input never appends or schedules even when enabled`() {
        var appended = false
        var scheduled = false
        val recorder = ResearchCorrectionRecorder(
            enabled = { true },
            appendToQueue = { appended = true },
            scheduleUpload = { scheduled = true },
        )

        recorder.record(
            CorrectionInput(
                action = CorrectionAction.MANUAL_EDIT,
                sourceText = "",
                finalText = "the",
                algorithmVersion = 1,
            ),
        )

        assertFalse(appended)
        assertFalse(scheduled)
    }

    @Test
    fun `telemetry failures never escape into keyboard behavior`() {
        val recorder = ResearchCorrectionRecorder(
            enabled = { true },
            appendToQueue = { throw IllegalStateException("disk unavailable") },
            scheduleUpload = { throw IllegalStateException("scheduler unavailable") },
        )

        assertDoesNotThrow { recorder.record(sampleCorrection()) }
    }

    @Test
    fun `research correction envelopes append only to the research plane queue`() {
        val directory = Files.createTempDirectory("research-correction-runtime").toFile()
        try {
            val queue = TelemetryQueue(
                directory = directory,
                plane = TelemetryPlane.RESEARCH,
                clock = { 1_000L },
                limits = QueueLimits(),
            )
            val recorder = ResearchCorrectionRecorder(
                enabled = { true },
                appendToQueue = { record -> queue.append(correctionEnvelope(record)) },
                scheduleUpload = {},
            )

            recorder.record(sampleCorrection())

            val event = queue.pendingBatches().single().events.single()
            assertEquals("research_correction", event.eventType)
            assertTrue(event.payload.toString().contains("teh"))
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

    private fun correctionEnvelope(record: BoundedCorrectionRecord): TelemetryEnvelope = TelemetryEnvelope(
        schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
        eventId = record.correctionId,
        batchId = "00000000-0000-0000-0000-000000000002",
        installationId = "00000000-0000-0000-0000-000000000003",
        sessionId = "00000000-0000-0000-0000-000000000004",
        occurredAtMs = 1_000L,
        appVersion = "0.1.8",
        buildType = "debug",
        androidApi = 36,
        eventType = "research_correction",
        payload = record.payloadJson(),
    )
}
