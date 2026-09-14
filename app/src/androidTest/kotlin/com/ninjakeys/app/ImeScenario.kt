package com.ninjakeys.app

import android.app.Instrumentation
import android.graphics.PointF
import android.view.KeyEvent
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.ninjakeys.core.language.Language
import com.ninjakeys.core.testing.SwipeFixtures

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
    val stepMs: Long = 16L,
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

    private var expectedText = ""
    private var expectedSelection = 0
    private var expectedLanguage = Language.ENGLISH
    private var expectedIme = system.ninjaKeysImeId

    fun run(block: ImeScenario.() -> Unit) {
        setup()
        try {
            block()
        } finally {
            system.hideKeyboard()
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
            error("Hebrew swipe fixtures are added by the bilingual scenarios")
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
        pointer.injectSwipe(
            points = injectedPoints,
            surfaceBounds = window.surfaceBounds,
            stepMs = transform.stepMs,
            jitterSeed = transform.jitterSeed,
            jitterPx = transform.jitterPx,
            cancel = cancelled,
        )
        if (cancelled) {
            checkpoint("cancelledSwipe($word)")
            return
        }
        val committed = if (expectedText.isEmpty() || expectedText.last() in ".!?\n") {
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
            editor.tapMarkedKey(keyDescription(logicalKey))
            expectedLanguage = if (before == Language.ENGLISH) Language.HEBREW else Language.ENGLISH
            checkpoint("switchLanguage")
            return
        }
        editor.tapMarkedKey(keyDescription(logicalKey))
        when (logicalKey) {
            "space" -> {
                insertExpected(" ")
            }
            "backspace" -> {
                deleteExpectedOne()
            }
            else -> {
                val value = if (logicalKey.length == 1 && logicalKey.first().isLetter() &&
                    (expectedText.isEmpty() || expectedText.last() in ".!?\n")
                ) logicalKey.uppercase() else logicalKey
                insertExpected(value)
            }
        }
        checkpoint("tapKey($key)")
    }

    fun tapSpace() = tapKey("space")

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
            editor.pressKey(KeyEvent.KEYCODE_DPAD_LEFT)
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
        val suggestion = device.findObject(By.desc("NinjaKeys suggestion $index"))
            ?: error("Missing suggestion $index")
        val bounds = suggestion.visibleBounds
        pointer.injectScreenSwipe(
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
        pointer.injectLongPress(
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
        check(keyboard().language == expected) {
            "Expected language $expected, observed ${keyboard().language}"
        }
        expectedLanguage = expected
        checkpoint("assertLanguage($expected)")
    }

    fun state(): ImeScenarioState = ImeScenarioState(
        expectedText = expectedText,
        expectedSelection = expectedSelection,
        expectedIme = expectedIme,
        expectedLanguage = expectedLanguage,
        trace = trace.toList(),
    )

    private fun setup() {
        system.launchHost()
        system.enableAndSelect(system.ninjaKeysImeId)
        editor.focus()
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
            check(keyboard().language == Language.ENGLISH) { "Could not reset NinjaKeys to English" }
        }
    }

    private fun keyboard(): KeyboardWindow = KeyboardWindowLocator.locate(device)

    private fun checkpoint(action: String) {
        val observedText = editor.text()
        val observedSelection = editor.selection()
        val observedIme = system.selectedInputMethodId()
        val observedLanguage = keyboard().language
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
        )
        trace += event
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
