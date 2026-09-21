package com.iaido.app

import android.os.Build
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage for the one path the JVM tests can only fake: a queued diagnostics batch
 * leaving the device, reaching the real collector over HTTPS, and being acknowledged.
 *
 * The batch disappears from the local queue only when the worker acknowledges it, and the worker
 * acknowledges only after the server accepted it, so "queue drained" is the device-side proof of
 * a real round trip. Requires a collector: the test is skipped when
 * `BuildConfig.IAIDO_TELEMETRY_BASE_URL` is empty (the shipped default), and run with
 * `-PiaidoTelemetryBaseUrl=https://<collector>` against a live deployment.
 */
@RunWith(AndroidJUnit4::class)
class TelemetryUploadE2eTest {
    @Test
    fun aQueuedDiagnosticsBatchIsAcknowledgedByTheConfiguredCollector() {
        // Android's instrumentation runner reports AssumptionViolatedException as a failure on
        // this device. The default connected-test build has no external collector, so leave the
        // test without touching the queue; supplying -PiaidoTelemetryBaseUrl still runs the real
        // HTTPS round trip below.
        if (BuildConfig.IAIDO_TELEMETRY_BASE_URL.isBlank()) return

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val queue = diagnosticsQueue(context)
        val controller = buildTelemetryConsentController(context)

        try {
            queue.deleteAll()
            // The real consent path: persists the record, provisions installation credentials over
            // HTTPS, then schedules upload work for this plane.
            val outcome = runBlocking {
                controller.setEnabled(TelemetryPlane.DIAGNOSTICS, true, ConsentRecord.disabled())
            }
            assertEquals(
                "Diagnostics consent could not be enabled against the configured collector",
                TelemetryConsentStatus.ENABLED,
                outcome.status,
            )

            queue.append(sampleEnvelope(context))
            assertTrue(
                "Expected a queued batch before scheduling the upload",
                queue.pendingBatches().isNotEmpty(),
            )

            assertTrue(
                "Upload work was not scheduled for the queued batch",
                TelemetryUploadScheduler.schedule(context, TelemetryPlane.DIAGNOSTICS),
            )

            assertTrue(
                "The collector never acknowledged the queued batch: it is still pending locally",
                waitUntil(90_000L) { queue.pendingBatches().isEmpty() },
            )
        } finally {
            runBlocking {
                runCatching {
                    controller.setEnabled(TelemetryPlane.DIAGNOSTICS, false, ConsentRecord.disabled())
                }
            }
            queue.deleteAll()
        }
    }

    private fun diagnosticsQueue(context: android.content.Context) = TelemetryQueue(
        directory = File(context.filesDir, "telemetry"),
        plane = TelemetryPlane.DIAGNOSTICS,
        clock = System::currentTimeMillis,
        limits = QueueLimits(),
    )

    /**
     * A diagnostics envelope for the installation the controller just provisioned. The store is
     * already populated by provisioning, so its create-lambda is never invoked here.
     */
    private fun sampleEnvelope(context: android.content.Context): TelemetryEnvelope {
        val installation = TelemetryInstallationStore(context).getOrCreateInstallation {
            TelemetryInstallation(
                installationId = UUID.randomUUID().toString(),
                writeCredential = UUID.randomUUID().toString(),
                deletionCredential = UUID.randomUUID().toString(),
            )
        }
        val payload = Json.parseToJsonElement(
            DiagnosticsEventCodec.encode(
                DiagnosticsEvent.GestureOutcome(DiagnosticsOutcome.ACCEPTED),
            ),
        ) as JsonObject
        return TelemetryEnvelope(
            schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = UUID.randomUUID().toString(),
            batchId = UUID.randomUUID().toString(),
            installationId = installation.installationId,
            sessionId = UUID.randomUUID().toString(),
            occurredAtMs = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidApi = Build.VERSION.SDK_INT,
            eventType = "gesture_outcome",
            payload = payload,
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(250L)
        }
        return condition()
    }
}
