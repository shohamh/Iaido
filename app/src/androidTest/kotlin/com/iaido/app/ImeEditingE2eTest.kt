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
            tapSpace(checkpointEach = false)
            swipeWord("world")
            pressBackspace(5, checkpointEach = false)
            swipeWord("there")
            assertText("Hello there")
        }
    }

    @Test
    fun middleCursorEditInsertsWithoutLosingSurroundingText() {
        scenario().run {
            swipeWord("hello")
            tapSpace(checkpointEach = false)
            swipeWord("world")
            moveCursorLeft(5, checkpointEach = false)
            tapKey("x")
            assertText("Hello xworld")
        }
    }

    @Test
    fun cancelledMistakePathDoesNotMutateText() {
        scenario().run {
            swipeWord("hello")
            tapSpace(checkpointEach = false)
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
            tapKey("t", checkpointEach = false)
            tapKey("e", checkpointEach = false)
            tapKey("h")
            assertText("Teh")
            pressBackspace(3, checkpointEach = false)
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

    @Test
    fun heldBackspaceDeletesRepeatedlyAndAccelerates() {
        scenario().run {
            swipeWord("hello")
            tapSpace(checkpointEach = false)
            swipeWord("world")
            holdBackspace(durationMs = 1_200L)
        }
    }

    @Test
    fun backspaceSwipeShowsLiveDeletionAndRestoresWhenMovedRight() {
        scenario().run {
            swipeWord("hello")
            tapSpace(checkpointEach = false)
            swipeWord("world")
            val snapshots = swipeBackspaceLeftThenRight()
            check(snapshots.distinct().size > 1) { "Expected multiple live deletion states: $snapshots" }
            assertText("Hello world")
        }
    }

    @Test
    fun backspaceVerticalSwipesUndoAndRedo() {
        scenario().run {
            swipeWord("hello")
            swipeBackspaceVertical(distancePx = -96f)
            assertText("")
            swipeBackspaceVertical(distancePx = 96f)
            assertText("Hello")
        }
    }

    private fun scenario() = ImeScenario().also(artifacts::track)
}
