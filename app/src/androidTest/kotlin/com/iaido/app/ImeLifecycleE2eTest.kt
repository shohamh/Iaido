package com.iaido.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeLifecycleE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun textSurvivesKeyboardHideShowHostRelaunchAndInputViewRecreation() {
        val scenario = ImeScenario()
        artifacts.track(scenario)
        scenario.run {
            swipeWord("hello")
            tapSpace(checkpointEach = false)
            swipeWord("world")
            hideAndShowKeyboard()
            assertText("Hello world")
            relaunchHost()
            assertText("Hello world")
            backgroundAndForeground()
            assertText("Hello world")
            recreateInputView()
            assertText("Hello world")
        }
    }
}
