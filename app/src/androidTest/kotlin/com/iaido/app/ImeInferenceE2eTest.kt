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
    fun inferredJoinPreviewsInlineThenCommitsOnReleaseAndKeepsCursorAtTheEnd() =
        scenario(ImeScenarioData.AutoSpaceFixture.JOIN_REEL).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("in", "X in")
            swipeWordExpecting("to", "X in to")
            val strip = SentenceStripImeDriver()
            val joinSide = strip.sideForAlternative(1, "into")
                ?: error("Expected an inline join option on the inferred 'in' word")
            strip.swipeAlternativeThenCancel(1, joinSide) {
                check(strip.joinPreviewOrNull() != null) { "Inferred join preview did not span its source words" }
                check(strip.editorSnapshot().text.trim() == "X in to") {
                    "Join preview mutated editor text before release"
                }
            }
            assertText("X in to")
            strip.swipeAlternative(1, joinSide) {
                check(strip.editorSnapshot().text.trim() == "X in to") {
                    "Join preview mutated editor text before release"
                }
            }
            assertTextAndCursor("X into")
        }

    @Test
    fun inferredSplitPreviewsAsSentenceWordsThenCommitsOnReleaseAndCancellationDoesNotMutate() =
        scenario(ImeScenarioData.AutoSpaceFixture.SPLIT_REEL).run {
            enableInferenceAndPrefix()
            swipeWordExpecting("inthe", "X inthe")
            val strip = SentenceStripImeDriver()
            val splitSide = strip.sideForAlternative(1, "in the")
                ?: error("Expected an inline split option on the inferred 'inthe' word")
            strip.swipeAlternativeThenCancel(1, splitSide) {
                check(strip.previewTextOrNull().orEmpty().contains("in the", ignoreCase = true)) {
                    "Split preview did not show its output words"
                }
                check(strip.editorSnapshot().text.trim() == "X inthe") {
                    "Split preview mutated editor text before release"
                }
            }
            assertTextAndCursor("X inthe")
            strip.swipeAlternative(1, splitSide) {
                check(strip.editorSnapshot().text.trim() == "X inthe") {
                    "Split preview mutated editor text before release"
                }
            }
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
