package com.iaido.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeEnglishE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun englishSentenceUsesRealSwipesSpacesAndPunctuation() {
        scenario().run {
            ImeScenarioData.englishSmoke.forEachIndexed { index, word ->
                swipeWord(word)
                if (index < ImeScenarioData.englishSmoke.lastIndex) tapSpace(checkpointEach = false)
            }
            tapKey(".")
            assertText("There is a ninja.")
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
