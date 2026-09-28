package com.iaido.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.iaido.core.language.Language
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeBilingualE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun hebrewSentenceUsesRtlLayoutAndCanReturnToEnglish() {
        scenario().run {
            switchLanguage()
            swipeWord("\u05d0\u05e0\u05d9")
            tapSpace(checkpointEach = false)
            swipeWord("\u05dc\u05d0")
            tapSpace(checkpointEach = false)
            swipeWord("\u05d6\u05d4")
            assertText("\u05d0\u05e0\u05d9 \u05dc\u05d0 \u05d6\u05d4")
            switchLanguage()
            tapKey(".")
            tapSpace(checkpointEach = false)
            swipeWord("hello")
            assertText("\u05d0\u05e0\u05d9 \u05dc\u05d0 \u05d6\u05d4. Hello")
        }
    }

    @Test
    fun twoFingerLanguageGesturePreservesExistingText() {
        scenario().run {
            swipeWord("hello")
            tapSpace(checkpointEach = false)
            twoFingerLanguageSwitch()
            swipeWord("\u05d0\u05e0\u05d9")
            assertText("Hello \u05d0\u05e0\u05d9")
        }
    }

    @Test
    fun hebrewBackspaceUsesAtLeastOneLetterKeyWidth() {
        scenario().run {
            switchLanguage()
            val screenshot = captureScreenshot("hebrew-backspace-width")
            val window = KeyboardWindowLocator.locate(
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
                expectedLanguage = Language.HEBREW,
            )
            val backspace = requireNotNull(window.keyBounds["backspace"])
            val zayin = requireNotNull(window.keyBounds["\u05d6"])

            assertTrue("Keyboard screenshot missing at ${screenshot.absolutePath}", screenshot.isFile)
            assertTrue(
                "Hebrew backspace should occupy at least one letter key: " +
                    "backspace=$backspace zayin=$zayin",
                backspace.width() >= zayin.width(),
            )
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
