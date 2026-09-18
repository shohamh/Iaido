package com.iaido.app

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryQueueTest {
    @Test
    fun `queue rolls after event limit and deletes only acknowledged batch`() {
        withQueue(QueueLimits(maxBatchEvents = 2)) { queue, _ ->
            queue.append(diagnosticEnvelope(1_000L))
            queue.append(diagnosticEnvelope(1_001L))
            queue.append(diagnosticEnvelope(1_002L))

            val batches = queue.pendingBatches()
            assertEquals(2, batches.size)
            assertEquals(2, batches.first().events.size)

            queue.acknowledge(batches.first().batchId)

            assertEquals(1, queue.pendingBatches().size)
            assertEquals(1_002L, queue.pendingBatches().single().events.single().occurredAtMs)
        }
    }

    @Test
    fun `queue rolls when the compressed batch reaches its byte limit`() {
        withQueue(
            limits = QueueLimits(maxBatchEvents = 100, maxBatchBytes = 250),
            plane = TelemetryPlane.RESEARCH,
        ) { queue, root ->
            queue.append(researchEnvelope(1_000L, "a".repeat(BoundedResearchText.MAX_LENGTH)))
            queue.append(researchEnvelope(1_001L, "b".repeat(BoundedResearchText.MAX_LENGTH)))

            assertEquals(2, queue.pendingBatches().size)
            assertTrue(batchFiles(root, TelemetryPlane.RESEARCH).all { it.length() <= 250L })
        }
    }

    @Test
    fun `queue expires records older than the age limit`() {
        withQueue(QueueLimits(maxAgeMs = 100L), clock = { 1_000L }) { queue, _ ->
            queue.append(diagnosticEnvelope(899L))
            queue.append(diagnosticEnvelope(900L))

            queue.enforceLimits()

            assertEquals(1, queue.pendingBatches().size)
            assertEquals(900L, queue.pendingBatches().single().events.single().occurredAtMs)
        }
    }

    @Test
    fun `queue evicts oldest batches first using durable sequence above nine`() {
        withQueue(
            limits = QueueLimits(maxBatchEvents = 1, maxQueuedBytes = 700L),
            clock = { 1_000L },
        ) { queue, _ ->
            (0L..11L).forEach { offset -> queue.append(diagnosticEnvelope(1_000L + offset)) }

            val batches = queue.pendingBatches()

            assertTrue(batches.size < 12)
            assertTrue(batches.isNotEmpty())
            assertEquals(1_011L, batches.last().events.single().occurredAtMs)
            assertTrue(batches.zipWithNext().all { (first, second) ->
                first.events.single().occurredAtMs < second.events.single().occurredAtMs
            })
        }
    }

    @Test
    fun `same root isolates diagnostics and research planes`() {
        val root = Files.createTempDirectory("telemetry-planes").toFile()
        try {
            val diagnostics = TelemetryQueue(root, TelemetryPlane.DIAGNOSTICS, { 1_000L }, QueueLimits())
            val research = TelemetryQueue(root, TelemetryPlane.RESEARCH, { 1_000L }, QueueLimits())

            diagnostics.append(diagnosticEnvelope(1_000L))
            research.append(researchEnvelope(1_001L, "bounded sample"))

            assertEquals(1, diagnostics.pendingBatches().size)
            assertEquals(1, research.pendingBatches().size)
            assertEquals(1_000L, diagnostics.pendingBatches().single().events.single().occurredAtMs)
            assertEquals(1_001L, research.pendingBatches().single().events.single().occurredAtMs)
            assertTrue(batchFiles(root, TelemetryPlane.DIAGNOSTICS).isNotEmpty())
            assertTrue(batchFiles(root, TelemetryPlane.RESEARCH).isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `diagnostics queue rejects readable payloads before persistence`() {
        withQueue { queue, root ->
            queue.append(
                diagnosticEnvelope(1_000L).copy(
                    payload = buildJsonObject {
                        put("event_type", "raw_touch_path")
                        put("points", "[1,2]")
                        put("typed_word", "secret")
                    },
                ),
            )

            assertTrue(queue.pendingBatches().isEmpty())
            assertTrue(batchFiles(root, TelemetryPlane.DIAGNOSTICS).isEmpty())
        }
    }

    @Test
    fun `corrupt batches do not prevent append or retention of healthy batches`() {
        withQueue(QueueLimits(maxBatchEvents = 1), clock = { 1_000L }) { queue, root ->
            queue.append(diagnosticEnvelope(900L))
            queue.append(diagnosticEnvelope(1_000L))
            val corrupt = batchFiles(root, TelemetryPlane.DIAGNOSTICS).first()
            corrupt.writeText("partial gzip record")

            queue.append(diagnosticEnvelope(1_001L))
            queue.enforceLimits()

            assertEquals(listOf(1_000L, 1_001L), queue.pendingBatches().flatMap { it.events }.map { it.occurredAtMs })
        }
    }

    @Test
    fun `deleteAll removes compressed batches and failed-rewrite temporary files`() {
        withQueue { queue, root ->
            queue.append(diagnosticEnvelope(1_000L))
            val planeDirectory = File(root, TelemetryPlane.DIAGNOSTICS.name.lowercase())
            File(planeDirectory, "payload.batch.tmp").writeText("payload")
            File(planeDirectory, "other.tmp").writeText("payload")

            queue.deleteAll()

            assertFalse(planeDirectory.walkTopDown().any { it.isFile })
        }
    }

    private fun withQueue(
        limits: QueueLimits = QueueLimits(),
        clock: () -> Long = { 1_000L },
        plane: TelemetryPlane = TelemetryPlane.DIAGNOSTICS,
        block: (TelemetryQueue, File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("telemetry-queue").toFile()
        try {
            block(TelemetryQueue(directory, plane, clock, limits), directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun diagnosticEnvelope(occurredAtMs: Long): TelemetryEnvelope = TelemetryEnvelope(
        schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
        eventId = "00000000-0000-0000-0000-${occurredAtMs.toString().padStart(12, '0')}",
        batchId = "00000000-0000-0000-0000-000000000000",
        installationId = "00000000-0000-0000-0000-000000000001",
        sessionId = "00000000-0000-0000-0000-000000000002",
        occurredAtMs = occurredAtMs,
        appVersion = "0.1.3",
        buildType = "debug",
        androidApi = 36,
        eventType = "gesture_outcome",
        payload = buildJsonObject {
            put("event_type", JsonPrimitive("gesture_outcome"))
            put("outcome", JsonPrimitive("ACCEPTED"))
        },
    )

    private fun researchEnvelope(occurredAtMs: Long, text: String): TelemetryEnvelope =
        diagnosticEnvelope(occurredAtMs).copy(
            eventType = "text_sample",
            payload = buildJsonObject { put("text", JsonPrimitive(BoundedResearchText(text).value)) },
        )

    private fun batchFiles(root: File, plane: TelemetryPlane): List<File> =
        File(root, plane.name.lowercase()).listFiles { file -> file.extension == "batch" }
            ?.sortedBy { it.name } ?: emptyList()
}
