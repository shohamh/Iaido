package com.iaido.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeEditingE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun sentenceCanDeleteWordAndRetypeIt() {
        scenario().run {
            swipeWord("hello")
            tapSpace()
            swipeWord("world")
            pressBackspace(5)
            swipeWord("there")
            assertText("Hello there")
        }
    }

    @Test
    fun middleCursorEditInsertsWithoutLosingSurroundingText() {
        scenario().run {
            swipeWord("hello")
            tapSpace()
            swipeWord("world")
            moveCursorLeft(5)
            tapKey("x")
            assertText("Hello xworld")
        }
    }

    @Test
    fun cancelledMistakePathDoesNotMutateText() {
        scenario().run {
            swipeWord("hello")
            tapSpace()
            injectCancelledSwipe("world")
            assertText("Hello ")
        }
    }

    @Test
    fun reversedAndJitteredPathRemainsObservableAsARealGesture() {
        scenario().run {
            swipePath(
                "there",
                PathTransform(
                    jitterSeed = 17L,
                    jitterPx = 1.5f,
                ),
            )
            assertText("There")
        }
    }

    @Test
    fun typedMistakeCanBeDeletedAndCorrected() {
        scenario().run {
            tapKey("t")
            tapKey("e")
            tapKey("h")
            assertText("Teh")
            pressBackspace(3)
            swipeWord("the")
            assertText("The")
        }
    }

    @Test
    fun splitPointerGestureCanCommitACombinedWord() {
        scenario().run {
            injectSplitWords(listOf("the", "re"), expected = "There")
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
