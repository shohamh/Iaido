package com.ninjakeys.app

import android.app.Instrumentation
import androidx.test.InstrumentationRegistry
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
        pointer.injectSwipe(path.points, window.surfaceBounds)
        val committed = if (expectedText.isEmpty() || expectedText.last() in ".!?\n") {
            word.replaceFirstChar { it.uppercase() }
        } else {
            word
        }
        expectedText += committed
        expectedSelection += committed.length
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
                expectedText += " "
                expectedSelection += 1
            }
            "backspace" -> {
                if (expectedText.isNotEmpty()) {
                    expectedText = expectedText.dropLast(1)
                    expectedSelection = (expectedSelection - 1).coerceAtLeast(0)
                }
            }
            else -> {
                val value = if (logicalKey.length == 1 && logicalKey.first().isLetter() &&
                    (expectedText.isEmpty() || expectedText.last() in ".!?\n")
                ) logicalKey.uppercase() else logicalKey
                expectedText += value
                expectedSelection += value.length
            }
        }
        checkpoint("tapKey($key)")
    }

    fun tapSpace() = tapKey("space")

    fun pressBackspace(count: Int = 1) {
        require(count >= 0) { "Backspace count cannot be negative" }
        repeat(count) {
            editor.pressBackspace()
            if (expectedText.isNotEmpty()) {
                expectedText = expectedText.dropLast(1)
                expectedSelection = (expectedSelection - 1).coerceAtLeast(0)
            }
            checkpoint("pressBackspace")
        }
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
}
