package com.iaido.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.iaido.core.recognition.SuggestionChip
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuggestionStripVisibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reelShowsTextAfterScrollingFourOptionsDown() {
        val candidates = (0..5).map { "option-$it" }

        composeRule.setContent {
            var selectedIndex by remember { mutableIntStateOf(0) }
            MaterialTheme {
                SuggestionStrip(
                    chips = listOf(
                        SuggestionChip(
                            word = candidates[selectedIndex],
                            alternatives = candidates,
                            selectedIndex = selectedIndex,
                            id = 1,
                        ),
                    ),
                    rtl = false,
                    onRelease = { _, candidateIndex -> selectedIndex = candidateIndex },
                )
            }
        }

        repeat(4) {
            composeRule.onNodeWithContentDescription("Iaido suggestion 0")
                .performTouchInput {
                    val delta = with(density) { 28.dp.toPx() * 1.25f }
                    val center = this.center
                    swipe(
                        start = Offset(center.x, center.y + delta / 2f),
                        end = Offset(center.x, center.y - delta / 2f),
                        durationMillis = 250,
                    )
                }
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText("option-4", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun reelsUseTheirOwnCandidateCountForHeight() {
        composeRule.setContent {
            MaterialTheme {
                SuggestionStrip(
                    chips = listOf(
                        SuggestionChip(
                            word = "one",
                            alternatives = listOf("two"),
                            selectedIndex = 0,
                            id = 1,
                        ),
                        SuggestionChip(
                            word = "only",
                            alternatives = listOf("only"),
                            selectedIndex = 0,
                            id = 2,
                        ),
                    ),
                    rtl = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Iaido suggestion 0")
            .assertHeightIsEqualTo(56.dp)
        composeRule.onNodeWithContentDescription("Iaido suggestion 1")
            .assertHeightIsEqualTo(28.dp)
        composeRule.onNodeWithContentDescription(SUGGESTION_STRIP_DESCRIPTION)
            .assertHeightIsEqualTo(56.dp)
    }

    @Test
    fun focusedWordIsScrolledIntoViewWhenTheCursorMoves() {
        var focusedId by mutableIntStateOf(1)

        composeRule.setContent {
            MaterialTheme {
                SuggestionStrip(
                    chips = (1..4).map { id ->
                        SuggestionChip(
                            word = "word$id",
                            alternatives = listOf("word$id", "option$id"),
                            selectedIndex = 0,
                            id = id,
                        )
                    },
                    rtl = false,
                    focusedChipId = focusedId,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Iaido suggestion 1").assertIsDisplayed()

        composeRule.runOnIdle { focusedId = 4 }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Iaido suggestion 3").assertIsDisplayed()
    }

    @Test
    fun focusedWordIsScrolledBackIntoViewWhenTheCursorMovesBackAndForth() {
        var focusedId by mutableIntStateOf(1)

        composeRule.setContent {
            MaterialTheme {
                SuggestionStrip(
                    chips = (1..8).map { id ->
                        SuggestionChip(
                            word = "word$id",
                            alternatives = listOf("word$id", "option$id"),
                            selectedIndex = 0,
                            id = id,
                        )
                    },
                    rtl = false,
                    focusedChipId = focusedId,
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Iaido suggestion 0").assertIsDisplayed()

        composeRule.runOnIdle { focusedId = 8 }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Iaido suggestion 7").assertIsDisplayed()

        composeRule.runOnIdle { focusedId = 2 }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Iaido suggestion 1").assertIsDisplayed()
    }
}
