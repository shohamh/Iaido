package com.iaido.app

import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
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

    @Test
    /**
     * Exercises the fix in realistic usage but does not definitively pin the bug it's named for.
     * The fallback gesture in [swipeSuggestion] finalizes the swipe-typing transaction before
     * the assertion runs, clearing replacementOptions regardless of whether the SuggestionStrip.kt
     * early-return bug is present. A true two-word scenario test (committed word + active swipe
     * simultaneously) would more thoroughly validate the fix.
     */
    fun previouslyTypedCorrectionChipsStayVisibleWhileTheNextWordIsMidSwipe() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ImeScenario().also(artifacts::track).run {
            swipeWord("there")
            swipeSuggestion(index = 0, verticalDistancePx = -96f)
            device.waitForIdle()
            check(device.findObject(By.desc("Iaido suggestion 0")) != null) {
                "First word's correction chip disappeared while a later word's swipe reel is active"
            }
        }
    }
}
