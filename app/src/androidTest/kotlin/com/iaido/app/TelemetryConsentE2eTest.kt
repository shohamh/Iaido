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
import kotlin.math.abs
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

        // A build with no collector configured cannot provision installation credentials, so its
        // enable path must roll back to disabled. Seed the enabled record a working deployment
        // would have left behind so this test still drives the real disable path there.
        val endpointConfigured = BuildConfig.IAIDO_TELEMETRY_BASE_URL.isNotBlank()
        if (!endpointConfigured) {
            runBlocking {
                persistConsentRecord(context, TelemetryPlane.DIAGNOSTICS, enabledDiagnosticsRecord())
            }
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

            if (endpointConfigured) {
                assertFalse(
                    "Diagnostics should start disabled",
                    waitForSwitchNear(device, diagnosticsLabel).isChecked,
                )
                toggleUntil(device, diagnosticsLabel, expected = true)
                assertTrue(
                    "Expected an enabled status message after provisioning the installation",
                    device.wait(
                        Until.hasObject(By.textContains("diagnostics telemetry enabled")),
                        10_000L,
                    ),
                )
            } else {
                assertTrue(
                    "Seeded diagnostics consent should render as enabled",
                    waitUntil(10_000L) { waitForSwitchNear(device, diagnosticsLabel).isChecked },
                )
            }

            scrollToText(device, researchLabel)
            assertFalse(
                "Enabling diagnostics must never enable research consent",
                waitForSwitchNear(device, researchLabel).isChecked,
            )

            scrollToText(device, diagnosticsLabel)
            toggleUntil(device, diagnosticsLabel, expected = false)
            assertTrue(
                "Expected a disabled status message after revoking diagnostics",
                device.wait(
                    Until.hasObject(By.textContains("disabled and pending data deleted")),
                    10_000L,
                ),
            )

            val pendingCleared = waitUntil(10_000L) { diagnosticsQueue.pendingBatches().isEmpty() }
            assertTrue("Pending diagnostics data was not deleted after disabling diagnostics", pendingCleared)

            if (!endpointConfigured) {
                scrollToText(device, diagnosticsLabel)
                var failureShown = false
                repeat(5) {
                    if (failureShown) return@repeat
                    waitForSwitchNear(device, diagnosticsLabel).click()
                    failureShown = waitUntil(3_000L) {
                        device.hasObject(By.textContains("Could not set up diagnostics telemetry"))
                    }
                }
                assertTrue(
                    "A build with no collector must surface the provisioning failure instead of enabling",
                    failureShown,
                )
                assertFalse(
                    "Diagnostics consent must never be shown as enabled without a collector",
                    waitForSwitchNear(device, diagnosticsLabel).isChecked,
                )
                assertFalse(
                    "Diagnostics consent must not be persisted as enabled without a collector",
                    runBlocking {
                        diagnosticsConsentFromPreferences(context.settingsStore.data.first()).enabled
                    },
                )
                assertTrue(
                    "A failed enable must not create pending telemetry data",
                    diagnosticsQueue.pendingBatches().isEmpty(),
                )
            }

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

    private fun enabledDiagnosticsRecord() = ConsentRecord(
        enabled = true,
        consentVersion = TELEMETRY_CONSENT_POLICY_VERSION,
        acceptedAtMs = System.currentTimeMillis(),
        revokedAtMs = null,
        policyDigest = telemetryPolicyDigest(TelemetryPlane.DIAGNOSTICS),
    )

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
     * Clicks the toggle for [label] until its checked state equals [expected], retrying a bounded
     * number of times. A tap issued while the settings list is still settling (scroll momentum or
     * recomposition), or while the toggle is disabled because a previous consent change is still
     * running, is swallowed - so each attempt re-scrolls to the row, waits for the toggle to be
     * enabled, and re-checks the observable state instead of assuming one tap landed.
     */
    private fun toggleUntil(device: UiDevice, label: String, expected: Boolean) {
        repeat(8) {
            scrollToText(device, label)
            device.waitForIdle()
            val toggle = waitForSwitchNear(device, label)
            if (toggle.isChecked == expected) return
            if (!toggle.isEnabled) {
                waitUntil(5_000L) { waitForSwitchNear(device, label).isEnabled }
            }
            waitForSwitchNear(device, label).click()
            if (waitUntil(4_000L) { waitForSwitchNear(device, label).isChecked == expected }) return
        }
        assertEquals(
            "Toggle '$label' never reached checked=$expected",
            expected,
            waitForSwitchNear(device, label).isChecked,
        )
    }

    /**
     * Finds the toggle in the same row as the given label. Compose's `Switch` does not report
     * itself as `android.widget.Switch`, so this matches on the checkable semantics the toggle
     * exposes, prefers a toggle on the label's own row band, and only then falls back to the
     * nearest one - so a scroll position that clips the row cannot make it click a different
     * plane's toggle.
     */
    private fun waitForSwitchNear(device: UiDevice, label: String): UiObject2 {
        val labelNode = device.wait(Until.findObject(By.text(label)), 5_000L)
            ?: error("Could not find the '$label' label")
        val labelBounds = labelNode.visibleBounds
        val labelCenterY = labelNode.visibleCenter.y
        val toggles = device.findObjects(By.checkable(true))
        val sameRow = toggles.filter {
            abs(it.visibleCenter.y - labelCenterY) <= labelBounds.height()
        }
        val toggle = (sameRow.ifEmpty { toggles })
            .minByOrNull { abs(it.visibleCenter.y - labelCenterY) }
        return requireNotNull(toggle) { "Could not find a toggle near '$label'" }
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
