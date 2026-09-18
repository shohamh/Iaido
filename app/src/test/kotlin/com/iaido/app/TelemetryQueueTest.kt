package com.iaido.app

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryQueueTest {
    @Test
    fun `queue rolls after event limit and deletes only acknowledged batch`() {
        withQueue(QueueLimits(maxBatchEvents = 2)) { queue, _ ->
            queue.append(diagnosticEnvelope("one", 1_000L))
            queue.append(diagnosticEnvelope("two", 1_000L))
            queue.append(diagnosticEnvelope("three", 1_000L))

            val batches = queue.pendingBatches()
            assertEquals(2, batches.size)
            assertEquals(2, batches.first().events.size)

            queue.acknowledge(batches.first().batchId)

            assertEquals(1, queue.pendingBatches().size)
            assertEquals("three", queue.pendingBatches().single().events.single().payload["value"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `queue rolls when the encoded batch reaches its byte limit`() {
        withQueue(QueueLimits(maxBatchEvents = 100, maxBatchBytes = 600)) { queue, _ ->
            queue.append(diagnosticEnvelope("first-value", 1_000L))
            queue.append(diagnosticEnvelope("second-value", 1_000L))

            assertEquals(2, queue.pendingBatches().size)
        }
    }

    @Test
    fun `queue expires records older than the age limit`() {
        withQueue(QueueLimits(maxAgeMs = 100L), clock = { 1_000L }) { queue, _ ->
            queue.append(diagnosticEnvelope("old", 899L))
            queue.append(diagnosticEnvelope("new", 900L))

            queue.enforceLimits()

            assertEquals(1, queue.pendingBatches().size)
            assertEquals("new", queue.pendingBatches().single().events.single().payload["value"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `queue evicts oldest batches first under the queued byte cap`() {
        withQueue(QueueLimits(maxBatchEvents = 1, maxQueuedBytes = 900L)) { queue, directory ->
            queue.append(diagnosticEnvelope("oldest", 1_000L))
            queue.append(diagnosticEnvelope("middle", 1_001L))
            queue.append(diagnosticEnvelope("newest", 1_002L))

            val batches = queue.pendingBatches()

            assertTrue(batches.size < 3)
            assertEquals("newest", batches.last().events.single().payload["value"]?.toString()?.trim('"'))
            assertTrue(directory.listFiles()?.all { it.extension == "batch" } == true)
        }
    }

    @Test
    fun `diagnostics and research queues remain in separate directories`() {
        val root = Files.createTempDirectory("telemetry-planes").toFile()
        try {
            val diagnostics = TelemetryQueue(File(root, "diagnostics"), { 1_000L }, QueueLimits())
            val research = TelemetryQueue(File(root, "research"), { 1_000L }, QueueLimits())

            diagnostics.append(diagnosticEnvelope("diagnostic", 1_000L))
            research.append(diagnosticEnvelope("research", 1_000L))

            assertEquals(1, diagnostics.pendingBatches().size)
            assertEquals(1, research.pendingBatches().size)
            assertTrue(File(root, "diagnostics").listFiles()?.none { it in File(root, "research").listFiles().orEmpty() } == true)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withQueue(
        limits: QueueLimits,
        clock: () -> Long = { 1_000L },
        block: (TelemetryQueue, File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("telemetry-queue").toFile()
        try {
            block(TelemetryQueue(directory, clock, limits), directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun diagnosticEnvelope(value: String, occurredAtMs: Long): TelemetryEnvelope = TelemetryEnvelope(
        schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
        eventId = "event-$value",
        batchId = "input-batch",
        installationId = "installation",
        sessionId = "session",
        occurredAtMs = occurredAtMs,
        appVersion = "0.1.3",
        buildType = "debug",
        androidApi = 36,
        eventType = "gesture_outcome",
        payload = buildJsonObject { put("value", kotlinx.serialization.json.JsonPrimitive(value)) },
    )
}
