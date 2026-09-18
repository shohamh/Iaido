package com.iaido.app

import android.content.Intent
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class SettingsUpdateE2eTest {
    @Test
    fun missingSpacingModeDefaultsToInferSpacesInTheRealSettingsScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runBlocking {
            instrumentation.targetContext.settingsStore.edit { preferences ->
                preferences.remove(spacingModeKey)
            }
        }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val device = UiDevice.getInstance(instrumentation)
            val label = "Infer spaces"
            // The spacing-mode options render below the initial fold of the Settings screen's
            // scrollable Column (headline + live preview + Setup/App updates/Gestures sections
            // above them), so the scrollable container must be scrolled to bring the option into
            // view before UiAutomator's accessibility-snapshot lookups (By.text/findObject) can
            // find it. Retry (rescrolling each attempt) rather than scrolling once, since a
            // single immediate lookup right after opening Settings can race the initial Compose
            // layout pass.
            var appeared = false
            val appearDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (SystemClock.elapsedRealtime() < appearDeadline && !appeared) {
                scrollToText(device, label)
                appeared = device.hasObject(By.text(label))
                if (!appeared) SystemClock.sleep(50L)
            }
            assertTrue("Missing spacing mode '$label'", appeared)
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var selected = false
            while (SystemClock.elapsedRealtime() < deadline && !selected) {
                selected = try {
                    scrollToText(device, label)
                    var node = device.findObject(By.text(label))
                    repeat(4) {
                        if (node?.isCheckable == true) return@repeat
                        node = node?.parent
                    }
                    node?.isChecked == true
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    false
                }
                if (!selected) SystemClock.sleep(50L)
            }
            assertTrue("Missing spacing mode was not selected as Infer spaces", selected)
        } finally {
            activity.finish()
        }
    }

    @Test
    fun settingsExposeTheAppUpdateAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.text("Update app")), 5_000L))
            assertTrue(device.hasObject(By.text("Update channel")))
            assertTrue(device.hasObject(By.text("Stable release")))
            assertTrue(device.hasObject(By.text("Nightly")))
            assertTrue(device.hasObject(By.text("Download the newest signed stable release Iaido APK from GitHub Releases.")))
        } finally {
            activity.finish()
        }
    }

    /**
     * Scrolls the Settings screen's scrollable container toward [label] with a few small, bounded
     * swipes (checking for the label after each one), rather than one large fling — a target
     * roughly mid-list can otherwise be scrolled past in a single big swipe before UiAutomator
     * ever observes it in the accessibility snapshot. Best-effort: a no-op if [label] is already
     * visible or no scrollable container exists.
     */
    private fun scrollToText(device: UiDevice, label: String) {
        if (device.hasObject(By.text(label))) return
        repeat(8) {
            val scrollable = device.findObject(By.scrollable(true)) ?: return
            val bounds = runCatching { scrollable.visibleBounds }.getOrNull() ?: return
            val x = (bounds.left + bounds.right) / 2
            // Swipe from near the bottom of the scrollable area to near its top, i.e. scroll the
            // content *down* into view, since the spacing-mode options sit below the fold.
            val startY = bounds.top + (bounds.height() * 0.8f).toInt()
            val endY = bounds.top + (bounds.height() * 0.2f).toInt()
            device.swipe(x, startY, x, endY, 20)
            device.waitForIdle()
            SystemClock.sleep(150L)
            if (device.hasObject(By.text(label))) return
        }
    }
}
