package com.iaido.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeGesturePerformanceE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun switchingToHebrewDoesNotRebuildTheSwipeIndexOnEveryFirstWord() {
        scenario().run {
            clearText()
            swipeCommitTiming("the")
            clearText()

            val hebrewFirstAfterSwitch = mutableListOf<Long>()
            val hebrewWarm = mutableListOf<Long>()
            repeat(4) {
                switchLanguage()
                clearText()
                hebrewFirstAfterSwitch += swipeCommitTiming("\u05d0\u05e0\u05d9").textUpdateAfterReleaseMs
                clearText()
                hebrewWarm += swipeCommitTiming("\u05d0\u05e0\u05d9").textUpdateAfterReleaseMs

                switchLanguage()
                clearText()
                swipeCommitTiming("the")
                clearText()
                swipeCommitTiming("the")
                clearText()
            }

            val coldMedianMs = hebrewFirstAfterSwitch.median()
            val warmMedianMs = hebrewWarm.median()
            val details = "Hebrew first-after-switch=${hebrewFirstAfterSwitch}ms " +
                "(median=${coldMedianMs}ms), same-language=${hebrewWarm}ms " +
                "(median=${warmMedianMs}ms)"
            Log.i("E2E-PERF", "phase=hebrew_cold_swipe $details")
            assertTrue(
                "The first Hebrew swipe after switching should stay close to a warm Hebrew swipe: $details",
                coldMedianMs <= warmMedianMs + 250L,
            )
        }
    }

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private fun scenario() = ImeScenario().also(artifacts::track)
}
