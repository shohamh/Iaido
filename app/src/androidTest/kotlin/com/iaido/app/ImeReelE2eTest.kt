package com.iaido.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeReelE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun correctionReelScrollsCommitsAndRemembersTheReleasedCandidate() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("there")
            val before = state().expectedText
            val after = swipeSuggestion(index = 0, verticalDistancePx = -96f)
            check(after != before) { "Reel release did not select a different candidate: '$before'" }
            val afterSecondRelease = swipeSuggestion(index = 0, verticalDistancePx = -96f)
            check(afterSecondRelease != after) {
                "Reel position was not remembered between releases: '$after'"
            }
        }
    }

    @Test
    fun correctionReelRespondsToAnImmediateSwipeWithoutRequiringALongPressFirst() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("there")
            val before = state().expectedText
            val after = swipeSuggestionImmediately(index = 0, verticalDistancePx = -96f)
            check(after != before) { "Reel did not respond to an immediate (no long-press) swipe: '$before'" }
        }
    }
}
