package com.ninjakeys.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeEnglishE2eTest {
    @Test
    fun englishSentenceUsesRealSwipesSpacesAndPunctuation() {
        ImeScenario().run {
            ImeScenarioData.englishSmoke.forEachIndexed { index, word ->
                swipeWord(word)
                if (index < ImeScenarioData.englishSmoke.lastIndex) tapSpace()
            }
            tapKey(".")
            assertText("There is a ninja.")
        }
    }
}
