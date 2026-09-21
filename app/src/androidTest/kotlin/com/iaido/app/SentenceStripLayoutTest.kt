package com.iaido.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iaido.core.language.Language
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceStripLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun stripShowsSentenceLanesAlternativesCaretAndIndependentHistoryControls() {
        composeRule.setContent {
            MaterialTheme {
                SentenceStrip(
                    state = sampleState(),
                    rtl = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Iaido sentence strip", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Iaido sentence word index=0", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Iaido sentence alternative word=0 side=above",
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Iaido sentence cursor offset=2").assertExists()
        composeRule.onNodeWithContentDescription("Iaido undo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Iaido redo").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Iaido sentence strip", substring = true).assertHeightIsEqualTo(114.dp)
    }

    @Test
    fun tappingCurrentWordPlacesSelectionAtItsEnd() {
        var selection = -1
        val actions = object : SentenceStripActions {
            override fun setSelection(start: Int, endExclusive: Int): Boolean {
                selection = start
                return true
            }
            override fun commitWordReplacement(wordId: String, expectedCurrent: String, replacement: String) = false
            override fun commitReplacement(replacement: SentenceStripReplacement) = false
            override fun commitDeletion(preview: SentenceDeletionPreview) = false
            override fun undo() = false
            override fun redo() = false
        }
        composeRule.setContent {
            MaterialTheme { SentenceStrip(sampleState(), rtl = false, actions = actions) }
        }

        composeRule.onNodeWithText("we").performClick()
        composeRule.runOnIdle { assertEquals(2, selection) }
    }

    private fun sampleState() = SentenceStripState(
        sentenceText = "we put it",
        sentenceStart = 0,
        selectionStart = 2,
        selectionEnd = 2,
        words = listOf(
            SentenceStripWord("word:0:2", "we", 0, 2, above = "well", below = "we're"),
            SentenceStripWord("word:3:6", "put", 3, 6, above = "but", below = "out"),
            SentenceStripWord("word:7:9", "it", 7, 9, above = "into", below = null),
        ),
        replacementOptions = emptyList(),
        language = Language.ENGLISH,
        canUndo = true,
        canRedo = false,
    )
}
