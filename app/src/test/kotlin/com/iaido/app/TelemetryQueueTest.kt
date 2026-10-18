package com.iaido.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
    fun `every queued batch fits the uncompressed request limit the transport and server enforce`() {
        withQueue(plane = TelemetryPlane.RESEARCH) { queue, _ ->
            // Swipe traces compress very well, so 60 of them stay far below the 256 KiB gzip cap
            // while their uncompressed JSON body is well over the 256 KiB request limit.
            (0 until 60).forEach { index -> queue.append(swipeTraceEnvelope(1_000L + index)) }

            val batches = queue.pendingBatches()
            assertEquals(60, batches.sumOf { it.events.size })
            val transport = TelemetryTransport(
                URL("https://collector.invalid"),
                TelemetryInstallationStore(
                    FakeInstallationCredentialStorage(
                        TelemetryInstallation("00000000-0000-0000-0000-000000000001", "write", "delete"),
                    ),
                ),
            ) { url -> EchoAcknowledgementConnection(url) }
            batches.forEach { batch ->
                assertEquals(UploadDisposition.ACKNOWLEDGED, transport.upload(TelemetryPlane.RESEARCH, batch))
            }
        }
    }

    @Test
    fun `a batch never mixes installations`() {
        withQueue { queue, _ ->
            queue.append(diagnosticEnvelope(1_000L))
            queue.append(diagnosticEnvelope(1_001L).copy(installationId = "00000000-0000-0000-0000-0000000000ff"))
            queue.append(diagnosticEnvelope(1_002L).copy(installationId = "00000000-0000-0000-0000-0000000000ff"))

            val batches = queue.pendingBatches()

            assertEquals(listOf(1, 2), batches.map { it.events.size })
            assertTrue(batches.all { batch -> batch.events.map { it.installationId }.distinct().size == 1 })
        }
    }

    @Test
    fun `events appended while a batch is being uploaded survive its acknowledgement`() {
        withQueue { ime, root ->
            val worker = TelemetryQueue(root, TelemetryPlane.DIAGNOSTICS, { 1_000L }, QueueLimits())
            ime.append(diagnosticEnvelope(1_000L))

            val inFlight = worker.pendingBatches().single()
            ime.append(diagnosticEnvelope(1_001L))

            // A batch handed out for upload never changes, so a retry resends identical bytes.
            assertEquals(inFlight, worker.pendingBatches().first())
            worker.acknowledge(inFlight.batchId)

            assertEquals(listOf(1_001L), worker.pendingBatches().flatMap { it.events }.map { it.occurredAtMs })
        }
    }

    @Test
    fun `concurrent appends and upload acknowledgements neither lose nor duplicate events`() {
        withQueue { ime, root ->
            val worker = TelemetryQueue(root, TelemetryPlane.DIAGNOSTICS, { 1_000L }, QueueLimits())
            val total = 150
            val uploaded = mutableListOf<Long>()
            val appender = Thread { (0 until total).forEach { ime.append(diagnosticEnvelope(1_000L + it)) } }
            appender.start()
            while (appender.isAlive) {
                worker.pendingBatches().forEach { batch ->
                    uploaded += batch.events.map { it.occurredAtMs }
                    worker.acknowledge(batch.batchId)
                }
            }
            appender.join()
            worker.pendingBatches().forEach { batch -> uploaded += batch.events.map { it.occurredAtMs } }

            assertEquals((0 until total).map { 1_000L + it }, uploaded.sorted())
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
    fun `age expiry never rewrites a sealed batch and deletes it only when every event expired`() {
        var now = 1_000L
        withQueue(QueueLimits(maxAgeMs = 100L), clock = { now }) { queue, _ ->
            queue.append(diagnosticEnvelope(950L))
            queue.append(diagnosticEnvelope(990L))
            val sealed = queue.pendingBatches().single()
            assertEquals(2, sealed.events.size)

            now = 1_060L // 950 is now expired, 990 is not
            queue.enforceLimits()

            assertEquals(sealed, queue.pendingBatches().single())

            now = 1_100L // both expired
            queue.enforceLimits()

            assertTrue(queue.pendingBatches().isEmpty())
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

    private fun swipeTraceEnvelope(occurredAtMs: Long): TelemetryEnvelope =
        diagnosticEnvelope(occurredAtMs).copy(
            eventType = "gesture_trace",
            payload = buildJsonObject {
                put("trace_id", JsonPrimitive("trace-$occurredAtMs"))
                put("layout_id", JsonPrimitive("qwerty"))
                put(
                    "points",
                    buildJsonArray {
                        (0 until 120).forEach { index ->
                            add(
                                buildJsonObject {
                                    put("pointer_id", 0)
                                    put("action", 2)
                                    put("t_ms", index * 8L)
                                    put("x", 0.25 + index / 1000.0)
                                    put("y", 0.5)
                                },
                            )
                        }
                    },
                )
            },
        )

    private fun batchFiles(root: File, plane: TelemetryPlane): List<File> =
        File(root, plane.name.lowercase()).listFiles { file -> file.extension == "batch" || file.extension == "sealed" }
            ?.sortedBy { it.name } ?: emptyList()
}

/** A collector that accepts every batch, echoing back the batch id the transport sent. */
private class EchoAcknowledgementConnection(url: URL) : HttpURLConnection(url) {
    private val body = ByteArrayOutputStream()

    override fun getOutputStream(): ByteArrayOutputStream = body

    override fun getResponseCode(): Int = 202

    override fun getInputStream(): InputStream = ByteArrayInputStream(
        """{"batch_id":"${getRequestProperty("X-Iaido-Batch-Id")}","accepted":true}"""
            .toByteArray(StandardCharsets.UTF_8),
    )

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}

class TelemetryOffMainThreadTest {
    @Test
    fun `queue work runs later on the telemetry executor, in order, and never throws to the caller`() {
        val posted = ArrayDeque<Runnable>()
        val ran = mutableListOf<String>()

        telemetryOffMainThread(posted::addLast) { ran += "append" }
        telemetryOffMainThread(posted::addLast) { error("disk full") }
        telemetryOffMainThread(posted::addLast) { ran += "schedule" }

        assertTrue(ran.isEmpty(), "work must not run on the calling (IME main) thread")
        while (posted.isNotEmpty()) posted.removeFirst().run()
        assertEquals(listOf("append", "schedule"), ran)
    }
}
