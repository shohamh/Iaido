package com.ninjakeys.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ninjakeys.core.language.Language
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
            assertLanguage(Language.HEBREW)
            swipeWord("\u05d0\u05e0\u05d9")
            tapSpace()
            swipeWord("\u05dc\u05d0")
            tapSpace()
            swipeWord("\u05d6\u05d4")
            assertText("\u05d0\u05e0\u05d9 \u05dc\u05d0 \u05d6\u05d4")
            switchLanguage()
            assertLanguage(Language.ENGLISH)
            tapKey(".")
            tapSpace()
            swipeWord("hello")
            assertText("\u05d0\u05e0\u05d9 \u05dc\u05d0 \u05d6\u05d4. Hello")
        }
    }

    @Test
    fun twoFingerLanguageGesturePreservesExistingText() {
        scenario().run {
            swipeWord("hello")
            tapSpace()
            twoFingerLanguageSwitch()
            assertLanguage(Language.HEBREW)
            tapSpace()
            swipeWord("\u05d0\u05e0\u05d9")
            assertText("Hello \u05d0\u05e0\u05d9")
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
