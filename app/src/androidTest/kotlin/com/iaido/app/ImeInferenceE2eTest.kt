package com.iaido.app

import com.iaido.core.typing.SpacingMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeInferenceE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun inferenceKeepsSeparateGesturesWhenTheFixtureFavorsSeparateWords() =
        scenario(ImeScenarioData.AutoSpaceFixture.SEPARATE).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("in", "X in")
            swipeWordExpecting("the", "X in the")
            assertText("X in the")
        }

    @Test
    fun inferenceOffersJoinedReelThenCommitsItOnReleaseAndKeepsCursorAtTheEnd() =
        scenario(ImeScenarioData.AutoSpaceFixture.JOIN_REEL).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("in", "X in")
            swipeWordExpecting("to", "X in to")
            previewReplacementThenCancel(sourceWords = 2, replacementWords = 1)
            assertText("X in to")
            releaseReplacement(sourceWords = 2, replacementWords = 1)
            assertTextAndCursor("X into")
        }

    @Test
    fun inferenceOffersSplitReelThenCommitsItOnReleaseAndCancellationDoesNotMutate() =
        scenario(ImeScenarioData.AutoSpaceFixture.SPLIT_REEL).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("inthe", "X inthe")
            previewReplacementThenCancel(sourceWords = 1, replacementWords = 2)
            assertTextAndCursor("X inthe")
            releaseReplacement(sourceWords = 1, replacementWords = 2)
            assertTextAndCursor("X in the")
        }

    @Test
    fun laterSwipeReanalyzesEarlierBoundaryWithFixtureContext() =
        scenario(ImeScenarioData.AutoSpaceFixture.CONTEXT_REVISION).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("in", "X in")
            swipeWordExpecting("to", "X in to")
            swipeWordExpecting("the", "X into the")
            assertText("X into the")
        }

    @Test
    fun twoFingerInferenceSupportsMergedAndBoundaryPreservingInterpretations() {
        scenario(ImeScenarioData.AutoSpaceFixture.TWO_FINGER_MERGE).run {
            enableInferenceAndPrefix()
            injectSplitWords(listOf("some", "thing"), expected = "X something")
            assertTextAndCursor("X something")
        }
        scenario(ImeScenarioData.AutoSpaceFixture.TWO_FINGER_BOUNDARY).run {
            enableInferenceAndPrefix()
            injectSplitWords(listOf("some", "thing"), expected = "X some thing")
            assertTextAndCursor("X some thing")
        }
    }

    @Test
    fun seventhUnitFreezesOnlyTheOldestOfTheBoundedInferenceRun() =
        scenario(ImeScenarioData.AutoSpaceFixture.SIX_UNIT).run {
            enableInferenceAndPrefix()
            // Each fixture word spans two distinct, non-adjacent-row keys (e.g. "qz" travels
            // from the top row to the bottom row) so it swipes reliably instead of being
            // classified as a tap — see KeyboardGestureClassification.isTapGesture, which
            // treats any gesture confined to a single key as a tap regardless of dictionary
            // content. A single-letter "word" can never satisfy that displacement requirement.
            val words = listOf("qz", "wx", "ec", "rv", "tb", "yn")
            words.forEachIndexed { index, word ->
                swipeWordExpecting(word, "X " + words.take(index + 1).joinToString(" "))
            }
            // The 7th unit ("um") pushes the window past its six-unit bound, freezing the
            // oldest unit ("qz") while the fixture's overwhelmingly frequent "ynum" entry wins
            // the re-segmentation of the newest two units ("yn" + "um") into one merged word.
            swipeWordExpecting("um", "X " + words.dropLast(1).joinToString(" ") + " ynum")
            assertTextAndCursor("X qz wx ec rv tb ynum")
        }

    @Test
    fun lowConfidenceFixtureLeavesTheTopRecognitionUnchanged() =
        scenario(ImeScenarioData.AutoSpaceFixture.LOW_CONFIDENCE).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("plain", "X plain")
            assertTextAndCursor("X plain")
        }

    private fun scenario(fixture: ImeScenarioData.AutoSpaceFixture) =
        ImeScenario(autoSpaceFixture = fixture).also(artifacts::track)

    private fun ImeScenario.enableInferenceAndPrefix() {
        setSpacingModeForBehaviorTest(SpacingMode.INFER_SPACES)
        tapKey("x")
        tapSpace()
        assertText("X ")
    }
}
