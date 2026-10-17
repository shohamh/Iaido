package com.iaido.app

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelemetryBackgroundWriterTest {
    @Test
    fun `an append queued before consent is revoked never lands and schedules no upload`() {
        withQueue { queue ->
            val posted = ArrayDeque<Runnable>()
            var consent = true
            var scheduled = 0
            val writer = TelemetryBackgroundWriter(posted::addLast, { consent }, { queue }, { scheduled += 1 })

            writer.record { envelope(1_000L) }
            writer.schedule()
            consent = false
            while (posted.isNotEmpty()) posted.removeFirst().run()

            assertTrue(queue.pendingBatches().isEmpty())
            assertEquals(0, scheduled)
        }
    }

    @Test
    fun `deleting the pending queue waits for appends already in flight on the telemetry thread`() {
        withQueue { queue ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val release = CountDownLatch(1)
                val writer = TelemetryBackgroundWriter(executor, { true }, { queue }, {})
                // Consent was still on when this append started, and it is slow to build.
                writer.record {
                    release.await(5, TimeUnit.SECONDS)
                    envelope(1_000L)
                }

                val deletion = Thread { runBlocking { onTelemetryThread(executor) { queue.deleteAll() } } }
                deletion.start()
                Thread.sleep(200)
                release.countDown()
                deletion.join()
                executor.submit {}.get(5, TimeUnit.SECONDS) // everything posted so far has run

                assertTrue(queue.pendingBatches().isEmpty())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun withQueue(block: (TelemetryQueue) -> Unit) {
        val directory: File = Files.createTempDirectory("telemetry-writer").toFile()
        try {
            block(TelemetryQueue(directory, TelemetryPlane.RESEARCH, { 1_000L }, QueueLimits()))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun envelope(occurredAtMs: Long) = TelemetryEnvelope(
        schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
        eventId = "00000000-0000-0000-0000-${occurredAtMs.toString().padStart(12, '0')}",
        batchId = "00000000-0000-0000-0000-000000000000",
        installationId = "00000000-0000-0000-0000-000000000001",
        sessionId = "00000000-0000-0000-0000-000000000002",
        occurredAtMs = occurredAtMs,
        appVersion = "0.1.22",
        buildType = "debug",
        androidApi = 35,
        eventType = "text_sample",
        payload = buildJsonObject { put("text", JsonPrimitive("sample")) },
    )
}
