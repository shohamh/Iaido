package com.iaido.app

import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.os.SystemClock
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsImeReelE2eTest {
    @Test
    fun settingsPreviewUsesTheRealImeReelAndCapturesItsBoundedHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val system = ImeSystemController(instrumentation, device, instrumentation.uiAutomation)
        system.setAutoSpaceFixture(ImeScenarioData.AutoSpaceFixture.TYPED_REEL.preferenceValue)
        system.enableAndSelect(system.iaidoImeId)
        instrumentation.uiAutomation.executeShellCommand(
            "am start -n ${instrumentation.targetContext.packageName}/.SettingsActivity",
        ).close()
        assertTrue(device.wait(Until.hasObject(By.text("Iaido Settings")), 5_000L))
        val preview = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000L)
        assertTrue("Live preview field was not found", preview != null)
        preview.click()
        device.click(500, 420)
        SystemClock.sleep(1_000L)

        val pointer = PointerInjector(instrumentation.uiAutomation)
        tapLetter(pointer, device, 'h')
        assertTrue(
            "Settings preview did not receive the first typed letter",
            device.wait(Until.hasObject(By.textContains("H")), 5_000L),
        )
        tapLetter(pointer, device, 'i')
        assertTrue(
            "Typing the second letter did not update the Settings preview",
            device.wait(Until.hasObject(By.textContains("Hi")), 5_000L),
        )
        val hiScreenshot = ArtifactWriter.captureScreenshot("settings-ime-typed-hi-reel", device)
        instrumentation.uiAutomation
            .executeShellCommand(
                "cp ${hiScreenshot.absolutePath} /sdcard/Download/settings-ime-typed-hi-reel.png",
            )
            .close()

        tapLetter(pointer, device, 'm')
        assertTrue(
            "Typing the third letter did not update the Settings preview",
            device.wait(Until.hasObject(By.textContains("Him")), 5_000L),
        )
        val screenshot = ArtifactWriter.captureScreenshot("settings-ime-reel-per-height", device)
        instrumentation.uiAutomation
            .executeShellCommand(
                "cp ${screenshot.absolutePath} /sdcard/Download/settings-ime-reel-per-height.png",
            )
            .close()
    }

    private fun tapLetter(pointer: PointerInjector, device: UiDevice, letter: Char) {
        val window = KeyboardWindowLocator.locate(device)
        val rows = listOf("qwertyuiop", "asdfghjkl", "'?zxcvbnm,.")
        val row = rows.indexOfFirst { letter in it }
        check(row >= 0) { "No English letter key for '$letter'" }
        val rowText = rows[row]
        val keySizePx = window.surfaceBounds.width().toFloat() / 11f
        val x = (rowText.indexOf(letter) + 0.5f + (11 - rowText.length) / 2f) * keySizePx
        val y = (row + 0.5f) * keySizePx
        pointer.injectTap(window.surfaceBounds.left + x, window.surfaceBounds.top + y)
        device.waitForIdle()
    }
}
