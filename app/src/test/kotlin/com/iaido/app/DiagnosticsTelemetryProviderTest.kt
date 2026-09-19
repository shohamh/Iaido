package com.iaido.app

import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Focused tests for [DiagnosticsTelemetryProvider]: the process-wide wiring around
 * [DiagnosticsTelemetry], as opposed to [DiagnosticsTelemetry] itself (covered by
 * [DiagnosticsTelemetryTest]).
 */
class DiagnosticsTelemetryProviderTest {
    @AfterEach
    fun tearDown() {
        DiagnosticsTelemetryProvider.resetForTest()
    }

    @Test
    fun `consent-disabled provider instance never appends or schedules`() {
        val queue = mutableListOf<TelemetryEnvelope>()
        var scheduled = false
        val telemetry = DiagnosticsTelemetry(
            diagnosticsEnabled = DiagnosticsTelemetryProvider::isEnabled,
            appendToQueue = { queue += it },
            scheduleUpload = { scheduled = true },
            envelopeFactory = ::sampleEnvelopeFor,
        )
        DiagnosticsTelemetryProvider.installForTest(telemetry = telemetry, consentEnabled = false)

        DiagnosticsTelemetryProvider.instance?.recordGesture(
            DiagnosticsGestureKind.SWIPE,
            DiagnosticsOutcome.ACCEPTED,
        )
        DiagnosticsTelemetryProvider.instance?.flush()

        assertTrue(queue.isEmpty())
        assertFalse(scheduled)
        assertTrue(DiagnosticsTelemetryProvider.instance?.breadcrumbs().orEmpty().isEmpty())
    }

    @Test
    fun `flush trigger persists at session boundary, not per individual gesture`() {
        val queue = mutableListOf<TelemetryEnvelope>()
        var scheduleCount = 0
        val telemetry = DiagnosticsTelemetry(
            diagnosticsEnabled = DiagnosticsTelemetryProvider::isEnabled,
            appendToQueue = { queue += it },
            scheduleUpload = { scheduleCount += 1 },
            envelopeFactory = ::sampleEnvelopeFor,
        )
        DiagnosticsTelemetryProvider.installForTest(telemetry = telemetry, consentEnabled = true)

        // Several gesture events in a row (as trackGesture/trackSuggestion now do) must only
        // batch in memory - no queue write or scheduling per event.
        repeat(5) {
            DiagnosticsTelemetryProvider.instance?.recordGesture(
                DiagnosticsGestureKind.SWIPE,
                DiagnosticsOutcome.ACCEPTED,
            )
        }
        assertTrue(queue.isEmpty())
        assertEquals(0, scheduleCount)

        // The session-boundary flush (what onFinishInputView calls) is what actually persists.
        DiagnosticsTelemetryProvider.instance?.flush()

        assertEquals(5, queue.size)
        assertEquals(1, scheduleCount)
    }

    @Test
    fun `in-memory backlog force-flushes once the pending-event cap is reached`() {
        val queue = mutableListOf<TelemetryEnvelope>()
        var scheduleCount = 0
        val telemetry = DiagnosticsTelemetry(
            diagnosticsEnabled = DiagnosticsTelemetryProvider::isEnabled,
            appendToQueue = { queue += it },
            scheduleUpload = { scheduleCount += 1 },
            envelopeFactory = ::sampleEnvelopeFor,
            maxPendingEvents = 3,
        )
        DiagnosticsTelemetryProvider.installForTest(telemetry = telemetry, consentEnabled = true)

        repeat(3) {
            DiagnosticsTelemetryProvider.instance?.recordGesture(
                DiagnosticsGestureKind.SWIPE,
                DiagnosticsOutcome.ACCEPTED,
            )
        }

        // Never flushed explicitly, but the cap forced a drain rather than growing unbounded.
        assertEquals(3, queue.size)
        assertEquals(1, scheduleCount)
    }

    @Test
    fun `consent flag reacts to preference updates without touching a real Context`() {
        assertFalse(DiagnosticsTelemetryProvider.isEnabled())

        val preferences = emptyPreferences().toMutablePreferences().apply {
            this[diagnosticsConsentKey] = true
        }
        val job = DiagnosticsTelemetryProvider.collectConsent(
            preferences = flowOf(preferences),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        runBlocking { job.join() }

        assertTrue(DiagnosticsTelemetryProvider.isEnabled())
    }

    @Test
    fun `crash-handler wiring reads the same isEnabled and breadcrumbs the provider exposes`() {
        val telemetry = DiagnosticsTelemetry(
            diagnosticsEnabled = DiagnosticsTelemetryProvider::isEnabled,
            appendToQueue = {},
            scheduleUpload = {},
            envelopeFactory = ::sampleEnvelopeFor,
        )
        DiagnosticsTelemetryProvider.installForTest(telemetry = telemetry, consentEnabled = true)
        DiagnosticsTelemetryProvider.instance?.recordAppStart()

        // Mirrors IaidoApplication.installTelemetryCrashHandler's lambdas exactly.
        val enabled: () -> Boolean = DiagnosticsTelemetryProvider::isEnabled
        val breadcrumbs: () -> List<DiagnosticsBreadcrumb> =
            { DiagnosticsTelemetryProvider.instance?.breadcrumbs() ?: emptyList() }

        assertTrue(enabled())
        assertEquals(
            listOf(DiagnosticsBreadcrumbKind.APP_START),
            breadcrumbs().map(DiagnosticsBreadcrumb::kind),
        )

        DiagnosticsTelemetryProvider.installForTest(telemetry = null, consentEnabled = false)

        assertFalse(enabled())
        assertTrue(breadcrumbs().isEmpty())
    }

    private fun sampleEnvelopeFor(event: DiagnosticsEvent): TelemetryEnvelope {
        val payload = Json.parseToJsonElement(DiagnosticsEventCodec.encode(event)) as JsonObject
        return TelemetryEnvelope(
            schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = "00000000-0000-0000-0000-000000000011",
            batchId = "00000000-0000-0000-0000-000000000012",
            installationId = "00000000-0000-0000-0000-000000000013",
            sessionId = "00000000-0000-0000-0000-000000000014",
            occurredAtMs = 1_000L,
            appVersion = "0.1.3",
            buildType = "debug",
            androidApi = 36,
            eventType = "gesture_outcome",
            payload = payload,
        )
    }
}
