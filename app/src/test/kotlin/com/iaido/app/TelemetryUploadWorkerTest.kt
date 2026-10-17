package com.iaido.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
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
    fun `a permanently rejected batch is dropped and the batches behind it still upload`() = runBlocking {
        val removed = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        val dispositions = ArrayDeque(listOf(UploadDisposition.DISCARD, UploadDisposition.ACKNOWLEDGED))
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1"), sampleBatch("batch-2")) },
            upload = { _, batch ->
                uploaded += batch.batchId
                dispositions.removeFirst()
            },
            acknowledge = { _, batchId -> removed += batchId },
        )

        assertEquals(WorkerDisposition.SUCCESS, runner.run(TelemetryPlane.RESEARCH))
        assertEquals(listOf("batch-1", "batch-2"), uploaded)
        assertEquals(listOf("batch-1", "batch-2"), removed)
    }

    @Test
    fun `only explicit permanent rejections delete a queued batch`() = runBlocking {
        val expectations = mapOf(
            302 to true, 408 to true, 403 to true, 404 to true, 426 to true, 503 to true,
            400 to false, 409 to false, 413 to false, 422 to false,
        )
        for ((status, kept) in expectations) {
            val directory = Files.createTempDirectory("telemetry-runner").toFile()
            try {
                val queue = TelemetryQueue(directory, TelemetryPlane.RESEARCH, { 1_000L }, QueueLimits())
                queue.append(sampleBatch("unused").events.single())
                val transport = TelemetryTransport(
                    URL("https://collector.invalid"),
                    TelemetryInstallationStore(
                        FakeInstallationCredentialStorage(
                            TelemetryInstallation("10000000-0000-4000-8000-000000000001", "write", "delete"),
                        ),
                    ),
                ) { url -> FixedResponseConnection(url, status) }
                val runner = TelemetryUploadRunner(
                    consentEnabled = { true },
                    pendingBatches = { queue.pendingBatches() },
                    upload = { plane, batch -> transport.upload(plane, batch) },
                    acknowledge = { _, batchId -> queue.acknowledge(batchId) },
                )

                runner.run(TelemetryPlane.RESEARCH)

                assertEquals(kept, queue.pendingBatches().isNotEmpty(), "status $status")
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `a local failure while uploading keeps the batch`() = runBlocking {
        val directory = Files.createTempDirectory("telemetry-runner").toFile()
        try {
            val queue = TelemetryQueue(directory, TelemetryPlane.RESEARCH, { 1_000L }, QueueLimits())
            queue.append(sampleBatch("unused").events.single())
            val transport = TelemetryTransport(
                URL("https://collector.invalid"),
                TelemetryInstallationStore(
                    FakeInstallationCredentialStorage(
                        TelemetryInstallation("10000000-0000-4000-8000-000000000001", "write", "delete"),
                    ),
                ),
            ) { error("unexpected local failure") }
            val runner = TelemetryUploadRunner(
                consentEnabled = { true },
                pendingBatches = { queue.pendingBatches() },
                upload = { plane, batch -> transport.upload(plane, batch) },
                acknowledge = { _, batchId -> queue.acknowledge(batchId) },
            )

            assertEquals(WorkerDisposition.RETRY, runner.run(TelemetryPlane.RESEARCH))
            assertTrue(queue.pendingBatches().isNotEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a transient failure keeps the batch and asks for a backoff retry`() = runBlocking {
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1"), sampleBatch("batch-2")) },
            upload = { _, _ -> UploadDisposition.RETRY },
            acknowledge = { _, _ -> error("must not remove a batch that may still be accepted") },
        )

        assertEquals(WorkerDisposition.RETRY, runner.run(TelemetryPlane.RESEARCH))
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
    fun `missing credentials are provisioned before any upload`() = runBlocking {
        val calls = mutableListOf<String>()
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1")) },
            upload = { _, batch ->
                calls += "upload:${batch.batchId}"
                UploadDisposition.ACKNOWLEDGED
            },
            acknowledge = { _, _ -> },
            ensureProvisioned = {
                calls += "provision"
                UploadDisposition.ACKNOWLEDGED
            },
        )

        assertEquals(WorkerDisposition.SUCCESS, runner.run(TelemetryPlane.RESEARCH))
        assertEquals(listOf("provision", "upload:batch-1"), calls)
    }

    @Test
    fun `provisioning failure keeps batches queued and never uploads`() = runBlocking {
        for ((provisioning, expected) in listOf(
            UploadDisposition.RETRY to WorkerDisposition.RETRY,
            UploadDisposition.DISCARD to WorkerDisposition.FAILURE,
        )) {
            val runner = TelemetryUploadRunner(
                consentEnabled = { true },
                pendingBatches = { listOf(sampleBatch("batch-1")) },
                upload = { _, _ -> error("must not upload without server issued credentials") },
                acknowledge = { _, _ -> error("must not acknowledge") },
                ensureProvisioned = { provisioning },
            )

            assertEquals(expected, runner.run(TelemetryPlane.RESEARCH))
        }
    }

    @Test
    fun `a second auth rejection in one run invalidates credentials, stops retrying and keeps the batch`() = runBlocking {
        var invalidated = false
        val uploaded = mutableListOf<String>()
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-1"), sampleBatch("batch-2")) },
            upload = { _, batch ->
                uploaded += batch.batchId
                UploadDisposition.UNAUTHORIZED
            },
            acknowledge = { _, _ -> error("must not acknowledge or drop on auth failure") },
            invalidateCredentials = { rejected ->
                assertEquals("10000000-0000-4000-8000-000000000001", rejected)
                invalidated = true
            },
        )

        assertEquals(WorkerDisposition.FAILURE, runner.run(TelemetryPlane.RESEARCH))
        assertTrue(invalidated)
        // The first 401 re-provisions once within the run and retries; the second one stops it.
        assertEquals(listOf("batch-1", "batch-1"), uploaded)
    }

    /**
     * KEEP-policy delivery gap: the gesture's own schedule() is dropped while this worker runs,
     * so the running worker must pick up a batch that was started mid-upload itself.
     */
    @Test
    fun `an event appended while a batch is uploading is uploaded by the same run`() = runBlocking {
        val directory = Files.createTempDirectory("telemetry-runner").toFile()
        try {
            val queue = TelemetryQueue(directory, TelemetryPlane.RESEARCH, { 1_000L }, QueueLimits())
            val first = sampleBatch("unused").events.single()
            queue.append(first)
            val uploadedEvents = mutableListOf<String>()
            var gestureDuringUpload = true
            val runner = TelemetryUploadRunner(
                consentEnabled = { true },
                pendingBatches = { queue.pendingBatches() },
                upload = { _, batch ->
                    if (gestureDuringUpload) {
                        gestureDuringUpload = false
                        // The batch being sent is sealed, so this lands in a fresh open batch.
                        queue.append(first.copy(eventId = "30000000-0000-4000-8000-000000000010"))
                    }
                    uploadedEvents += batch.events.map { it.eventId }
                    UploadDisposition.ACKNOWLEDGED
                },
                acknowledge = { _, batchId -> queue.acknowledge(batchId) },
            )

            assertEquals(WorkerDisposition.SUCCESS, runner.run(TelemetryPlane.RESEARCH))
            assertEquals(
                listOf("30000000-0000-4000-8000-000000000009", "30000000-0000-4000-8000-000000000010"),
                uploadedEvents,
            )
            assertTrue(queue.pendingBatches().isEmpty(), "the mid-upload event is still waiting for a new gesture")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a run that keeps finding new batches stops after a bounded number of passes and retries`() = runBlocking {
        var next = 0
        var uploads = 0
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { listOf(sampleBatch("batch-${next++}")) },
            upload = { _, _ ->
                uploads += 1
                UploadDisposition.ACKNOWLEDGED
            },
            acknowledge = { _, _ -> },
        )

        assertEquals(WorkerDisposition.RETRY, runner.run(TelemetryPlane.RESEARCH))
        assertEquals(TelemetryUploadRunner.MAX_PASSES, uploads)
    }

    @Test
    fun `a 401 re-provisions within the same run and the batches still upload`() = runBlocking {
        val calls = mutableListOf<String>()
        val dispositions = ArrayDeque(listOf(UploadDisposition.UNAUTHORIZED, UploadDisposition.ACKNOWLEDGED))
        val runner = TelemetryUploadRunner(
            consentEnabled = { true },
            pendingBatches = { if ("ack:batch-1" in calls) emptyList() else listOf(sampleBatch("batch-1")) },
            upload = { _, batch ->
                calls += "upload:${batch.batchId}"
                dispositions.removeFirst()
            },
            acknowledge = { _, batchId -> calls += "ack:$batchId" },
            ensureProvisioned = {
                calls += "provision"
                UploadDisposition.ACKNOWLEDGED
            },
            invalidateCredentials = { calls += "invalidate" },
        )

        assertEquals(WorkerDisposition.SUCCESS, runner.run(TelemetryPlane.RESEARCH))
        assertEquals(
            listOf("provision", "upload:batch-1", "invalidate", "provision", "upload:batch-1", "ack:batch-1"),
            calls,
        )
    }

    @Test
    fun `scheduler requires a configured HTTPS endpoint but not credentials, so the worker can re-provision`() {
        assertFalse(shouldScheduleTelemetry(""))
        assertFalse(shouldScheduleTelemetry("http://collector.invalid"))
        assertTrue(shouldScheduleTelemetry("https://collector.invalid"))
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

/** A collector that answers every request with [status] and an empty JSON body. */
private class FixedResponseConnection(url: URL, private val status: Int) : HttpURLConnection(url) {
    override fun getOutputStream(): ByteArrayOutputStream = ByteArrayOutputStream()

    override fun getResponseCode(): Int = status

    override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())

    override fun getErrorStream(): InputStream = getInputStream()

    override fun disconnect() = Unit

    override fun usingProxy(): Boolean = false

    override fun connect() = Unit
}
