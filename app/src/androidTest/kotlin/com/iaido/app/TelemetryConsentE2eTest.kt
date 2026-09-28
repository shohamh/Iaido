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
                    "Diagnostics should start disabled (${stateDump(device, diagnosticsLabel)})",
                    awaitToggle(device, diagnosticsLabel)?.isChecked ?: false,
                )
                val enabled = runCatching { toggleUntil(device, diagnosticsLabel, expected = true) }
                if (enabled.isFailure) {
                    val stored = runBlocking {
                        diagnosticsConsentFromPreferences(context.settingsStore.data.first())
                    }
                    throw AssertionError(
                        "Diagnostics toggle never enabled; stored=$stored " +
                            stateDump(device, diagnosticsLabel) + " " + stateDump(device, researchLabel),
                        enabled.exceptionOrNull(),
                    )
                }
                assertTrue(
                    "Expected an enabled status message after provisioning the installation",
                    device.wait(
                        Until.hasObject(By.textContains("telemetry enabled")),
                        10_000L,
                    ),
                )
            } else {
                assertTrue(
                    "Seeded diagnostics consent should render as enabled (${stateDump(device, diagnosticsLabel)})",
                    waitUntil(10_000L) { awaitToggle(device, diagnosticsLabel)?.isChecked == true },
                )
            }

            scrollToText(device, researchLabel)
            assertFalse(
                "Enabling diagnostics must never enable research consent (${stateDump(device, researchLabel)})",
                awaitToggle(device, researchLabel)?.isChecked ?: false,
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
                    awaitToggle(device, diagnosticsLabel)?.let { clickToggleCenter(device, diagnosticsLabel) }
                    failureShown = waitUntil(3_000L) {
                        device.hasObject(By.textContains("Could not set up diagnostics telemetry"))
                    }
                }
                assertTrue(
                    "A build with no collector must surface the provisioning failure instead of enabling",
                    failureShown,
                )
                assertFalse(
                    "Diagnostics consent must never be shown as enabled without a collector " +
                        "(${stateDump(device, diagnosticsLabel)})",
                    awaitToggle(device, diagnosticsLabel)?.isChecked ?: false,
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
     * recomposition), a row that is momentarily laid out without its toggle, or a toggle disabled
     * because a previous consent change is still running would otherwise be read as "the tap did
     * not work" - so each attempt re-locates the row, waits for the toggle to be enabled, and
     * re-checks the observable state instead of assuming one tap landed.
     */
    private fun toggleUntil(device: UiDevice, label: String, expected: Boolean) {
        repeat(6) {
            val toggle = awaitToggle(device, label) ?: return@repeat
            if (toggle.isChecked == expected) return
            if (!toggle.isEnabled) waitUntil(5_000L) { awaitToggle(device, label)?.isEnabled == true }
            clickToggleCenter(device, label)
            assertNoResearchDialog(device)
            // Enabling a plane can involve a provisioning round trip to the collector, so give the
            // expected state a long window before deciding the tap was swallowed - clicking again
            // while a consent change is still in flight would revoke it and leave the test racing
            // the app instead of asserting it.
            if (waitUntil(20_000L) { awaitToggle(device, label)?.isChecked == expected }) return
            waitUntil(15_000L) { awaitToggle(device, label)?.isEnabled == true }
        }
        val finalToggle = awaitToggle(device, label)
        if (finalToggle?.isChecked != expected) {
            ArtifactWriter.captureScreenshot(
                "telemetry-toggle-failure-expected-$expected",
                device,
            )
        }
        assertTrue(
            "Toggle '$label' never reached checked=$expected (${stateDump(device, label)})",
            finalToggle?.isChecked == expected,
        )
    }

    /**
     * Locates the toggle for [label], scrolling and nudging the list until the row's own toggle is
     * laid out. Returns null only after the budget is spent - callers assert on the state rather
     * than on one particular moment of a settling list.
     */
    private fun awaitToggle(device: UiDevice, label: String, timeoutMs: Long = 8_000L): UiObject2? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var nudges = 0
        while (true) {
            scrollToText(device, label)
            findToggle(device, label)?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline || nudges >= 4) return null
            nudgeList(device)
            nudges += 1
        }
    }

    /**
     * Finds the toggle on the same row as the given label, or null when the label or its toggle is
     * not laid out. Compose's `Switch` does not report itself as `android.widget.Switch`, so this
     * matches the checkable semantics the toggle exposes and requires a vertical overlap with the
     * label's own row. There is no fallback to "nearest" toggle: the research toggle sits one row
     * away, and clicking it opens a confirmation dialog instead of changing diagnostics consent.
     */
    private fun findToggle(device: UiDevice, label: String): UiObject2? {
        val labelNode = device.wait(Until.findObject(By.text(label)), 2_000L) ?: return null
        val labelBounds = labelNode.visibleBounds
        val viewport = scrollViewport(device) ?: return null
        val safeTop = viewport.top + (viewport.height() * 0.18f).toInt()
        val safeBottom = viewport.bottom - (viewport.height() * 0.18f).toInt()
        if (labelBounds.isEmpty || labelBounds.centerY() !in safeTop..safeBottom) return null
        val labelCenterY = labelNode.visibleCenter.y
        return device.findObjects(By.checkable(true))
            .filter {
                val bounds = it.visibleBounds
                !bounds.isEmpty &&
                    bounds.top >= viewport.top &&
                    bounds.bottom <= viewport.bottom &&
                    bounds.top < labelBounds.bottom &&
                    bounds.bottom > labelBounds.top
            }
            .minByOrNull { abs(it.visibleCenter.y - labelCenterY) }
    }

    /** Moves the list by a fraction of its height, to bring a row's toggle out of a clipped edge. */
    private fun nudgeList(device: UiDevice) {
        val area = scrollViewport(device) ?: return
        val x = (area.left + area.right) / 2
        val start = area.top + (area.height() * 0.55f).toInt()
        device.swipe(x, start, x, start - (area.height() * 0.12f).toInt(), 20)
        device.waitForIdle()
    }

    /** Diagnostic snapshot for a failing assertion: label bounds, every checkable, and screen size. */
    private fun stateDump(device: UiDevice, label: String): String {
        val labelBounds = device.findObject(By.text(label))?.visibleBounds?.toShortString()
        val toggles = device.findObjects(By.checkable(true)).map {
            "${it.visibleBounds.toShortString()} checked=${it.isChecked} enabled=${it.isEnabled}"
        }
        return "label=$label/$labelBounds display=${device.displayWidth}x${device.displayHeight} toggles=$toggles"
    }

    /** Taps fresh toggle bounds only after Compose and scroll motion have settled. */
    private fun clickToggleCenter(device: UiDevice, label: String) {
        device.waitForIdle()
        val toggle = requireNotNull(awaitToggle(device, label)) { "Toggle '$label' disappeared before tap" }
        check(toggle.isEnabled) { "Toggle '$label' stayed disabled before tap (${stateDump(device, label)})" }
        val bounds = toggle.visibleBounds
        check(!bounds.isEmpty) { "Toggle '$label' has no visible tap bounds" }
        val viewport = requireNotNull(scrollViewport(device)) { "Settings scroll viewport disappeared before tap" }
        check(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom) {
            "Toggle '$label' is clipped by the Settings scroll viewport: toggle=$bounds viewport=$viewport"
        }
        device.click(bounds.centerX(), bounds.centerY())
        device.waitForIdle()
    }

    /**
     * Fails if the research confirmation dialog is open, which means a previous tap landed on the
     * research row instead of the diagnostics toggle.
     */
    private fun assertNoResearchDialog(device: UiDevice) {
        assertFalse(
            "Tapping the diagnostics row opened the research confirmation dialog",
            device.hasObject(By.text("Contribute typing research data?")),
        )
    }

    /**
     * Positions [label] inside the safe middle of the scroll viewport. The complete Compose
     * Column remains in the accessibility tree while offscreen, so `hasObject(By.text(label))`
     * alone cannot be used as evidence that the row is tappable.
     */
    private fun scrollToText(device: UiDevice, label: String) {
        repeat(12) {
            val viewport = scrollViewport(device) ?: return
            val safeTop = viewport.top + (viewport.height() * 0.18f).toInt()
            val safeBottom = viewport.bottom - (viewport.height() * 0.18f).toInt()
            val labelBounds = device.findObject(By.text(label))?.visibleBounds
            if (labelBounds != null && !labelBounds.isEmpty && labelBounds.centerY() in safeTop..safeBottom) return

            val x = (viewport.left + viewport.right) / 2
            val swipeDistance = (viewport.height() * 0.2f).toInt()
            val targetIsAboveViewport =
                labelBounds != null && !labelBounds.isEmpty && labelBounds.centerY() < safeTop
            val startY: Int
            val endY: Int
            if (targetIsAboveViewport) {
                // Move earlier content down into view.
                startY = viewport.top + (viewport.height() * 0.38f).toInt()
                endY = startY + swipeDistance
            } else {
                // Move later content up into view; an empty bounds result means the row is still below.
                startY = viewport.bottom - (viewport.height() * 0.38f).toInt()
                endY = startY - swipeDistance
            }
            device.swipe(x, startY, x, endY, 80)
            device.waitForIdle()
            SystemClock.sleep(150L)
        }
    }

    private fun scrollViewport(device: UiDevice) =
        runCatching { device.findObject(By.scrollable(true))?.visibleBounds }.getOrNull()
}
