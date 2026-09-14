package com.ninjakeys.app

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class ImeSystemController(
    private val instrumentation: Instrumentation,
    private val device: UiDevice,
    private val automation: UiAutomation,
) {
    private val packageName = instrumentation.targetContext.packageName

    val ninjaKeysImeId: String = ComponentName(packageName, "$packageName.NinjaKeysInputMethodService").flattenToShortString()
    val referenceImeId: String = ComponentName(packageName, "$packageName.ReferenceInputMethodService").flattenToShortString()

    fun launchHost() {
        val component = ComponentName(packageName, "$packageName.ImeTestHostActivity").flattenToShortString()
        shell("am start -n $component -f 0x14000000")
        check(device.wait(Until.hasObject(By.res("$packageName:id/ime_test_editor")), DEFAULT_TIMEOUT_MS)) {
            "IME test host did not launch; focused=${device.currentPackageName}"
        }
    }

    fun enableAndSelect(imeId: String) {
        shell("ime enable $imeId")
        shell("ime set $imeId")
        check(selectedInputMethodId() == imeId) {
            "IME '$imeId' was not selected; default=${selectedInputMethodId()}"
        }
    }

    fun waitForImeVisible(imeId: String) {
        check(device.wait(Until.hasObject(markerFor(imeId)), DEFAULT_TIMEOUT_MS)) {
            "IME '$imeId' did not become visible. default=${selectedInputMethodId()}\n" +
                "input_method=${shell("dumpsys input_method") }"
        }
    }

    fun selectedInputMethodId(): String = shell("settings get secure ${Settings.Secure.DEFAULT_INPUT_METHOD}")
        .lineSequence()
        .lastOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

    fun hideKeyboard() {
        device.pressBack()
    }

    private fun markerFor(imeId: String) = if (imeId == referenceImeId) {
        By.desc("NinjaKeys reference keyboard")
    } else {
        By.desc(KeyboardWindowLocator.ROOT_DESCRIPTION)
    }

    private fun shell(command: String): String {
        val descriptor = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
