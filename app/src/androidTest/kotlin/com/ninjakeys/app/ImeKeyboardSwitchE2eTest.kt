package com.ninjakeys.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeKeyboardSwitchE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun referenceKeyboardCanBeSelectedAndNinjaKeysRestored() {
        scenario().run {
            swipeWord("hello")
            tapSpace()
            switchToReferenceKeyboard()
            tapReferenceCommit()
            switchBackToNinjaKeys()
            tapSpace()
            swipeWord("world")
            assertText("Hello reference world")
        }
    }

    @Test
    fun keyboardSwitchSurvivesDeletingReferenceToken() {
        scenario().run {
            switchToReferenceKeyboard()
            tapReferenceCommit()
            switchBackToNinjaKeys()
            pressBackspace(9)
            swipeWord("there")
            assertText("There")
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
