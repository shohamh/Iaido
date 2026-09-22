package com.iaido.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeReelE2eTest {
    @get:Rule
    val artifacts = FailureArtifactRule()

    @Test
    fun stripCaretTracksWordCharacterAndGapTaps() {
        ImeScenario().also(artifacts::track).run {
            typeText("we put")
            val text = state().expectedText
            val strip = SentenceStripImeDriver()

            strip.tapWord(0)
            val wordEnd = text.indexOf(' ')
            check(strip.editorSnapshot().selection.last == wordEnd) {
                "Tapping the first word should place the host cursor at its end: " + strip.editorSnapshot()
            }
            check(strip.cursorOffset() == wordEnd) { "Strip caret did not mirror word-end cursor" }
            assertText(text)

            strip.tapCharacter(1, "put", 1)
            val characterBoundary = text.indexOf("put") + 1
            check(strip.editorSnapshot().selection.last == characterBoundary) {
                "Character tap did not place the caret at the requested boundary: " + strip.editorSnapshot()
            }
            check(strip.cursorOffset() == characterBoundary) { "Strip caret diverged after character tap" }
            assertText(text)

            strip.tapGap(0, 1)
            val gapCursor = strip.editorSnapshot().selection.last
            val secondWordStart = text.indexOf("put")
            check(gapCursor in wordEnd..secondWordStart) {
                "Gap tap placed the caret outside the word boundary: cursor=" + gapCursor + " text='" + text + "'"
            }
            check(strip.cursorOffset() == gapCursor) { "Strip caret diverged after gap tap" }
            assertText(text)
        }
    }

    @Test
    fun verticalAlternativePreviewDoesNotCommitUntilReleaseAndCanSwapBack() {
        ImeScenario().also(artifacts::track).run {
            swipeWord("there")
            val strip = SentenceStripImeDriver()
            val before = strip.editorSnapshot().text
            val side = strip.alternativesForWord(0).firstOrNull()?.let { node ->
                if (node.contentDescription.orEmpty().contains("side=above")) {
                    SentenceStripImeDriver.Side.ABOVE
                } else {
                    SentenceStripImeDriver.Side.BELOW
                }
            } ?: error("Expected an autocorrect alternative for 'there'")

            strip.swipeAlternative(0, side) {
                check(strip.previewTextOrNull() != null) { "Whole-sentence preview was not exposed while held" }
                check(strip.editorSnapshot().text == before) { "Preview mutated editor text before release" }
            }
            val corrected = strip.editorSnapshot().text
            check(corrected != before) { "Releasing on an alternative did not commit the correction" }
            assertText(corrected)

            strip.swipeAlternative(0, side) {
                check(strip.editorSnapshot().text == corrected) {
                    "The reverse preview mutated editor text before release"
                }
            }
            check(strip.editorSnapshot().text == before) {
                "Swiping in the same direction should swap back to the previous word"
            }
            assertText(before)
        }
    }

    @Test
    fun splitPreviewShowsAlotAsTwoWordsAndCommitsAtomically() {
        ImeScenario(autoSpaceFixture = ImeScenarioData.AutoSpaceFixture.SPLIT_ALOT)
            .also(artifacts::track).run {
                typeText("alot")
                val strip = SentenceStripImeDriver()
                val before = strip.editorSnapshot().text
                val side = strip.sideForAlternative(0, "a lot")
                    ?: error("Expected the 'a lot' split alternative for 'alot'")

                strip.swipeAlternative(0, side) {
                    check(strip.previewTextOrNull().orEmpty().contains("a lot", ignoreCase = true)) {
                        "Split preview did not show both output words"
                    }
                    check(strip.editorSnapshot().text == before) {
                        "Split preview mutated editor text before release"
                    }
                }

                val committed = strip.editorSnapshot().text
                check(committed.trim().equals("a lot", ignoreCase = true)) {
                    "Expected one atomic 'alot' -> 'a lot' replacement, got '" + committed + "'"
                }
                check(strip.visibleWordIndices().distinct().size == 2) {
                    "The committed split should expose two independent word lanes"
                }
                assertText(committed)
            }
    }

    @Test
    fun forwardJoinPreviewUnitesInAndToAndCommitsOnRelease() = assertJoinFrom(sourceWordIndex = 0)

    @Test
    fun backwardJoinPreviewFromToUnitesInAndToAndCommitsOnRelease() = assertJoinFrom(sourceWordIndex = 1)

    @Test
    fun normalInsideAlternativeOnInDoesNotConsumeFollowingTo() {
        ImeScenario(autoSpaceFixture = ImeScenarioData.AutoSpaceFixture.JOIN_REEL)
            .also(artifacts::track).run {
                swipeWord("in")
                tapSpace(checkpointEach = false)
                swipeWord("to")
                tapSpace(checkpointEach = false)

                val strip = SentenceStripImeDriver()
                val side = strip.sideForAlternative(0, "inside")
                    ?: error("Expected the normal 'inside' autocorrect for 'in'")
                strip.swipeAlternative(0, side) {
                    check(strip.joinPreviewOrNull() == null) {
                        "A normal one-word autocorrect was incorrectly shown as a join"
                    }
                }

                val committed = strip.editorSnapshot().text.trim()
                check(committed.equals("Inside to", ignoreCase = true)) {
                    "Correcting 'in' to 'inside' must preserve the following 'to', got '$committed'"
                }
                check(strip.visibleWordIndices().distinct().size == 2) {
                    "Normal autocorrect removed or duplicated the neighboring word lane"
                }
                assertText(strip.editorSnapshot().text)
            }
    }

    @Test
    fun deletionPreviewUsesWordMidpointsAndIsOneUndoableAction() {
        ImeScenario().also(artifacts::track).run {
            typeText("one two three")
            val strip = SentenceStripImeDriver()
            val before = strip.editorSnapshot().text

            strip.dragAcrossWords(startIndex = 1, endIndex = 2) {
                check(strip.deletionPreviewOrNull() != null) { "Crossing the neighboring word did not show deletion" }
                check(strip.editorSnapshot().text == before) { "Deletion preview changed text before release" }
                check(strip.alternativesForWord(1).isEmpty()) {
                    "Alternatives should be hidden for a word previewed for deletion"
                }
            }

            val after = strip.editorSnapshot().text
            check(after.equals(before.substringBefore(' '), ignoreCase = true)) {
                "Expected only the previewed trailing words to be deleted: '" + before + "' -> '" + after + "'"
            }
            check(strip.editorSnapshot().selection.last == after.length) {
                "Deletion did not restore the cursor at the deletion start"
            }
            assertText(after)

            strip.tapUndo()
            assertText(before)
            strip.tapRedo()
            assertText(after)
        }
    }

    @Test
    fun hebrewDeletionPreviewBoundsCoverTheRenderedWords() {
        ImeScenario().also(artifacts::track).run {
            switchLanguageForScreenshotTest()
            replaceEditorTextForTest(
                "\u05e9\u05dc\u05d5\u05dd \u05e2\u05d5\u05dc\u05dd \u05d7\u05dc\u05e7\u05d9\u05dd " +
                    "\u05d9\u05d7\u05d9\u05d3\u05d4 \u05de\u05e6\u05d0\u05d4 \u05de\u05e6\u05d0\u05d4",
            )
            val strip = SentenceStripImeDriver()

            strip.swipeApproximateHebrewDeletion {
                SystemClock.sleep(350L)
                captureScreenshot("hebrew-deletion-preview-bounds")
                val deletion = strip.deletionPreviewOrNull()
                if (deletion != null) {
                    check(strip.strip().visibleBounds.contains(deletion.visibleBounds)) {
                        "Hebrew delete outline ${deletion.visibleBounds} escaped the strip " +
                        "${strip.strip().visibleBounds}"
                    }
                }
            }
            assertText(strip.editorSnapshot().text)
        }
    }

    @Test
    fun heldCursorScrubContinuesThroughTheRightEdgeZone() {
        ImeScenario().also(artifacts::track).run {
            typeText("one two three four five six seven eight nine ten eleven twelve")
            val text = state().expectedText
            val strip = SentenceStripImeDriver()
            strip.tapWord(5)
            val beforeOffset = strip.editorSnapshot().selection.last
            assertText(text)

            strip.holdWordAndDragToEdge(5, SentenceStripImeDriver.Direction.RIGHT) {
                check(strip.editorSnapshot().text == text) { "Cursor scrubbing changed the sentence" }
                check(strip.cursorOffset() == strip.editorSnapshot().selection.last) {
                    "The strip caret stopped matching InputConnection during edge scrolling"
                }
            }

            check(strip.editorSnapshot().selection.last > beforeOffset) {
                "Holding at the right edge did not keep moving the cursor through later words"
            }
            check(strip.editorSnapshot().selection.last <= text.length) {
                "Edge scrolling wrapped the cursor past the sentence end"
            }
            assertText(text)
        }
    }

    @Test
    fun undoRedoStayVisibleHaveIndependentAvailabilityAndKeepMultipleSteps() {
        ImeScenario().also(artifacts::track).run {
            clearText()
            val strip = SentenceStripImeDriver()
            check(!strip.undoEnabled()) { "Undo must be visible but disabled with an empty history" }
            check(!strip.redoEnabled()) { "Redo must be visible but disabled with an empty history" }

            typeText("we put")
            val typed = strip.editorSnapshot().text
            check(strip.undoEnabled() && !strip.redoEnabled()) {
                "Typing should enable undo independently of redo"
            }

            strip.tapUndo()
            val afterFirstUndo = strip.editorSnapshot().text
            check(afterFirstUndo != typed) { "First undo did not change the sentence" }
            assertText(afterFirstUndo)

            strip.tapUndo()
            val afterSecondUndo = strip.editorSnapshot().text
            check(afterSecondUndo != afterFirstUndo) { "History contains only one undo action" }
            check(strip.redoEnabled()) { "Undo should populate the redo history" }
            assertText(afterSecondUndo)

            strip.tapRedo()
            check(strip.editorSnapshot().text == afterFirstUndo) { "First redo did not restore the previous step" }
            assertText(afterFirstUndo)

            val beforeHeldUndo = strip.editorSnapshot().text
            strip.holdUndo {
                check(strip.nodeOrNull("Iaido history preview") != null) {
                    "Holding undo did not show its action and before/after preview"
                }
                check(strip.editorSnapshot().text == beforeHeldUndo) {
                    "The long-press preview applied before release"
                }
            }
            val afterHeldUndo = strip.editorSnapshot().text
            check(afterHeldUndo != beforeHeldUndo) { "Releasing the held undo did not apply it" }
            assertText(afterHeldUndo)

            tapKey("x", checkpointEach = false)
            check(!strip.redoEnabled()) { "A new edit after undo must clear redo history" }
        }
    }

    private fun assertJoinFrom(sourceWordIndex: Int) {
        ImeScenario(autoSpaceFixture = ImeScenarioData.AutoSpaceFixture.JOIN_REEL)
            .also(artifacts::track).run {
                swipeWord("in")
                tapSpace(checkpointEach = false)
                swipeWord("to")
                tapSpace(checkpointEach = false)

                val strip = SentenceStripImeDriver()
                val before = strip.editorSnapshot().text
                val sourceBounds = android.graphics.Rect(strip.word(0).visibleBounds).apply {
                    union(strip.word(1).visibleBounds)
                }
                val side = strip.sideForAlternative(sourceWordIndex, "into")
                    ?: error("Expected the join alternative from source word " + sourceWordIndex)

                strip.swipeAlternative(sourceWordIndex, side) {
                    check(strip.editorSnapshot().text == before) {
                        "Join preview changed editor text before release"
                    }
                    val join = strip.joinPreviewOrNull()
                        ?: error("Join preview did not expose its two-word union")
                    check(join.visibleBounds.contains(sourceBounds)) {
                        "Join outline " + join.visibleBounds + " did not cover both source words " + sourceBounds
                    }
                    check(strip.previewTextOrNull().orEmpty().contains("into", ignoreCase = true)) {
                        "Whole-sentence preview did not show the joined word"
                    }
                }

                val committed = strip.editorSnapshot().text.trim()
                check(committed.equals("Into", ignoreCase = true)) {
                    "Expected atomic 'in to' -> 'into', got '" + committed + "'"
                }
                check(strip.visibleWordIndices().distinct().size == 1) {
                    "Join commit should leave one word lane and no duplicate source lane"
                }
                assertText(strip.editorSnapshot().text)
            }
    }

    private fun ImeScenario.typeText(value: String) {
        value.forEach { character ->
            if (character == ' ') tapSpace(checkpointEach = false)
            else tapKey(character.toString(), checkpointEach = false)
        }
    }
}
