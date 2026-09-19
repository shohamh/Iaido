package com.iaido.app

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryUploadWorkerTest {
    @Test
    fun `worker rechecks plane consent before every batch and stops successfully when revoked`() = runBlocking {
        val batches = listOf(sampleBatch("batch-1"), sampleBatch("batch-2"))
        val consentAnswers = ArrayDeque(listOf(true, false))
        val uploaded = mutableListOf<String>()
        val acknowledged = mutableListOf<String>()
        val runner = TelemetryUploadRunner(
            consentEnabled = { consentAnswers.removeFirst() },
            pendingBatches = { batches },
            upload = { _, batch ->
                uploaded += batch.batchId
                UploadDisposition.ACKNOWLEDGED
            },
            acknowledge = { _, batchId -> acknowledged += batchId },
        )

        val result = runner.run(TelemetryPlane.RESEARCH)

        assertEquals(WorkerDisposition.SUCCESS, result)
        assertEquals(listOf("batch-1"), uploaded)
        assertEquals(listOf("batch-1"), acknowledged)
        assertTrue(consentAnswers.isEmpty())
    }

    @Test
    fun `worker acknowledges only accepted batches`() = runBlocking {
        val acknowledged = mutableListOf<String>()
        val dispositions = ArrayDeque(
            listOf(UploadDisposition.ACKNOWLEDGED, UploadDisposition.RETRY),
        )
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1"), sampleBatch("batch-2")) },
            upload = { _, _ -> dispositions.removeFirst() },
            acknowledge = { _, batchId -> acknowledged += batchId },
        )

        val result = runner.run(TelemetryPlane.DIAGNOSTICS)

        assertEquals(WorkerDisposition.RETRY, result)
        assertEquals(listOf("batch-1"), acknowledged)
    }

    @Test
    fun `schema or auth rejection fails without acknowledging`() = runBlocking {
        var acknowledged = false
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1")) },
            upload = { _, _ -> UploadDisposition.DISCARD },
            acknowledge = { _, _ -> acknowledged = true },
        )

        assertEquals(WorkerDisposition.FAILURE, runner.run(TelemetryPlane.DIAGNOSTICS))
        assertFalse(acknowledged)
    }

    @Test
    fun `telemetry transport failure never escapes worker runner`() = runBlocking {
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1")) },
            upload = { _, _ -> error("collector unavailable") },
            acknowledge = { _, _ -> error("must not acknowledge") },
        )

        assertEquals(WorkerDisposition.RETRY, runner.run(TelemetryPlane.DIAGNOSTICS))
    }

    @Test
    fun `scheduler requires configured endpoint and existing write credential`() {
        assertFalse(shouldScheduleTelemetry("", "write-secret"))
        assertFalse(shouldScheduleTelemetry("http://collector.invalid", "write-secret"))
        assertFalse(shouldScheduleTelemetry("https://collector.invalid", null))
        assertFalse(shouldScheduleTelemetry("https://collector.invalid", ""))
        assertTrue(shouldScheduleTelemetry("https://collector.invalid", "write-secret"))
    }

    @Test
    fun `unique work names are isolated by plane`() {
        assertEquals("iaido-telemetry-diagnostics", telemetryWorkName(TelemetryPlane.DIAGNOSTICS))
        assertEquals("iaido-telemetry-research", telemetryWorkName(TelemetryPlane.RESEARCH))
    }

    private fun sampleBatch(batchId: String): TelemetryBatch = TelemetryBatch(
        batchId = batchId,
        events = listOf(
            TelemetryEnvelope(
                schemaVersion = 1,
                eventId = "30000000-0000-4000-8000-000000000009",
                batchId = "20000000-0000-4000-8000-000000000008",
                installationId = "10000000-0000-4000-8000-000000000001",
                sessionId = "10000000-0000-4000-8000-000000000002",
                occurredAtMs = 1_750_000_000_000,
                appVersion = "0.1.4",
                buildType = "release",
                androidApi = 36,
                eventType = "gesture_outcome",
                payload = buildJsonObject {},
            ),
        ),
    )
}
