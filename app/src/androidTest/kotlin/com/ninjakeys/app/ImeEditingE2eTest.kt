package com.ninjakeys.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeEditingE2eTest {
    @Test
    fun sentenceCanDeleteWordAndRetypeIt() {
        ImeScenario().run {
            swipeWord("hello")
            tapSpace()
            swipeWord("world")
            pressBackspace(6)
            swipeWord("there")
            assertText("Hello there")
        }
    }

    @Test
    fun middleCursorEditInsertsWithoutLosingSurroundingText() {
        ImeScenario().run {
            swipeWord("hello")
            tapSpace()
            swipeWord("world")
            moveCursorLeft(5)
            tapKey("x")
            assertText("Hello Xworld")
        }
    }

    @Test
    fun cancelledMistakePathDoesNotMutateText() {
        ImeScenario().run {
            swipeWord("hello")
            tapSpace()
            injectCancelledSwipe("world")
            assertText("Hello ")
        }
    }

    @Test
    fun reversedAndJitteredPathRemainsObservableAsARealGesture() {
        ImeScenario().run {
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
        ImeScenario().run {
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
        ImeScenario().run {
            injectSplitWords(listOf("the", "re"), expected = "There")
        }
    }
}
