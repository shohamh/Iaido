package com.iaido.app

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connected coverage for the real [SettingsActivity]: enables diagnostics through the actual
 * Settings screen, confirms research stays off, disables diagnostics, and asserts pending
 * diagnostics data is deleted - all without changing unrelated keyboard settings or requiring
 * any IME/editor text (the telemetry section never touches editor content).
 */
@RunWith(AndroidJUnit4::class)
class TelemetryConsentE2eTest {
    @Test
    fun enablingDiagnosticsLeavesResearchOffAndDisablingDeletesPendingDataWithoutChangingOtherSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        runBlocking {
            context.settingsStore.edit { preferences ->
                preferences.remove(diagnosticsConsentKey)
                preferences.remove(diagnosticsConsentVersionKey)
                preferences.remove(diagnosticsAcceptedAtMsKey)
                preferences.remove(diagnosticsRevokedAtMsKey)
                preferences.remove(diagnosticsPolicyDigestKey)
                preferences.remove(researchConsentKey)
                preferences.remove(researchConsentVersionKey)
                preferences.remove(researchAcceptedAtMsKey)
                preferences.remove(researchRevokedAtMsKey)
                preferences.remove(researchPolicyDigestKey)
            }
        }

        val diagnosticsQueue = TelemetryQueue(
            directory = File(context.filesDir, "telemetry"),
            plane = TelemetryPlane.DIAGNOSTICS,
            clock = System::currentTimeMillis,
            limits = QueueLimits(),
        )
        diagnosticsQueue.deleteAll()
        diagnosticsQueue.append(sampleDiagnosticsEnvelope())
        assertTrue(
            "Expected a pending diagnostics batch before enabling diagnostics telemetry",
            diagnosticsQueue.pendingBatches().isNotEmpty(),
        )

        val originalSpacingMode = runBlocking {
            context.settingsStore.data.first()[spacingModeKey]
        }
        val originalCascadeDepth = runBlocking {
            context.settingsStore.data.first()[cascadeDepthKey]
        }

        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val device = UiDevice.getInstance(instrumentation)
            val diagnosticsLabel = "Share anonymous diagnostics"
            val researchLabel = "Contribute typing research data"

            scrollToText(device, diagnosticsLabel)
            assertTrue(
                "Missing the diagnostics toggle label",
                device.wait(Until.hasObject(By.text(diagnosticsLabel)), 5_000L),
            )

            val diagnosticsSwitch = waitForSwitchNear(device, diagnosticsLabel)
            assertFalse("Diagnostics should start disabled", diagnosticsSwitch.isChecked)
            diagnosticsSwitch.click()

            waitUntil(5_000L) { waitForSwitchNear(device, diagnosticsLabel).isChecked }
            assertTrue(
                "Diagnostics switch did not turn on after tapping it",
                waitForSwitchNear(device, diagnosticsLabel).isChecked,
            )

            scrollToText(device, researchLabel)
            assertFalse(
                "Enabling diagnostics must never enable research consent",
                waitForSwitchNear(device, researchLabel).isChecked,
            )

            scrollToText(device, diagnosticsLabel)
            waitForSwitchNear(device, diagnosticsLabel).click()

            waitUntil(5_000L) { !waitForSwitchNear(device, diagnosticsLabel).isChecked }
            assertFalse(
                "Diagnostics switch did not turn off after tapping it again",
                waitForSwitchNear(device, diagnosticsLabel).isChecked,
            )

            val pendingCleared = waitUntil(5_000L) { diagnosticsQueue.pendingBatches().isEmpty() }
            assertTrue("Pending diagnostics data was not deleted after disabling diagnostics", pendingCleared)

            assertEquals(
                "Disabling diagnostics must not change unrelated keyboard settings",
                originalSpacingMode,
                runBlocking { context.settingsStore.data.first()[spacingModeKey] },
            )
            assertEquals(
                "Disabling diagnostics must not change unrelated keyboard settings",
                originalCascadeDepth,
                runBlocking { context.settingsStore.data.first()[cascadeDepthKey] },
            )

            val researchStillOff = runBlocking {
                researchConsentFromPreferences(context.settingsStore.data.first()).enabled
            }
            assertFalse("Research consent must remain untouched by this diagnostics-only flow", researchStillOff)
        } finally {
            activity.finish()
            diagnosticsQueue.deleteAll()
        }
    }

    private fun sampleDiagnosticsEnvelope(): TelemetryEnvelope {
        val payload = Json.parseToJsonElement(
            DiagnosticsEventCodec.encode(DiagnosticsEvent.GestureOutcome(DiagnosticsOutcome.ACCEPTED)),
        ) as JsonObject
        return TelemetryEnvelope(
            schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = "e2e-consent-event",
            batchId = "e2e-consent-batch",
            installationId = "e2e-consent-installation",
            sessionId = "e2e-consent-session",
            occurredAtMs = System.currentTimeMillis(),
            appVersion = "e2e-test",
            buildType = "debug",
            androidApi = Build.VERSION.SDK_INT,
            eventType = "gesture_outcome",
            payload = payload,
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var satisfied = condition()
        while (SystemClock.elapsedRealtime() < deadline && !satisfied) {
            SystemClock.sleep(50L)
            satisfied = condition()
        }
        return satisfied
    }

    /**
     * Finds the Switch nearest the given label, walking up from the text node to its row and then
     * searching that row for the Switch widget - mirrors how [TelemetryPlaneToggle] lays out a
     * title/disclosure column next to a Switch in the same Row.
     */
    private fun waitForSwitchNear(device: UiDevice, label: String): UiObject2 {
        var node = device.findObject(By.text(label))
        var row = node
        repeat(6) {
            if (row?.className == "android.widget.Switch") return@repeat
            val candidate = row?.findObject(By.clazz("android.widget.Switch"))
            if (candidate != null) {
                row = candidate
                return@repeat
            }
            row = row?.parent
        }
        return requireNotNull(row) { "Could not find a Switch near '$label'" }
    }

    /**
     * Scrolls the Settings screen's scrollable container toward [label] with a few small, bounded
     * swipes, checking for the label after each one, since a target roughly mid-list can be
     * scrolled past in one large fling before UiAutomator observes it in the accessibility
     * snapshot. Mirrors [SettingsUpdateE2eTest]'s helper of the same shape.
     */
    private fun scrollToText(device: UiDevice, label: String) {
        if (device.hasObject(By.text(label))) return
        repeat(8) {
            val scrollable = device.findObject(By.scrollable(true)) ?: return
            val bounds = runCatching { scrollable.visibleBounds }.getOrNull() ?: return
            val x = (bounds.left + bounds.right) / 2
            val startY = bounds.top + (bounds.height() * 0.8f).toInt()
            val endY = bounds.top + (bounds.height() * 0.2f).toInt()
            device.swipe(x, startY, x, endY, 20)
            device.waitForIdle()
            SystemClock.sleep(150L)
            if (device.hasObject(By.text(label))) return
        }
    }
}
