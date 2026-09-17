package com.iaido.app

import android.view.KeyEvent
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

class ImeEditorDriver(
    private val device: UiDevice,
    private val pointerInjector: PointerInjector,
    private val packageName: String,
) {
    fun focus() {
        val bounds = editor().visibleBounds
        pointerInjector.injectTap(
            centerX = (bounds.left + bounds.right) / 2f,
            centerY = (bounds.top + bounds.bottom) / 2f,
        )
    }

    fun clear() {
        clickMarked("ime_test_clear")
        waitForText("")
    }

    fun text(): String {
        if (length() == 0) return ""
        return readMarked("ime_test_editor") { it.text.orEmpty() }
    }

    private fun length(): Int {
        val status = readMarked("ime_test_status") { it.text.orEmpty() }
        val match = LENGTH_REGEX.find(status)
            ?: error("Host status did not expose length: '$status'")
        return match.groupValues[1].toInt()
    }

    fun selection(): IntRange {
        val status = readMarked("ime_test_status") {
            it.contentDescription.orEmpty() + " " + it.text.orEmpty()
        }
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

    fun moveCursorLeft() {
        clickMarked("ime_test_move_cursor_left")
    }

    fun pressKey(keyCode: Int) {
        check(device.pressKeyCode(keyCode)) { "UiDevice rejected key code $keyCode" }
    }

    fun tapMarkedKey(description: String): List<InjectedPointerEvent> {
        val key = findView(By.desc(description), "keyboard key $description")
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
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(false) {
            "Expected editor text '$expected', observed '${text()}'"
        }
    }

    fun waitForTextChange(previous: String, timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val current = text()
            if (current != previous) return current
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Editor text did not change from '$previous'")
    }

    private fun editor(): UiObject2 = markedView("ime_test_editor")

    private fun markedView(id: String): UiObject2 = findView(By.res(resourceId(id)), "host view $id")

    private fun clickMarked(id: String) {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                val bounds = markedView(id).visibleBounds
                pointerInjector.injectTap(
                    centerX = (bounds.left + bounds.right) / 2f,
                    centerY = (bounds.top + bounds.bottom) / 2f,
                )
                return
            } catch (failure: StaleObjectException) {
                lastFailure = failure
                SystemClock.sleep(50L)
            }
        }
        throw IllegalStateException("Could not click host view $id", lastFailure)
    }

    private fun <T> readMarked(id: String, read: (UiObject2) -> T): T {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return read(markedView(id))
            } catch (failure: StaleObjectException) {
                lastFailure = failure
                SystemClock.sleep(50L)
            }
        }
        throw IllegalStateException("Could not read host view $id", lastFailure)
    }

    private fun findView(selector: BySelector, description: String): UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObject(selector)?.let { return it }
            SystemClock.sleep(50L)
        }
        error("Missing $description")
    }

    private fun resourceId(id: String) = "$packageName:id/$id"

    private companion object {
        val LENGTH_REGEX = Regex("length=(\\d+)")
        val SELECTION_REGEX = Regex("selection=(\\d+):(\\d+)")
    }
}
