package com.ninjakeys.app

import android.view.KeyEvent
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

    fun text(): String = editor().text.orEmpty()

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
        check(device.wait(Until.hasObject(editorSelector().text(expected)), timeoutMs)) {
            "Expected editor text '$expected', observed '${text()}'"
        }
    }

    private fun editor(): UiObject2 = markedView("ime_test_editor")

    private fun markedView(id: String): UiObject2 = device.findObject(By.res(resourceId(id)))
        ?: error("Missing host view $id")

    private fun editorSelector() = By.res(resourceId("ime_test_editor"))

    private fun resourceId(id: String) = "$packageName:id/$id"

    private companion object {
        val SELECTION_REGEX = Regex("selection=(\\d+):(\\d+)")
    }
}
