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
            assertTrue(device.wait(Until.hasObject(By.text(label)), 5_000L))
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var selected = false
            while (SystemClock.elapsedRealtime() < deadline && !selected) {
                var node = device.findObject(By.text(label))
                repeat(4) {
                    if (node?.isCheckable == true) return@repeat
                    node = node?.parent
                }
                selected = node?.isChecked == true
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
            assertTrue(device.hasObject(By.text("Download the newest signed Iaido APK from GitHub Releases.")))
        } finally {
            activity.finish()
        }
    }
}
