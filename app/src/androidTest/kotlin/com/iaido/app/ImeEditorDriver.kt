package com.iaido.app

import android.view.KeyEvent
import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2

data class ImeEditorSnapshot(
    val text: String,
    val selection: IntRange,
    val reportedLength: Int,
    val hostGeneration: Long?,
    val resetRequestId: Long?,
) {
    companion object {
        private val LENGTH_REGEX = Regex("length=(\\d+)")
        private val SELECTION_REGEX = Regex("selection=(\\d+):(\\d+)")
        private val GENERATION_REGEX = Regex("generation=(\\d+)")
        private val RESET_REGEX = Regex("reset=(-?\\d+)")

        fun fromStatus(status: String): ImeEditorSnapshot? {
            val length = LENGTH_REGEX.find(status)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val selectionMatch = SELECTION_REGEX.find(status) ?: return null
            val start = selectionMatch.groupValues[1].toIntOrNull() ?: return null
            val end = selectionMatch.groupValues[2].toIntOrNull() ?: return null
            return ImeEditorSnapshot(
                text = "",
                selection = start..end,
                reportedLength = length,
                hostGeneration = GENERATION_REGEX.find(status)?.groupValues?.get(1)?.toLongOrNull(),
                resetRequestId = RESET_REGEX.find(status)?.groupValues?.get(1)?.toLongOrNull(),
            )
        }
    }
}

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
        setMarkedText("ime_test_editor", "")
        if (snapshot().text.isEmpty()) return
        waitForText("")
    }

    fun snapshot(): ImeEditorSnapshot {
        repeat(SNAPSHOT_READ_ATTEMPTS) {
            val status = readMarked("ime_test_status") {
                it.contentDescription.orEmpty() + " " + it.text.orEmpty()
            }
            val parsed = ImeEditorSnapshot.fromStatus(status)
                ?: error("Host status did not expose length/selection: '$status'")
            val text = if (parsed.reportedLength == 0) {
                ""
            } else {
                readMarked("ime_test_editor") { it.text.orEmpty() }
            }
            if (text.length == parsed.reportedLength) return parsed.copy(text = text)
            SystemClock.sleep(SNAPSHOT_RETRY_DELAY_MS)
        }
        error("Host editor text and status length did not settle together")
    }

    fun text(): String = snapshot().text

    fun selection(): IntRange = snapshot().selection

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
        val key = findKeyboardKey(description)
        val bounds = key.visibleBounds
        return pointerInjector.injectTap(
            centerX = ((bounds.left + bounds.right) / 2f),
            centerY = ((bounds.top + bounds.bottom) / 2f),
        )
    }

    fun waitForText(expected: String, timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS): String {
        return waitForSnapshot(expected, timeoutMs).text
    }

    fun waitForSnapshot(expected: String, timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS): ImeEditorSnapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val current = snapshot()
            if (current.text == expected) return current
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        check(false) {
            "Expected editor text '$expected', observed '${snapshot().text}'"
        }
        error("Unreachable")
    }

    fun waitForTextChange(previous: String, timeoutMs: Long = ImeSystemController.DEFAULT_TIMEOUT_MS): String {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            val current = snapshot().text
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

    private fun setMarkedText(id: String, value: String) {
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        var lastFailure: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                markedView(id).text = value
                return
            } catch (failure: StaleObjectException) {
                lastFailure = failure
                SystemClock.sleep(50L)
            }
        }
        throw IllegalStateException("Could not set host view $id text", lastFailure)
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
        val availableKeys = device.findObjects(By.descStartsWith("Iaido key "))
            .mapNotNull { runCatching { it.contentDescription?.toString() }.getOrNull() }
        error("Missing $description; available keyboard keys=$availableKeys")
    }

    private fun findKeyboardKey(description: String): UiObject2 {
        if (!description.startsWith("Iaido key ")) {
            return findView(By.desc(description), "keyboard key $description")
        }
        val deadline = SystemClock.elapsedRealtime() + ImeSystemController.DEFAULT_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            device.findObjects(By.descStartsWith("Iaido key "))
                .firstOrNull { key ->
                    runCatching { key.contentDescription?.toString() == description }
                        .getOrDefault(false)
                }
                ?.let { return it }
            SystemClock.sleep(50L)
        }
        val availableKeys = device.findObjects(By.descStartsWith("Iaido key "))
            .mapNotNull { runCatching { it.contentDescription?.toString() }.getOrNull() }
        error("Missing keyboard key $description; available keyboard keys=$availableKeys")
    }

    private fun resourceId(id: String) = "$packageName:id/$id"

    private companion object {
        const val SNAPSHOT_READ_ATTEMPTS = 3
        const val SNAPSHOT_RETRY_DELAY_MS = 25L
    }
}
