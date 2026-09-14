package com.ninjakeys.app

import android.view.KeyEvent
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

class ImeEditorDriver(
    private val device: UiDevice,
    private val pointerInjector: PointerInjector,
    private val packageName: String,
) {
    fun focus() {
        editor().click()
        check(device.wait(Until.hasObject(editorSelector()), ImeSystemController.DEFAULT_TIMEOUT_MS)) {
            "IME test editor lost focus or host disappeared"
        }
    }

    fun clear() {
        markedView("ime_test_clear").click()
        waitForText("")
    }

    fun text(): String {
        if (length() == 0) return ""
        return editor().text.orEmpty()
    }

    private fun length(): Int {
        val status = markedView("ime_test_status").text.orEmpty()
        val match = LENGTH_REGEX.find(status)
            ?: error("Host status did not expose length: '$status'")
        return match.groupValues[1].toInt()
    }

    fun selection(): IntRange {
        val status = markedView("ime_test_status").contentDescription.orEmpty() + " " +
            markedView("ime_test_status").text.orEmpty()
        val match = SELECTION_REGEX.find(status)
            ?: error("Host status did not expose selection: '$status'")
        val start = match.groupValues[1].toInt()
        val end = match.groupValues[2].toInt()
        return start..end
    }

    fun pressBackspace(count: Int = 1) {
        require(count >= 0) { "Backspace count cannot be negative" }
        repeat(count) { pressKey(KeyEvent.KEYCODE_DEL) }
    }

    fun pressKey(keyCode: Int) {
        check(device.pressKeyCode(keyCode)) { "UiDevice rejected key code $keyCode" }
    }

    fun tapMarkedKey(description: String): List<InjectedPointerEvent> {
        val key = markedView(description)
        val bounds = key.visibleBounds
        return pointerInjector.injectTap(
            centerX = ((bounds.left + bounds.right) / 2f),
            centerY = ((bounds.top + bounds.bottom) / 2f),
        )
    }

    fun waitForText(expected: String, timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (text() == expected) return
            device.waitForIdle()
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(false) {
            "Expected editor text '$expected', observed '${text()}'"
        }
    }

    private fun editor(): UiObject2 = markedView("ime_test_editor")

    private fun markedView(id: String): UiObject2 = device.wait(
        Until.findObject(By.res(resourceId(id))),
        ImeSystemController.DEFAULT_TIMEOUT_MS,
    ) ?: error("Missing host view $id")

    private fun editorSelector() = By.res(resourceId("ime_test_editor"))

    private fun resourceId(id: String) = "$packageName:id/$id"

    private companion object {
        val LENGTH_REGEX = Regex("length=(\\d+)")
        val SELECTION_REGEX = Regex("selection=(\\d+):(\\d+)")
    }
}
