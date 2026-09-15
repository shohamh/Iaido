package com.iaido.app

import android.app.Instrumentation
import android.graphics.PointF
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.iaido.core.language.Language
import com.iaido.core.testing.SwipeFixtures

data class ImeScenarioEvent(
    val index: Int,
    val action: String,
    val expectedText: String,
    val observedText: String,
    val expectedSelection: Int,
    val observedSelection: IntRange,
    val expectedIme: String,
    val observedIme: String,
    val expectedLanguage: Language,
    val observedLanguage: Language,
    val gestureSeed: Long?,
    val pointerEvents: List<InjectedPointerEvent>,
)

data class ImeScenarioState(
    val expectedText: String,
    val expectedSelection: Int,
    val expectedIme: String,
    val expectedLanguage: Language,
    val trace: List<ImeScenarioEvent>,
)

data class PathTransform(
    val jitterSeed: Long = 0L,
    val jitterPx: Float = 0f,
    val stepMs: Long = 48L,
    val pauseAfterPoint: Int? = null,
    val pauseMs: Long = 0L,
    val reverseStart: Int? = null,
    val reverseEndExclusive: Int? = null,
    val cancelAfterPoint: Int? = null,
)

class ImeScenario(
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
) {
    private val device = UiDevice.getInstance(instrumentation)
    private val automation = instrumentation.uiAutomation
    private val system = ImeSystemController(instrumentation, device, automation)
    private val pointer = PointerInjector(automation)
    private val editor = ImeEditorDriver(device, pointer, instrumentation.targetContext.packageName)
    private val trace = mutableListOf<ImeScenarioEvent>()
    private var pendingGestureSeed: Long? = null
    private var pendingPointerEvents: List<InjectedPointerEvent> = emptyList()

    private var expectedText = ""
    private var expectedSelection = 0
    private var expectedLanguage = Language.ENGLISH
    private var expectedIme = system.iaidoImeId

    fun run(block: ImeScenario.() -> Unit) {
        setup()
        var succeeded = false
        try {
            block()
            succeeded = true
        } finally {
            if (succeeded) system.hideKeyboard()
        }
    }

    fun focusEditor() {
        editor.focus()
        checkpoint("focusEditor")
    }

    fun clearText() {
        editor.clear()
        expectedText = ""
        expectedSelection = 0
        checkpoint("clearText")
    }

    fun swipeWord(word: String) {
        swipePath(word)
    }

    fun swipePath(word: String, transform: PathTransform = PathTransform()) {
        require(word.isNotEmpty() && word.all(Char::isLetter)) { "Swipe word must contain letters: '$word'" }
        val window = keyboard()
        check(window.language == expectedLanguage) {
            "Scenario language drifted before '$word': expected=$expectedLanguage observed=${window.language}"
        }
        val keySizePx = window.surfaceBounds.width().toFloat() /
            if (expectedLanguage == Language.HEBREW) 11f else 10f
        val path = if (expectedLanguage == Language.ENGLISH) {
            SwipeFixtures.pathThroughQwerty(word, keySizePx)
        } else {
            SwipeFixtures.pathThroughHebrew(word, keySizePx)
        }
        val transformedPoints = path.points.toMutableList().apply {
            val start = transform.reverseStart
            val end = transform.reverseEndExclusive
            if (start != null || end != null) {
                require(start != null && end != null && start in indices && end in 1..size && start < end) {
                    "Invalid reverse segment ${transform.reverseStart}..${transform.reverseEndExclusive} for '$word'"
                }
                subList(start, end).reverse()
            }
        }
        val cancelled = transform.cancelAfterPoint != null
        val injectedPoints = transform.cancelAfterPoint?.let { index ->
            require(index in transformedPoints.indices) { "Invalid cancel point $index for '$word'" }
            transformedPoints.take(index + 1)
        } ?: transformedPoints
        val injected = pointer.injectSwipe(
            points = injectedPoints,
            surfaceBounds = window.surfaceBounds,
            stepMs = transform.stepMs,
            jitterSeed = transform.jitterSeed,
            jitterPx = transform.jitterPx,
            cancel = cancelled,
            pauseAfterPoint = transform.pauseAfterPoint,
            pauseMs = transform.pauseMs,
        )
        pendingPointerEvents = injected
        pendingGestureSeed = transform.jitterSeed
        if (cancelled) {
            checkpoint("cancelledSwipe($word)")
            return
        }
        val textBeforeCursor = expectedText.take(expectedSelection)
        val committed = if (textBeforeCursor.isEmpty() || textBeforeCursor.matches(Regex(".*[.!?]\\s*$"))) {
            word.replaceFirstChar { it.uppercase() }
        } else {
            word
        }
        insertExpected(committed)
        checkpoint("swipeWord($word)")
    }

    fun tapKey(key: String) {
        val logicalKey = key.lowercase()
        if (logicalKey == "globe") {
            val before = expectedLanguage
            pendingPointerEvents = editor.tapMarkedKey(keyDescription(logicalKey))
            expectedLanguage = if (before == Language.ENGLISH) Language.HEBREW else Language.ENGLISH
            checkpoint("switchLanguage")
            return
        }
        pendingPointerEvents = editor.tapMarkedKey(keyDescription(logicalKey))
        when (logicalKey) {
            "space" -> {
                insertExpected(" ")
            }
            "backspace" -> {
                deleteExpectedOne()
            }
            else -> {
                val textBeforeCursor = expectedText.take(expectedSelection)
                val value = if (logicalKey.length == 1 && logicalKey.first().isLetter() &&
                    (textBeforeCursor.isEmpty() || textBeforeCursor.last() in ".!?\n")
                ) logicalKey.uppercase() else logicalKey
                insertExpected(value)
            }
        }
        checkpoint("tapKey($key)")
    }

    fun tapSpace() = tapKey("space")

    fun switchLanguage() = tapKey("globe")

    fun twoFingerLanguageSwitch() {
        val window = keyboard()
        val center = window.keyCenter("space")
        val localX = (center.x - window.surfaceBounds.left).toFloat()
        val localY = (center.y - window.surfaceBounds.top).toFloat()
        val paths = listOf(
            listOf(
                com.iaido.core.gesture.GesturePoint(localX - 24f, localY, 0L),
                com.iaido.core.gesture.GesturePoint(localX + 80f, localY, 16L),
            ),
            listOf(
                com.iaido.core.gesture.GesturePoint(localX + 24f, localY, 0L),
                com.iaido.core.gesture.GesturePoint(localX + 128f, localY, 16L),
            ),
        )
        pendingPointerEvents = pointer.injectMultiPointer(paths, window.surfaceBounds)
        expectedLanguage = if (expectedLanguage == Language.ENGLISH) Language.HEBREW else Language.ENGLISH
        checkpoint("twoFingerLanguageSwitch")
    }

    fun pressBackspace(count: Int = 1) {
        require(count >= 0) { "Backspace count cannot be negative" }
        repeat(count) {
            editor.pressBackspace()
            deleteExpectedOne()
            checkpoint("pressBackspace")
        }
    }

    fun moveCursorLeft(count: Int = 1) {
        require(count >= 0) { "Cursor movement count cannot be negative" }
        repeat(count) {
            editor.moveCursorLeft()
            expectedSelection = (expectedSelection - 1).coerceAtLeast(0)
            checkpoint("moveCursorLeft")
        }
    }

    fun waitForCorrection(expected: String) {
        editor.waitForText(expected)
        expectedText = expected
        expectedSelection = editor.selection().last
        checkpoint("waitForCorrection")
    }

    fun tapSuggestion(index: Int, correctedText: String? = null) {
        require(index >= 0) { "Suggestion index cannot be negative" }
        val suggestion = device.findObject(By.desc("Iaido suggestion $index"))
            ?: error("Missing suggestion $index")
        val bounds = suggestion.visibleBounds
        pendingPointerEvents = pointer.injectScreenSwipe(
            listOf(
                PointF((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f),
                PointF((bounds.left + bounds.right) / 2f, bounds.top.toFloat() - 64f),
            ),
        )
        if (correctedText != null) {
            expectedText = correctedText
            expectedSelection = editor.selection().last
        } else {
            editor.waitForText(editor.text())
            expectedText = editor.text()
            expectedSelection = editor.selection().last
        }
        checkpoint("tapSuggestion($index)")
    }

    fun swipeSuggestion(index: Int, verticalDistancePx: Float): String {
        device.waitForIdle()
        SystemClock.sleep(1_000L)
        val suggestion = device.findObject(By.desc("Iaido suggestion $index"))
        val center = if (suggestion != null) {
            val bounds = suggestion.visibleBounds
            PointF(
                (bounds.left + bounds.right) / 2f,
                (bounds.top + bounds.bottom) / 2f,
            )
        } else {
            val root = device.findObject(By.desc(KeyboardWindowLocator.ROOT_DESCRIPTION))
                ?: error("Missing keyboard root while locating suggestion $index")
            val surface = device.findObject(By.desc(KeyboardWindowLocator.SURFACE_DESCRIPTION))
                ?: error("Missing keyboard surface while locating suggestion $index")
            val point = PointF(root.visibleBounds.left + 80f, root.visibleBounds.top + 60f)
            check(root.visibleBounds.contains(point.x.toInt(), point.y.toInt())) {
                "Correction reel fallback point is outside the keyboard root: point=$point root=${root.visibleBounds}"
            }
            check(point.y < surface.visibleBounds.top) {
                "Correction reel fallback point is not above the keyboard surface: point=$point surface=${surface.visibleBounds}"
            }
            point
        }
        fun injectReelSwipe() {
            pendingPointerEvents = pointer.injectScreenSwipe(
                points = (1..3).map { step ->
                    val fraction = step / 3f
                    PointF(center.x, center.y + verticalDistancePx * fraction)
                }.let { listOf(center) + it },
                holdBeforeMoveMs = 520L,
            )
        }
        val before = expectedText
        injectReelSwipe()
        var after = runCatching { editor.waitForTextChange(before, timeoutMs = 1_500L) }.getOrNull()
        if (after == null) {
            device.waitForIdle()
            SystemClock.sleep(500L)
            injectReelSwipe()
            after = editor.waitForTextChange(before)
        }
        expectedText = after
        device.waitForIdle()
        SystemClock.sleep(200L)
        expectedSelection = editor.selection().last
        checkpoint("swipeSuggestion($index, $verticalDistancePx)")
        return expectedText
    }

    fun holdBackspace(durationMs: Long = 1_000L): String {
        val before = editor.text()
        val center = keyboard().keyCenter("backspace")
        pendingPointerEvents = pointer.injectLongPress(center.x.toFloat(), center.y.toFloat(), durationMs)
        val after = editor.text()
        check(after.length < before.length) { "Holding backspace did not delete text: before='$before' after='$after'" }
        expectedText = after
        expectedSelection = editor.selection().last
        checkpoint("holdBackspace($durationMs)")
        return after
    }

    fun swipeBackspaceLeftThenRight(leftDistancePx: Float = 168f): List<String> {
        val before = editor.text()
        val center = keyboard().keyCenter("backspace")
        val snapshots = mutableListOf<String>()
        pendingPointerEvents = pointer.injectScreenSwipe(
            points = listOf(
                PointF(center.x.toFloat(), center.y.toFloat()),
                PointF(center.x - leftDistancePx, center.y.toFloat()),
                PointF(center.x - leftDistancePx / 2f, center.y.toFloat()),
                PointF(center.x.toFloat(), center.y.toFloat()),
            ),
            holdBeforeMoveMs = 520L,
            onEvent = { event ->
                if (event.action == android.view.MotionEvent.ACTION_MOVE) snapshots += editor.text()
            },
        )
        editor.waitForText(before)
        val after = editor.text()
        check(after == before) { "Backspace swipe did not restore the original text: before='$before' after='$after' snapshots=$snapshots" }
        check(snapshots.any { it.length < before.length }) { "Backspace swipe never changed the text live: $snapshots" }
        expectedText = after
        expectedSelection = editor.selection().last
        checkpoint("swipeBackspaceLeftThenRight")
        return snapshots
    }

    fun swipeBackspaceVertical(distancePx: Float) {
        val center = keyboard().keyCenter("backspace")
        pendingPointerEvents = pointer.injectScreenSwipe(
            points = listOf(
                PointF(center.x.toFloat(), center.y.toFloat()),
                PointF(center.x.toFloat(), center.y.toFloat() + distancePx),
            ),
        )
        expectedText = editor.text()
        expectedSelection = editor.selection().last
        checkpoint("swipeBackspaceVertical($distancePx)")
    }

    fun injectCancelledSwipe(word: String, transform: PathTransform = PathTransform(cancelAfterPoint = 1)) {
        require(transform.cancelAfterPoint != null) { "Cancelled swipe transform must specify cancelAfterPoint" }
        swipePath(word, transform)
    }

    fun injectSplitWords(words: List<String>, expected: String? = null) {
        require(words.size >= 2) { "Split gesture needs at least two words/parts" }
        val window = keyboard()
        val keySizePx = window.surfaceBounds.width().toFloat() / 10f
        val paths = words.map { word -> SwipeFixtures.pathThroughQwerty(word, keySizePx).points }
        pointer.injectMultiPointer(paths, window.surfaceBounds)
        if (expected != null) {
            editor.waitForText(expected)
            expectedText = expected
            expectedSelection = editor.selection().last
        }
        checkpoint("injectSplitWords(${words.joinToString("+")})")
    }

    fun longPressKey(key: String) {
        val marked = device.findObject(By.desc(keyDescription(key.lowercase())))
            ?: error("Missing key '$key'")
        val bounds = marked.visibleBounds
        pendingPointerEvents = pointer.injectLongPress(
            centerX = (bounds.left + bounds.right) / 2f,
            centerY = (bounds.top + bounds.bottom) / 2f,
        )
        checkpoint("longPressKey($key)")
    }

    fun pressKey(keyCode: Int) {
        editor.pressKey(keyCode)
        checkpoint("pressKey($keyCode)")
    }

    fun assertText(expected: String) {
        editor.waitForText(expected)
        check(editor.text() == expected) { "Expected text '$expected', observed '${editor.text()}'" }
        expectedText = expected
        expectedSelection = editor.selection().last
        checkpoint("assertText")
    }

    fun assertLanguage(expected: Language) {
        check(expectedIme == system.iaidoImeId) { "Language markers are unavailable on the reference IME" }
        check(keyboard().language == expected) {
            "Expected language $expected, observed ${keyboard().language}"
        }
        expectedLanguage = expected
        checkpoint("assertLanguage($expected)")
    }

    fun assertKeyboardGeometry() {
        val window = keyboard()
        val display = android.graphics.Rect(0, 0, device.displayWidth, device.displayHeight)
        val navigationInset = instrumentation.targetContext.resources.getIdentifier(
            "navigation_bar_height",
            "dimen",
            "android",
        ).takeIf { it != 0 }
            ?.let(instrumentation.targetContext.resources::getDimensionPixelSize)
            ?: 0
        val safeBottom = navigationSafeBottom(display, navigationInset)
        check(window.surfaceBounds.bottom <= safeBottom) {
            "Keyboard surface reaches navigation area: surface=${window.surfaceBounds} safeBottom=$safeBottom"
        }
        check(window.rootBounds.bottom <= display.bottom) {
            "Keyboard root is outside display: root=${window.rootBounds} display=$display"
        }
        check(window.keyBounds.values.all { it.bottom <= window.surfaceBounds.bottom }) {
            "A key extends below the marked keyboard surface: keys=${window.keyBounds} surface=${window.surfaceBounds}"
        }
        checkpoint("assertKeyboardGeometry")
    }

    fun hideAndShowKeyboard() {
        system.hideKeyboard()
        editor.focus()
        checkpoint("hideAndShowKeyboard")
    }

    fun relaunchHost() {
        system.launchHost()
        editor.focus()
        checkpoint("relaunchHost")
    }

    fun backgroundAndForeground() {
        device.pressHome()
        system.launchHost()
        editor.focus()
        checkpoint("backgroundAndForeground")
    }

    fun recreateInputView() {
        system.enableAndSelect(system.iaidoImeId)
        expectedIme = system.iaidoImeId
        checkpoint("recreateInputView")
    }

    fun captureScreenshot(name: String): java.io.File =
        ArtifactWriter.captureScreenshot(name, device)

    fun state(): ImeScenarioState = ImeScenarioState(
        expectedText = expectedText,
        expectedSelection = expectedSelection,
        expectedIme = expectedIme,
        expectedLanguage = expectedLanguage,
        trace = trace.toList(),
    )

    fun switchKeyboard(imeId: String) {
        system.enableAndSelect(imeId)
        editor.focus()
        system.waitForImeVisible(imeId)
        expectedIme = imeId
        checkpoint("switchKeyboard($imeId)")
    }

    fun switchToReferenceKeyboard() = switchKeyboard(system.referenceImeId)

    fun switchBackToIaido() = switchKeyboard(system.iaidoImeId)

    fun tapReferenceCommit() {
        check(expectedIme == system.referenceImeId) { "Reference keyboard is not selected" }
        pendingPointerEvents = editor.tapMarkedKey("Iaido reference commit")
        insertExpected("reference")
        checkpoint("tapReferenceCommit")
    }

    private fun setup() {
        system.enableAndSelect(system.iaidoImeId)
        system.launchHost()
        editor.focus()
        system.waitForImeVisible(system.iaidoImeId)
        editor.clear()
        expectedText = ""
        expectedSelection = 0
        expectedLanguage = Language.ENGLISH
        resetLanguage()
        checkpoint("setup")
    }

    private fun resetLanguage() {
        val current = keyboard().language
        if (current == Language.HEBREW) {
            editor.tapMarkedKey(keyDescription("globe"))
            check(keyboard().language == Language.ENGLISH) { "Could not reset Iaido to English" }
        }
    }

    private fun keyboard(): KeyboardWindow = KeyboardWindowLocator.locate(device)

    private fun checkpoint(action: String) {
        editor.waitForText(expectedText)
        val observedText = editor.text()
        val observedSelection = editor.selection()
        val observedIme = system.selectedInputMethodId()
        val observedLanguage = if (expectedIme == system.iaidoImeId) keyboard().language else expectedLanguage
        val event = ImeScenarioEvent(
            index = trace.size,
            action = action,
            expectedText = expectedText,
            observedText = observedText,
            expectedSelection = expectedSelection,
            observedSelection = observedSelection,
            expectedIme = expectedIme,
            observedIme = observedIme,
            expectedLanguage = expectedLanguage,
            observedLanguage = observedLanguage,
            gestureSeed = pendingGestureSeed,
            pointerEvents = pendingPointerEvents,
        )
        trace += event
        pendingGestureSeed = null
        pendingPointerEvents = emptyList()
        check(observedText == expectedText && observedSelection.first == expectedSelection &&
            observedSelection.last == expectedSelection && observedIme == expectedIme &&
            observedLanguage == expectedLanguage) {
            "IME scenario mismatch at #${event.index} '$action':\n$event"
        }
    }

    private fun keyDescription(key: String): String =
        KeyboardWindowLocator.KEY_DESCRIPTION_PREFIX + key

    private fun insertExpected(value: String) {
        val cursor = expectedSelection.coerceIn(0, expectedText.length)
        expectedText = expectedText.take(cursor) + value + expectedText.drop(cursor)
        expectedSelection = cursor + value.length
    }

    private fun deleteExpectedOne() {
        if (expectedSelection <= 0) return
        expectedText = expectedText.removeRange(expectedSelection - 1, expectedSelection)
        expectedSelection -= 1
    }
}
